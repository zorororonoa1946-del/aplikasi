import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawn } from "node:child_process";
import https from "node:https";
import { createServer } from "../server.js";
const run = (args, env) =>
  new Promise((resolve, reject) => {
    const child = spawn(
      process.execPath,
      ["scripts/relay-local.mjs", ...args],
      { env: { ...process.env, ...env }, stdio: ["ignore", "pipe", "pipe"] },
    );
    let text = "";
    child.stdout.on("data", (x) => (text += x));
    child.stderr.on("data", (x) => (text += x));
    child.on("error", reject);
    child.on("exit", (code) =>
      code === 0 ? resolve(text) : reject(Error(text)),
    );
  });
const request = (url, options) =>
  new Promise((resolve, reject) => {
    const req = https.get(url, options, (res) => {
      res.resume();
      res.on("end", () => resolve(res.statusCode));
    });
    req.on("error", reject);
  });
test("local relay setup preserves identity/token, keeps credentials private, and uses verified TLS with hostname checks", async () => {
  const dir = await mkdtemp(join(tmpdir(), "nusa-local-"));
  let server;
  try {
    const env = { LOCAL_RELAY_DIR: dir };
    const first = await run(["127.0.0.1", "--init-only"], env);
    const config = JSON.parse(await readFile(join(dir, "config.json"), "utf8")),
      ca = await readFile(join(dir, "ca.pem"));
    assert.ok(!first.includes(config.token));
    assert.equal((await stat(join(dir, "config.json"))).mode & 0o777, 0o600);
    assert.equal((await stat(join(dir, "ca-key.pem"))).mode & 0o777, 0o600);
    await run(["127.0.0.1", "--init-only"], env);
    assert.equal(
      JSON.parse(await readFile(join(dir, "config.json"), "utf8")).token,
      config.token,
    );
    assert.deepEqual(await readFile(join(dir, "ca.pem")), ca);
    server = createServer({
      token: config.token,
      dataDir: join(dir, "data"),
      tls: {
        cert: await readFile(join(dir, "server.pem")),
        key: await readFile(join(dir, "server-key.pem")),
      },
    });
    await new Promise((r) => server.listen(0, "127.0.0.1", r));
    const url = `https://127.0.0.1:${server.address().port}`;
    assert.equal(await request(url, { ca }), 200);
    await assert.rejects(request(url, {}));
    await assert.rejects(request(url, { ca, servername: "wrong.example.com" }));
    assert.equal(await request(url + "/api/files", { ca }), 401);
    assert.equal(
      await request(url + "/api/files", {
        ca,
        headers: { Authorization: `Bearer ${config.token}` },
      }),
      200,
    );
  } finally {
    if (server) await new Promise((r) => server.close(r));
    await rm(dir, { recursive: true, force: true });
  }
});
