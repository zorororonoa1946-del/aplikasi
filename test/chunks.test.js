import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm, readdir } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createHash } from "node:crypto";
import { createServer } from "../server.js";
import {
  encrypt,
  decrypt,
  validate,
  openChunk,
  base64,
  aad,
  CHUNK_BYTES,
} from "../public/format.js";
const token = "fixture-only-long-relay-token-123";
const id = "12345678-1234-1234-1234-123456789abc";
async function fixture(options, callback) {
  const directory = await mkdtemp(join(tmpdir(), "nusa-chunks-"));
  const server = createServer({ token, dataDir: directory, ...options });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const url = `http://127.0.0.1:${server.address().port}`;
  const req = (path, options = {}) =>
    fetch(url + path, {
      ...options,
      headers: { Authorization: `Bearer ${token}`, ...options.headers },
    });
  try {
    await callback(req, directory);
  } finally {
    await new Promise((r) => server.close(r));
    await rm(directory, { recursive: true, force: true });
  }
}
test("chunk writes are immutable, hash-verifiable, bounded, and not listed as files until manifest publication", () =>
  fixture({}, async (req, directory) => {
    const data = Buffer.alloc(1024, 7),
      path = `/api/chunks/${id}/0`;
    assert.equal((await req(path, { method: "PUT", body: data })).status, 201);
    assert.deepEqual(await (await req("/api/files")).json(), []);
    assert.equal(
      (await req(path, { method: "PUT", body: Buffer.alloc(32, 8) })).status,
      409,
    );
    const head = await req(path, { method: "HEAD" });
    assert.equal(head.status, 200);
    assert.equal(
      head.headers.get("x-content-sha256"),
      createHash("sha256").update(data).digest("base64"),
    );
    assert.deepEqual(Buffer.from(await (await req(path)).arrayBuffer()), data);
    assert.equal(
      (
        await req(`/api/chunks/${id}/1`, {
          method: "PUT",
          body: Buffer.alloc(CHUNK_BYTES + 17),
        })
      ).status,
      413,
    );
    assert.equal(
      (await req(`/api/chunks/${id}/2`, { method: "PUT", body: "short" }))
        .status,
      400,
    );
    assert.equal(
      (await req(`/api/chunks/${id}/-1`, { method: "PUT", body: data })).status,
      404,
    );
    assert.ok(
      (await readdir(join(directory, "chunks", id))).every(
        (name) => !name.startsWith(".upload-"),
      ),
    );
    const manifest = await encrypt(
      { fileId: id, parts: [] },
      "test-password-very-long",
    );
    assert.equal(
      (
        await req(`/api/files/${id}`, {
          method: "PUT",
          body: JSON.stringify(manifest),
        })
      ).status,
      201,
    );
    assert.deepEqual(await (await req("/api/files")).json(), [id]);
  }));
test("quota rejects new data without deleting prior chunks or leaving temporary files", () =>
  fixture({ maxStorageBytes: 2048 }, async (req) => {
    const body = Buffer.alloc(1536, 1);
    assert.equal(
      (await req(`/api/chunks/${id}/0`, { method: "PUT", body })).status,
      201,
    );
    assert.equal(
      (await req(`/api/chunks/${id}/1`, { method: "PUT", body })).status,
      507,
    );
    assert.deepEqual(
      Buffer.from(await (await req(`/api/chunks/${id}/0`)).arrayBuffer()),
      body,
    );
    assert.equal((await req(`/api/chunks/${id}/1`)).status, 404);
  }));
test("failed authentication is rate limited while valid owner credentials remain usable", () =>
  fixture({}, async (req) => {
    for (let i = 0; i < 31; i++) {
      const response = await req("/api/files", {
        headers: { Authorization: "Bearer wrong" },
      });
      assert.equal(response.status, i < 30 ? 401 : 429);
    }
    assert.equal((await req("/api/files")).status, 200);
  }));
test("v2 chunks bind to file and position; malformed manifests and altered bytes are rejected", async () => {
  const raw = crypto.getRandomValues(new Uint8Array(32)),
    iv = crypto.getRandomValues(new Uint8Array(12)),
    key = await crypto.subtle.importKey("raw", raw, "AES-GCM", false, [
      "encrypt",
    ]);
  const original = new TextEncoder().encode("private data");
  const encrypted = new Uint8Array(
    await crypto.subtle.encrypt(
      { name: "AES-GCM", iv, additionalData: aad(id, 0) },
      key,
      original,
    ),
  );
  const value = {
    format: 2,
    fileId: id,
    name: "folder/private.txt",
    size: original.length,
    date: new Date().toISOString(),
    chunkSize: CHUNK_BYTES,
    key: base64(raw),
    parts: [
      {
        size: original.length,
        iv: base64(iv),
        sha256: base64(
          new Uint8Array(await crypto.subtle.digest("SHA-256", encrypted)),
        ),
      },
    ],
  };
  validate(value, id);
  assert.deepEqual(await openChunk(value, id, 0, encrypted), original);
  const tampered = encrypted.slice();
  tampered[0] ^= 1;
  await assert.rejects(openChunk(value, id, 0, tampered), /Checksum/);
  const forged = structuredClone(value);
  forged.parts[0].sha256 = base64(
    new Uint8Array(await crypto.subtle.digest("SHA-256", tampered)),
  );
  await assert.rejects(openChunk(forged, id, 0, tampered));
  const other = "22345678-1234-1234-1234-123456789abc";
  await assert.rejects(openChunk(value, other, 0, encrypted));
  assert.throws(() => validate({ ...value, size: original.length + 1 }, id));
  assert.throws(() => validate({ ...value, key: "YQ==" }, id));
  assert.throws(() => validate({ ...value, parts: [] }, id));
  const legacy = await encrypt(
    { name: "old.txt", size: 3, date: new Date().toISOString(), data: "b2xk" },
    "test-password-very-long",
  );
  assert.equal(
    validate(await decrypt(legacy, "test-password-very-long"), id).name,
    "old.txt",
  );
});
