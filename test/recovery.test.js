import { test } from "node:test";
import assert from "node:assert/strict";
import {
  mkdtemp,
  mkdir,
  writeFile,
  readFile,
  link,
  access,
  rm,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createServer } from "../server.js";
const id = "12345678-1234-1234-1234-123456789abc";
const token = "recovery-test-only-long-token-123456";
const envelope = JSON.stringify({
  version: 1,
  salt: Buffer.alloc(16).toString("base64"),
  iv: Buffer.alloc(12).toString("base64"),
  ciphertext: Buffer.alloc(16).toString("base64"),
});
async function fixture(setup, check, quota = 2048) {
  const dir = await mkdtemp(join(tmpdir(), "relay-recovery-"));
  let server;
  try {
    await setup(dir);
    server = createServer({ token, dataDir: dir, maxStorageBytes: quota });
    await new Promise((r) => server.listen(0, "127.0.0.1", r));
    const request = (path, options = {}) =>
      fetch(`http://127.0.0.1:${server.address().port}${path}`, {
        ...options,
        headers: { Authorization: `Bearer ${token}` },
      });
    await check(request, dir);
  } finally {
    if (server) await new Promise((r) => server.close(r));
    await rm(dir, { recursive: true, force: true });
  }
}
test("restart removes interrupted temporary writes while preserving committed hard links and quota", () =>
  fixture(
    async (dir) => {
      const chunks = join(dir, "chunks", id);
      await mkdir(chunks, { recursive: true });
      const temp = join(chunks, `.upload-${id}`);
      await writeFile(temp, Buffer.alloc(1024, 7));
      await link(temp, join(chunks, "0.bin"));
      await writeFile(join(dir, `.upload-${id}`), Buffer.alloc(4096));
    },
    async (req, dir) => {
      assert.equal((await req("/api/files")).status, 200);
      await assert.rejects(access(join(dir, `.upload-${id}`)), {
        code: "ENOENT",
      });
      await assert.rejects(access(join(dir, "chunks", id, `.upload-${id}`)), {
        code: "ENOENT",
      });
      assert.deepEqual(
        await readFile(join(dir, "chunks", id, "0.bin")),
        Buffer.alloc(1024, 7),
      );
      assert.equal(
        (
          await req(`/api/chunks/${id}/1`, {
            method: "PUT",
            body: Buffer.alloc(1024, 8),
          })
        ).status,
        201,
      );
      assert.equal(
        (
          await req(`/api/chunks/${id}/2`, {
            method: "PUT",
            body: Buffer.alloc(16),
          })
        ).status,
        507,
      );
    },
  ));
test("deleting a record also removes its v2 chunks and releases quota", () =>
  fixture(
    async () => {},
    async (req) => {
      assert.equal(
        (
          await req(`/api/chunks/${id}/0`, {
            method: "PUT",
            body: Buffer.alloc(1536),
          })
        ).status,
        201,
      );
      assert.equal(
        (await req(`/api/files/${id}`, { method: "PUT", body: envelope }))
          .status,
        201,
      );
      assert.equal(
        (await req(`/api/files/${id}`, { method: "DELETE" })).status,
        200,
      );
      assert.equal((await req(`/api/chunks/${id}/0`)).status, 404);
      assert.deepEqual(await (await req("/api/files")).json(), []);
      assert.equal(
        (
          await req(`/api/chunks/${id}/1`, {
            method: "PUT",
            body: Buffer.alloc(2048),
          })
        ).status,
        201,
      );
    },
  ));
test("simultaneous uploads cannot overcommit storage quota", () =>
  fixture(
    async () => {},
    async (req) => {
      const statuses = await Promise.all(
        [0, 1, 2, 3].map(
          async (i) =>
            (
              await req(`/api/chunks/${id}/${i}`, {
                method: "PUT",
                body: Buffer.alloc(1024, i),
              })
            ).status,
        ),
      );
      assert.deepEqual(statuses.sort(), [201, 201, 507, 507]);
    },
  ));
