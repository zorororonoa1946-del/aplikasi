import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";
import {
  mkdtemp,
  readFile,
  writeFile,
  mkdir,
  rm,
  access,
} from "node:fs/promises";
import { createServer as httpsServer } from "node:https";
import { createServer } from "../server.js";
import { chromium } from "playwright";
import assert from "node:assert/strict";
import { resolve } from "node:path";

if (process.env.NUSA_DISPOSABLE_EMULATOR !== "1")
  throw Error(
    "Set NUSA_DISPOSABLE_EMULATOR=1 only for a disposable emulator; these tests remove its application data.",
  );
process.chdir(fileURLToPath(new URL("..", import.meta.url)));
await rm("artifacts/android-test-results.txt", { force: true });
const temporary = await mkdtemp("/tmp/nusa-device-test-");
const generated = resolve("android/app/src/debug");
const token = "test-only-relay-token-123456789";
const password = "pengujian-vault-aman-123";
let secure,
  local,
  browser,
  created = false;
function command(program, args, quiet = false) {
  return new Promise((resolve, reject) => {
    const child = spawn(program, args, { stdio: ["ignore", "pipe", "pipe"] });
    let output = "";
    for (const stream of [child.stdout, child.stderr])
      stream.on("data", (chunk) => {
        output += chunk;
        if (!quiet) process.stdout.write(chunk);
      });
    child.on("error", reject);
    child.on("exit", (code) =>
      code === 0
        ? resolve(output)
        : reject(Error(`${program} exited ${code}\n${output}`)),
    );
  });
}
try {
  // Only disposable debug files are generated here. No custom production trust is changed.
  try {
    await access(generated);
    throw Error(
      "Remove only previously generated test debug resources before running; debug directory already exists",
    );
  } catch (e) {
    if (e.code !== "ENOENT") throw e;
  }
  await command(
    "openssl",
    [
      "req",
      "-x509",
      "-newkey",
      "rsa:2048",
      "-nodes",
      "-keyout",
      `${temporary}/key.pem`,
      "-out",
      `${temporary}/cert.pem`,
      "-days",
      "2",
      "-subj",
      "/CN=localhost",
      "-addext",
      "subjectAltName=DNS:localhost,IP:127.0.0.1",
    ],
    true,
  );
  created = true;
  await mkdir(`${generated}/res/raw`, { recursive: true });
  await mkdir(`${generated}/res/xml`, { recursive: true });
  await writeFile(
    `${generated}/res/raw/test_ca.pem`,
    await readFile(`${temporary}/cert.pem`),
  );
  await writeFile(
    `${generated}/AndroidManifest.xml`,
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:networkSecurityConfig="@xml/test_network_security"/></manifest>',
  );
  await writeFile(
    `${generated}/res/xml/test_network_security.xml`,
    '<network-security-config><base-config cleartextTrafficPermitted="false"><trust-anchors><certificates src="system"/></trust-anchors></base-config><domain-config cleartextTrafficPermitted="false"><domain includeSubdomains="false">localhost</domain><domain includeSubdomains="false">127.0.0.1</domain><trust-anchors><certificates src="@raw/test_ca"/></trust-anchors></domain-config></network-security-config>',
  );
  await command("scripts/android-build.sh", [
    "assembleDebug",
    "assembleAndroidTest",
    "lintDebug",
  ]);
  const options = { token, dataDir: `${temporary}/relay` };
  local = createServer(options);
  const handler = createServer(options).listeners("request")[0],
    interrupts = new Map(),
    counts = new Map();
  secure = httpsServer(
    {
      key: await readFile(`${temporary}/key.pem`),
      cert: await readFile(`${temporary}/cert.pem`),
    },
    (req, res) => {
      const control = req.url.match(
        /^\/test\/(interrupt|resume|count)\/([a-f0-9-]{36})$/,
      );
      if (control) {
        if (req.headers.authorization !== `Bearer ${token}`) {
          res.writeHead(401);
          res.end();
          return;
        }
        if (control[1] === "interrupt") interrupts.set(control[2], 3);
        if (control[1] === "resume") interrupts.delete(control[2]);
        res.end(
          control[1] === "count" ? String(counts.get(control[2]) || 0) : "ok",
        );
        return;
      }
      const part = req.url.match(/^\/api\/chunks\/([a-f0-9-]{36})\/(0|1)$/);
      if (req.method === "PUT" && part) {
        if (part[2] === "0")
          counts.set(part[1], (counts.get(part[1]) || 0) + 1);
        if (part[2] === "1" && interrupts.get(part[1]) > 0) {
          interrupts.set(part[1], interrupts.get(part[1]) - 1);
          req.resume();
          res.writeHead(503);
          res.end("{}");
          return;
        }
      }
      handler(req, res);
    },
  );
  await new Promise((r) => secure.listen(3443, "127.0.0.1", r));
  await new Promise((r) => local.listen(3444, "127.0.0.1", r));
  await command("scripts/adb.sh", ["wait-for-device"]);
  const deadline = Date.now() + 15 * 60 * 1000;
  while (
    (
      await command(
        "scripts/adb.sh",
        ["shell", "getprop", "sys.boot_completed"],
        true,
      )
    ).trim() !== "1"
  ) {
    if (Date.now() > deadline)
      throw Error("Android boot did not complete within 15 minutes");
    await new Promise((r) => setTimeout(r, 2000));
  }
  const api = (
    await command(
      "scripts/adb.sh",
      ["shell", "getprop", "ro.build.version.sdk"],
      true,
    )
  ).trim();
  const serial = await command("scripts/adb.sh", ["get-serialno"], true);
  if (!serial.trim().startsWith("emulator-"))
    throw Error(
      "Use a disposable emulator; physical-device data must not be cleared",
    );
  await command("scripts/adb.sh", ["reverse", "tcp:3443", "tcp:3443"]);
  await command(
    "scripts/adb.sh",
    ["uninstall", "id.nusastudio.vault.test"],
    true,
  ).catch(() => {});
  await command(
    "scripts/adb.sh",
    ["uninstall", "id.nusastudio.vault"],
    true,
  ).catch(() => {});
  await command("scripts/adb.sh", [
    "install",
    "--no-streaming",
    "-r",
    "android/app/build/outputs/apk/debug/app-debug.apk",
  ]);
  await command("scripts/adb.sh", [
    "install",
    "-r",
    "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
  ]);
  // Tests are deliberately restricted to disposable emulators: they clear the test app's vault.
  await command("scripts/adb.sh", [
    "shell",
    "pm",
    "clear",
    "id.nusastudio.vault",
  ]);
  const result = await command("scripts/adb.sh", [
    "shell",
    "am",
    "instrument",
    "-w",
    "-r",
    "-e",
    "relay",
    "https://localhost:3443",
    "id.nusastudio.vault.test/id.nusastudio.vault.VaultInstrumentation",
  ]);
  assert.match(result, /Tests passed: 23/);
  assert.doesNotMatch(result, /FAIL |INSTRUMENTATION_FAILED|Process crashed/);
  // Revocation intentionally kills the app on Android. Do it after the first
  // instrumentation process exits, then verify refusal in a fresh process.
  await command("scripts/adb.sh", [
    "shell",
    ...(Number(api) >= 30
      ? [
          "appops",
          "set",
          "id.nusastudio.vault",
          "MANAGE_EXTERNAL_STORAGE",
          "deny",
        ]
      : [
          "pm",
          "revoke",
          "id.nusastudio.vault",
          "android.permission.READ_EXTERNAL_STORAGE",
        ]),
  ]);
  const revoked = await command("scripts/adb.sh", [
    "shell",
    "am",
    "instrument",
    "-w",
    "-r",
    "-e",
    "suite",
    "storage-revoked",
    "id.nusastudio.vault.test/id.nusastudio.vault.VaultInstrumentation",
  ]);
  assert.match(
    revoked,
    /PASS revoked shared storage permission blocks scanning/,
  );
  assert.doesNotMatch(revoked, /FAIL |INSTRUMENTATION_FAILED|Process crashed/);

  // Independent browser verifies the exact bytes produced/encrypted on Android.
  browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium",
    headless: true,
    args: ["--no-sandbox"],
  });
  const page = await browser.newPage();
  await page.goto("http://127.0.0.1:3444");
  for (let i = 0; i < 7; i++) await page.locator("#brand").click();
  await page.locator("#password").fill(password);
  if (await page.locator("#confirm-password").isVisible())
    await page.locator("#confirm-password").fill(password);
  await page.locator("#unlock-form button[type=submit]").click();
  await page.locator("#vault").waitFor({ state: "visible" });
  await page.locator("#token").fill(token);
  await page.locator("#restore").click();
  await page.getByText("android-test.txt", { exact: true }).waitFor();
  const pending = page.waitForEvent("download");
  await page
    .locator("article.file")
    .filter({ has: page.getByText("android-test.txt", { exact: true }) })
    .getByRole("button", { name: "Unduh", exact: true })
    .click();
  const download = await pending;
  const stream = await download.createReadStream();
  const chunks = [];
  for await (const chunk of stream) chunks.push(chunk);
  assert.equal(Buffer.concat(chunks).toString(), "rahasia dari Android 🔒");
  await mkdir("artifacts", { recursive: true });
  await writeFile(
    "artifacts/android-test-results.txt",
    `${new Date().toISOString()}\nAndroid API ${api}\n${result}\n${revoked}\nPASS Android -> HTTPS relay -> independent laptop browser, exact restored bytes\n`,
  );
  console.log(
    "PASS Android -> HTTPS relay -> laptop browser: exact restored bytes",
  );
} finally {
  if (browser) await browser.close();
  if (secure) await new Promise((r) => secure.close(r));
  if (local) await new Promise((r) => local.close(r));
  // The directory was created exclusively by this test runner.
  if (secure)
    await command(
      "scripts/adb.sh",
      ["reverse", "--remove", "tcp:3443"],
      true,
    ).catch(() => {});
  if (created) await rm(generated, { recursive: true });
  await rm(temporary, { recursive: true, force: true });
}
