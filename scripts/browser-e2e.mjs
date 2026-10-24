import { chromium } from "playwright";
import { createServer } from "../server.js";
import { mkdtemp, rm, mkdir } from "node:fs/promises";
import assert from "node:assert/strict";
import { encrypt } from "../public/format.js";
import { createHash } from "node:crypto";
await mkdir(new URL("../artifacts/", import.meta.url), { recursive: true });
const dir = await mkdtemp("/tmp/vault-e2e-");
const token = "integration-test-relay-token-123456";
const server = createServer({ token, dataDir: dir });
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const url = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch({
  executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium",
  headless: true,
  args: ["--no-sandbox"],
});
try {
  const phone = await browser.newContext({
    viewport: { width: 390, height: 844 },
  });
  const page = await phone.newPage();
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(url);
  await page.screenshot({
    path: new URL("../artifacts/company.png", import.meta.url).pathname,
    fullPage: true,
  });
  async function unlock(p, pass) {
    for (let i = 0; i < 7; i++) await p.locator("#brand").click();
    await p.locator("#password").fill(pass);
    if (await p.locator("#confirm-password").isVisible())
      await p.locator("#confirm-password").fill(pass);
    await p.locator("#unlock-form button[type=submit]").click();
    await p.locator("#vault").waitFor({ state: "visible" });
  }
  const pass = "a-long-test-vault-password";
  await unlock(page, pass);
  await page
    .locator("#import")
    .setInputFiles({
      name: "pribadi.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("rahasia untuk pengujian"),
    });
  await page.getByText("pribadi.txt", { exact: true }).waitFor();
  await page.locator("#token").fill(token);
  await page.locator("#backup").click();
  await page
    .getByText("1 file berhasil di-backup.", { exact: true })
    .waitFor()
    .catch(async (error) => {
      throw Error(
        (await page.locator("#status").textContent()) + " " + error.message,
      );
    });
  await page.screenshot({
    path: new URL("../artifacts/vault.png", import.meta.url).pathname,
    fullPage: true,
  });
  // Exercise multiple authenticated chunks, plus existing v1 backups from the original release.
  const large = Buffer.alloc(25 * 1024 * 1024 + 17);
  for (let i = 0; i < large.length; i++) large[i] = i % 251;
  await page
    .locator("#import")
    .setInputFiles({
      name: "large-browser.bin",
      mimeType: "application/octet-stream",
      buffer: large,
    });
  await page.getByText("large-browser.bin", { exact: true }).waitFor();
  await page.locator("#backup").click();
  await page.getByText("2 file berhasil di-backup.", { exact: true }).waitFor();
  const legacyId = crypto.randomUUID(),
    legacy = await encrypt(
      {
        name: "lama/legacy.txt",
        size: 6,
        date: new Date().toISOString(),
        data: Buffer.from("legacy").toString("base64"),
      },
      pass,
    );
  assert.equal(
    (
      await fetch(`${url}/api/files/${legacyId}`, {
        method: "PUT",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(legacy),
      })
    ).ok,
    true,
  );
  const laptop = await browser.newContext();
  const second = await laptop.newPage();
  await second.goto(url);
  await unlock(second, pass);
  await second.locator("#token").fill(token);
  await second.locator("#restore").click();
  await second.getByText("pribadi.txt", { exact: true }).waitFor();
  const downloadPromise = second.waitForEvent("download");
  await second
    .locator("article.file")
    .filter({ has: second.getByText("pribadi.txt", { exact: true }) })
    .getByRole("button", { name: "Unduh", exact: true })
    .click();
  const download = await downloadPromise;
  assert.equal(download.suggestedFilename(), "pribadi.txt");
  const stream = await download.createReadStream();
  const chunks = [];
  for await (const chunk of stream) chunks.push(chunk);
  assert.equal(Buffer.concat(chunks).toString(), "rahasia untuk pengujian");
  await second.getByText("large-browser.bin", { exact: true }).waitFor();
  const largePending = second.waitForEvent("download");
  await second
    .locator("article.file")
    .filter({ has: second.getByText("large-browser.bin", { exact: true }) })
    .getByRole("button", { name: "Unduh", exact: true })
    .click();
  const largeDownload = await largePending,
    largeStream = await largeDownload.createReadStream(),
    hash = createHash("sha256");
  for await (const bytes of largeStream) hash.update(bytes);
  assert.equal(
    hash.digest("hex"),
    createHash("sha256").update(large).digest("hex"),
  );
  await second.getByRole("button", { name: "▸ lama", exact: true }).click();
  await second.getByText("lama/legacy.txt", { exact: true }).waitFor();
  await second
    .getByRole("button", { name: "↑ Folder induk", exact: true })
    .click();
  // Damage ciphertext in IndexedDB. Restore must repair it before a later download succeeds.
  await second.evaluate(async () => {
    const db = await new Promise((r) => {
      const q = indexedDB.open("nusa-vault", 2);
      q.onsuccess = () => r(q.result);
    });
    await new Promise((resolve, reject) => {
      const t = db.transaction("chunks", "readwrite"),
        s = t.objectStore("chunks"),
        q = s.openCursor();
      q.onsuccess = () => {
        const c = q.result;
        if (c) {
          const data = new Uint8Array(c.value);
          data[0] ^= 1;
          c.update(data);
        }
      };
      t.oncomplete = resolve;
      t.onerror = reject;
    });
    db.close();
  });
  await second.locator("#restore").click();
  await second
    .getByText("1 file dipulihkan ke perangkat ini.", { exact: true })
    .waitFor();
  await second.locator("#lock").click();
  for (let i = 0; i < 7; i++) await second.locator("#brand").click();
  await second.locator("#password").fill(pass);
  await second.locator("#unlock-form button[type=submit]").click();
  await second.keyboard.press("Escape");
  await second.waitForTimeout(1000);
  assert.equal(
    await second.locator("#vault").isVisible(),
    false,
    "Canceled unlock unexpectedly reopened the vault",
  );
  for (let i = 0; i < 7; i++) await second.locator("#brand").click();
  await second.locator("#password").fill("wrong-password-123");
  await second.locator("#unlock-form button[type=submit]").click();
  await second
    .getByText("Vault tidak dapat dibuka. Periksa kata sandi atau data lokal.")
    .waitFor();
  assert.deepEqual(errors, []);
  console.log(
    "PASS: mobile import, 25 MiB chunk backup/restore with exact SHA-256, v1 compatibility, folder browsing, corrupt local chunk repair, lock, unlock cancellation and wrong-password rejection; no browser errors",
  );
} finally {
  await browser.close();
  await new Promise((r) => server.close(r));
  await rm(dir, { recursive: true, force: true });
}
