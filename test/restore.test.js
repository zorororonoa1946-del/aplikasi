import { test } from "node:test";
import assert from "node:assert/strict";
import {
  mkdtemp,
  readFile,
  writeFile,
  rm,
  access,
  stat,
  symlink,
} from "node:fs/promises";
import { createReadStream } from "node:fs";
import { createHash } from "node:crypto";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { spawn } from "node:child_process";
import { createServer } from "../server.js";
import { encrypt, base64, aad, CHUNK_BYTES } from "../public/format.js";
const pass = "fixture-password-for-streaming";
function command(file, args, { env = {}, input } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [file, ...args], {
      env: { ...process.env, ...env },
      stdio: ["pipe", "pipe", "pipe"],
    });
    let stdout = "",
      stderr = "";
    child.stdout.on("data", (x) => (stdout += x));
    child.stderr.on("data", (x) => (stderr += x));
    child.on("error", reject);
    child.on("exit", (code) => resolve({ code, stdout, stderr }));
    child.stdin.end(input);
  });
}
async function checksum(path) {
  const hash = createHash("sha256");
  for await (const bytes of createReadStream(path)) hash.update(bytes);
  return hash.digest("base64");
}
test("laptop restore streams a >20 MiB file over verified TLS, preserves existing files, rejects traversal, and never publishes tampered output", async () => {
  const root = await mkdtemp(join(tmpdir(), "nusa-restore-"));
  let server;
  try {
    const local = join(root, "local"),
      env = { LOCAL_RELAY_DIR: local };
    assert.equal(
      (
        await command("scripts/relay-local.mjs", ["127.0.0.1", "--init-only"], {
          env,
        })
      ).code,
      0,
    );
    const config = JSON.parse(
      await readFile(join(local, "config.json"), "utf8"),
    );
    const data = join(root, "relay");
    await import("node:fs/promises").then((fs) =>
      fs.mkdir(data, { recursive: true }),
    );
    const id = "12345678-1234-1234-1234-123456789abc",
      keyBytes = crypto.getRandomValues(new Uint8Array(32)),
      key = await crypto.subtle.importKey("raw", keyBytes, "AES-GCM", false, [
        "encrypt",
      ]),
      parts = [],
      hash = createHash("sha256"),
      size = 25 * 1024 * 1024 + 17;
    await import("node:fs/promises").then((fs) =>
      fs.mkdir(join(data, "chunks", id), { recursive: true }),
    );
    for (let offset = 0; offset < size; offset += CHUNK_BYTES) {
      const plain = Buffer.alloc(Math.min(CHUNK_BYTES, size - offset), 90);
      hash.update(plain);
      const iv = crypto.getRandomValues(new Uint8Array(12)),
        cipher = new Uint8Array(
          await crypto.subtle.encrypt(
            { name: "AES-GCM", iv, additionalData: aad(id, parts.length) },
            key,
            plain,
          ),
        );
      parts.push({
        size: plain.length,
        iv: base64(iv),
        sha256: createHash("sha256").update(cipher).digest("base64"),
      });
      await writeFile(
        join(data, "chunks", id, `${parts.length - 1}.bin`),
        cipher,
      );
    }
    const metadata = {
      format: 2,
      fileId: id,
      name: "folder/large.bin",
      size,
      date: new Date().toISOString(),
      key: base64(keyBytes),
      chunkSize: CHUNK_BYTES,
      parts,
      hash: hash.digest("base64"),
    };
    await writeFile(
      join(data, `${id}.json`),
      JSON.stringify(await encrypt(metadata, pass)),
    );
    server = createServer({
      token: config.token,
      dataDir: data,
      tls: {
        cert: await readFile(join(local, "server.pem")),
        key: await readFile(join(local, "server-key.pem")),
      },
    });
    await new Promise((r) => server.listen(0, "127.0.0.1", r));
    const connection = join(root, "connection.txt");
    await writeFile(
      connection,
      `Relay: https://127.0.0.1:${server.address().port}\nToken: ${config.token}\n`,
      { mode: 0o600 },
    );
    const out = join(root, "restored");
    const args = [
      "--connection",
      connection,
      "--ca",
      join(local, "ca.pem"),
      "--out",
      out,
    ];
    let result = await command("scripts/restore.mjs", args, {
      input: pass + "\n",
    });
    assert.equal(result.code, 0, result.stderr);
    assert.equal((await stat(join(out, "folder/large.bin"))).size, size);
    assert.equal(await checksum(join(out, "folder/large.bin")), metadata.hash);
    assert.ok(
      !result.stdout.includes(pass) && !result.stdout.includes(config.token),
    );
    await writeFile(join(out, "folder/large.bin"), "existing user data");
    result = await command("scripts/restore.mjs", args, { input: pass + "\n" });
    assert.equal(result.code, 0, result.stderr);
    assert.equal(
      await readFile(join(out, "folder/large.bin"), "utf8"),
      "existing user data",
    );
    assert.equal(
      await checksum(join(out, `folder/large.${id}.bin`)),
      metadata.hash,
    );
    metadata.name = "../escape.bin";
    await writeFile(
      join(data, `${id}.json`),
      JSON.stringify(await encrypt(metadata, pass)),
    );
    result = await command("scripts/restore.mjs", args, { input: pass + "\n" });
    assert.equal(result.code, 1);
    await assert.rejects(access(join(root, "escape.bin")));
    metadata.name = "bad/partial.bin";
    await writeFile(
      join(data, `${id}.json`),
      JSON.stringify(await encrypt(metadata, pass)),
    );
    const path = join(data, "chunks", id, "6.bin"),
      cipher = await readFile(path);
    cipher[0] ^= 1;
    await writeFile(path, cipher);
    result = await command("scripts/restore.mjs", args, { input: pass + "\n" });
    assert.equal(result.code, 1);
    await assert.rejects(access(join(out, "bad/partial.bin")));
    const bad = await command("scripts/restore.mjs", args, {
      input: "wrong-password-for-test\n",
    });
    assert.equal(bad.code, 1);
  } finally {
    if (server) await new Promise((r) => server.close(r));
    await rm(root, { recursive: true, force: true });
  }
});
