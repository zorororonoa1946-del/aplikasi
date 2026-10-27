import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";
import { readFile, writeFile, rm } from "node:fs/promises";
import { createHash } from "node:crypto";
import assert from "node:assert/strict";
if (process.env.NUSA_DISPOSABLE_EMULATOR !== "1")
  throw Error("Set NUSA_DISPOSABLE_EMULATOR=1 only for a disposable emulator.");
process.chdir(fileURLToPath(new URL("..", import.meta.url)));
await rm("artifacts/release-smoke-results.txt", { force: true });
const { version } = JSON.parse(await readFile("package.json", "utf8"));
if (!/^\d+\.\d+\.\d+$/.test(version)) throw Error("Invalid package version");
function command(program, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(program, args, { stdio: ["ignore", "pipe", "pipe"] });
    let output = "";
    for (const stream of [child.stdout, child.stderr])
      stream.on("data", (chunk) => (output += chunk));
    child.on("error", reject);
    child.on("exit", (code) =>
      code === 0
        ? resolve(output)
        : reject(Error(`${program} failed (${code}): ${output}`)),
    );
  });
}
const adb = (args) => command("scripts/adb.sh", args);
await adb(["wait-for-device"]);
const bootDeadline = Date.now() + 15 * 60 * 1000;
while ((await adb(["shell", "getprop", "sys.boot_completed"])).trim() !== "1") {
  if (Date.now() > bootDeadline) throw Error("Emulator did not finish booting");
  await new Promise((r) => setTimeout(r, 2000));
}
const serial = (await adb(["get-serialno"])).trim();
assert.ok(
  serial.startsWith("emulator-"),
  "Use a disposable emulator; this smoke test replaces the test application",
);
const api = Number(
  (await adb(["shell", "getprop", "ro.build.version.sdk"])).trim(),
);
assert.ok(api >= 26);
const apk = `artifacts/nusa-vault-${version}.apk`;
const expected = (await readFile(`${apk}.sha256`, "utf8")).split(/\s/)[0];
assert.equal(
  createHash("sha256")
    .update(await readFile(apk))
    .digest("hex"),
  expected,
);
const sdk = process.env.ANDROID_HOME || "/workspace/toolchains/android-sdk";
const signature = await command(`${sdk}/build-tools/35.0.0/apksigner`, [
  "verify",
  "--verbose",
  apk,
]);
assert.match(signature, /Verified using v2 scheme .*true/);
await adb(["uninstall", "id.nusastudio.vault.test"]).catch(() => {});
await adb(["uninstall", "id.nusastudio.vault"]).catch(() => {});
const installation = await adb(["install", "--no-streaming", apk]);
assert.match(installation, /Success/);
const launch = await adb([
  "shell",
  "am",
  "start",
  "-W",
  "-n",
  "id.nusastudio.vault/.MainActivity",
]);
assert.match(launch, /Status: ok/);
let xml = "";
let recoveredSystemUi = false;
for (let attempt = 0; attempt < 4; attempt++) {
  await adb([
    "shell",
    "uiautomator",
    "dump",
    "/data/local/tmp/nusa-release-ui.xml",
  ]);
  xml = await adb(["shell", "cat", "/data/local/tmp/nusa-release-ui.xml"]);
  if (xml.includes("System UI isn't responding")) {
    // This software-only emulator can leave a System UI ANR overlay. Never dismiss an app ANR.
    const button = xml.match(
      /<node[^>]*resource-id="android:id\/aerr_close"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/,
    );
    assert.ok(button, "Cannot recover emulator System UI overlay");
    const x = Math.floor((Number(button[1]) + Number(button[3])) / 2),
      y = Math.floor((Number(button[2]) + Number(button[4])) / 2);
    await adb(["shell", "input", "tap", String(x), String(y)]);
    recoveredSystemUi = true;
    await new Promise((r) => setTimeout(r, 1000));
    continue;
  }
  break;
}
assert.ok(
  xml.includes("SiServis-PC") && xml.includes("DASHBOARD SERVIS"),
  "SiServis-PC dashboard did not render",
);
assert.ok(
  !xml.includes("Personal vault."),
  "Vault unexpectedly visible without authentication",
);
await adb(["shell", "rm", "/data/local/tmp/nusa-release-ui.xml"]);
await writeFile(
  "artifacts/release-smoke-results.txt",
  `${new Date().toISOString()}\nPASS release APK SHA-256 and v2 signature verification\nPASS installation on Android API ${api} x86_64 emulator\nPASS launch and rendered SiServis-PC dashboard\nPASS vault remains locked without authentication\n${launch}\nEmulator System UI recovery: ${recoveredSystemUi}\n`,
);
console.log(
  "PASS signed release APK: checksum, signature, installation, launch and locked SiServis-PC dashboard",
);
