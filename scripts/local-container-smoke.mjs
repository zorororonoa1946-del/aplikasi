import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { mkdtemp, writeFile, rm, access } from "node:fs/promises";
import { randomUUID, randomBytes, createHash } from "node:crypto";
import https from "node:https";
import { fileURLToPath } from "node:url";
import { join } from "node:path";
import { tmpdir } from "node:os";
import {
  encrypt,
  decrypt,
  validate,
  openChunk,
  aad,
  CHUNK_BYTES,
} from "../public/format.js";
process.chdir(fileURLToPath(new URL("..", import.meta.url)));
const exec = promisify(execFile),
  project = "nusa-lan-test-" + randomUUID().slice(0, 8);
const temporary = await mkdtemp(join(tmpdir(), "nusa-lan-test-"));
const override = join(temporary, "compose.yaml");
const env = { ...process.env, LAN_IP: "127.0.0.1" };
if (
  !env.DOCKER_CONFIG &&
  (await access("/workspace/toolchains/docker-config").then(
    () => true,
    () => false,
  ))
)
  env.DOCKER_CONFIG = "/workspace/toolchains/docker-config";
const docker = async (args) =>
  (await exec("docker", args, { env, maxBuffer: 8 * 1024 * 1024 })).stdout;
const compose = (args) =>
  docker([
    "compose",
    "-p",
    project,
    "-f",
    "compose.yaml",
    "-f",
    override,
    ...args,
  ]);
const report = [];
const pass = (s) => {
  report.push("PASS " + s);
  console.log("PASS " + s);
};
const request = (port, ca, token, path, method = "GET", body) =>
  new Promise((resolve, reject) => {
    const req = https.request(
      {
        hostname: "127.0.0.1",
        port,
        path,
        method,
        ca,
        headers: { Authorization: "Bearer " + token },
      },
      (res) => {
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () =>
          resolve({ status: res.statusCode, body: Buffer.concat(chunks) }),
        );
        res.on("error", reject);
      },
    );
    req.setTimeout(10000, () => req.destroy(Error("HTTPS test timeout")));
    req.on("error", reject);
    req.end(body);
  });
try {
  await writeFile(
    override,
    'services:\n  vault:\n    ports: !override\n      - "127.0.0.1::3443"\n',
  );
  await compose(["up", "-d", "--build", "--wait", "--wait-timeout", "90"]);
  const container = (await compose(["ps", "-q", "vault"])).trim();
  const inspect = JSON.parse(await docker(["inspect", container]))[0];
  assert.equal(inspect.Config.User, "node");
  assert.equal(inspect.HostConfig.ReadonlyRootfs, true);
  assert.equal(inspect.State.Health.Status, "healthy");
  let port = Number(
    (await compose(["port", "vault", "3443"])).trim().split(":").at(-1),
  );
  const config = JSON.parse(
    await compose(["exec", "-T", "vault", "cat", "/data/config.json"]),
  );
  const ca = await compose(["exec", "-T", "vault", "cat", "/data/ca.pem"]);
  const req = (path, method, body, token = config.token) =>
    request(port, ca, token, path, method, body);
  assert.equal((await req("/")).status, 200);
  assert.equal(
    (await req("/api/files", "GET", undefined, "wrong")).status,
    401,
  );
  await assert.rejects(request(port, undefined, config.token, "/api/files"));
  pass(
    "root Compose starts healthy, nonroot/read-only; host published port verifies CA and rejects untrusted TLS/wrong token",
  );
  const id = randomUUID(),
    key = randomBytes(32),
    password = "disposable-lan-audit-password";
  const value = {
    format: 2,
    fileId: id,
    name: "lan-audit.bin",
    size: 25 * 1024 * 1024 + 17,
    date: new Date().toISOString(),
    chunkSize: CHUNK_BYTES,
    key: key.toString("base64"),
    parts: [],
  };
  const aes = await crypto.subtle.importKey("raw", key, "AES-GCM", false, [
    "encrypt",
  ]);
  const originalHash = createHash("sha256");
  for (let offset = 0, i = 0; offset < value.size; offset += CHUNK_BYTES, i++) {
    const plain = Buffer.alloc(
        Math.min(CHUNK_BYTES, value.size - offset),
        i + 1,
      ),
      iv = randomBytes(12);
    originalHash.update(plain);
    const encrypted = Buffer.from(
      await crypto.subtle.encrypt(
        { name: "AES-GCM", iv, additionalData: aad(id, i) },
        aes,
        plain,
      ),
    );
    value.parts.push({
      size: plain.length,
      iv: iv.toString("base64"),
      sha256: createHash("sha256").update(encrypted).digest("base64"),
    });
    assert.equal(
      (await req(`/api/chunks/${id}/${i}`, "PUT", encrypted)).status,
      201,
    );
  }
  const envelope = await encrypt(value, password);
  assert.deepEqual(JSON.parse((await req("/api/files")).body), []);
  assert.equal(
    (await req(`/api/files/${id}`, "PUT", JSON.stringify(envelope))).status,
    201,
  );
  assert.equal(
    (await req(`/api/files/${id}`, "PUT", JSON.stringify(envelope))).status,
    409,
  );
  const expected = originalHash.digest("hex");
  async function restore() {
    const got = validate(
      await decrypt(JSON.parse((await req(`/api/files/${id}`)).body), password),
      id,
    );
    const hash = createHash("sha256");
    for (let i = 0; i < got.parts.length; i++)
      hash.update(
        await openChunk(got, id, i, (await req(`/api/chunks/${id}/${i}`)).body),
      );
    assert.equal(hash.digest("hex"), expected);
  }
  await restore();
  pass(
    "25 MiB+17 byte encrypted chunk upload and independent client restore match SHA-256; overwrite rejected",
  );
  // A hard kill exercises on-disk durability, not only graceful server shutdown.
  await compose(["kill", "-s", "SIGKILL", "vault"]);
  await compose(["up", "-d", "--wait", "--wait-timeout", "90"]);
  // Docker may allocate a new ephemeral host port after restarting the test container.
  port = Number(
    (await compose(["port", "vault", "3443"])).trim().split(":").at(-1),
  );
  assert.equal(
    JSON.parse(
      await compose(["exec", "-T", "vault", "cat", "/data/config.json"]),
    ).token,
    config.token,
  );
  assert.equal(
    await compose(["exec", "-T", "vault", "cat", "/data/ca.pem"]),
    ca,
  );
  await restore();
  pass(
    "SIGKILL and restart preserve CA, token, complete backup and exact restored bytes",
  );
  await writeFile(
    "artifacts/local-container-test-results.txt",
    new Date().toISOString() + "\n" + report.join("\n") + "\n",
  );
} finally {
  await compose([
    "down",
    "--volumes",
    "--remove-orphans",
    "--rmi",
    "local",
  ]).catch(() => {});
  await rm(temporary, { recursive: true, force: true });
}
