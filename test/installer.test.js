import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, mkdir, copyFile, readFile, stat, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";

test("relay installer creates private credentials once, refuses invalid domains and preserves existing secrets", async () => {
  const root = await mkdtemp(join(tmpdir(), "nusa-installer-"));
  try {
    await mkdir(join(root, "scripts"));
    await mkdir(join(root, "deployment"));
    const script = join(root, "scripts/init-relay.mjs");
    await copyFile(
      new URL("../scripts/init-relay.mjs", import.meta.url),
      script,
    );
    const run = (domain) =>
      spawnSync(process.execPath, [script, domain], { encoding: "utf8" });
    assert.equal(run("https://invalid.example/path").status, 1);
    const first = run("vault.example.com");
    assert.equal(first.status, 0);
    const file = join(root, "deployment/.env");
    const content = await readFile(file, "utf8");
    assert.match(
      content,
      /^VAULT_DOMAIN=vault\.example\.com\nRELAY_TOKEN=[A-Za-z0-9_-]{43}\n$/,
    );
    assert.equal((await stat(file)).mode & 0o777, 0o600);
    const token = content.split("RELAY_TOKEN=")[1].trim();
    assert.ok(!first.stdout.includes(token));
    assert.ok(!first.stderr.includes(token));
    assert.equal(run("another.example.com").status, 1);
    assert.equal(await readFile(file, "utf8"), content);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
