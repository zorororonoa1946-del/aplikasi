import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";
import { readFile, writeFile, mkdtemp, rm } from "node:fs/promises";
import assert from "node:assert/strict";
import { resolve } from "node:path";
if (process.env.NUSA_DISPOSABLE_EMULATOR !== "1")
  throw Error("Set NUSA_DISPOSABLE_EMULATOR=1 only for a disposable emulator.");
process.chdir(fileURLToPath(new URL("..", import.meta.url)));
await rm("artifacts/compatibility-test-results.txt", { force: true });
const { version } = JSON.parse(await readFile("package.json", "utf8"));
if (!/^\d+\.\d+\.\d+$/.test(version)) throw Error("Invalid package version");
function run(program, args, env = process.env) {
  return new Promise((resolve, reject) => {
    const child = spawn(program, args, {
      env,
      stdio: ["ignore", "pipe", "pipe"],
    });
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
const adb = (args) => run("scripts/adb.sh", args);
assert.ok(
  (await adb(["get-serialno"])).trim().startsWith("emulator-"),
  "Disposable emulator required; test clears app data",
);
const api = Number(
  (await adb(["shell", "getprop", "ro.build.version.sdk"])).trim(),
);
assert.ok(api >= 26);
await run("scripts/android-build.sh", ["assembleAndroidTest"]);
const sdk = process.env.ANDROID_HOME || "/workspace/toolchains/android-sdk",
  temporary = await mkdtemp("/tmp/nusa-release-compat-");
const signing = process.env.NUSA_SIGNING_DIR || resolve(".local/signing");
try {
  const env = {
    ...process.env,
    NUSA_TEST_SIGNING_PASSWORD: (
      await readFile(`${signing}/password`, "utf8")
    ).trim(),
  };
  await run(
    `${sdk}/build-tools/35.0.0/apksigner`,
    [
      "sign",
      "--ks",
      `${signing}/release.p12`,
      "--ks-key-alias",
      "nusa",
      "--ks-pass",
      "env:NUSA_TEST_SIGNING_PASSWORD",
      "--key-pass",
      "env:NUSA_TEST_SIGNING_PASSWORD",
      "--out",
      `${temporary}/tests.apk`,
      "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    ],
    env,
  );
  delete env.NUSA_TEST_SIGNING_PASSWORD;
  await adb([
    "install",
    "--no-streaming",
    "-r",
    `artifacts/nusa-vault-${version}.apk`,
  ]);
  await adb([
    "install",
    "--no-streaming",
    "-t",
    "-r",
    `${temporary}/tests.apk`,
  ]);
  await adb(["shell", "pm", "clear", "id.nusastudio.vault"]);
  const result = await adb([
    "shell",
    "am",
    "instrument",
    "-w",
    "-r",
    "-e",
    "suite",
    "crypto",
    "id.nusastudio.vault.test/id.nusastudio.vault.VaultInstrumentation",
  ]);
  assert.match(result, /Compatibility tests passed: 5/);
  assert.doesNotMatch(result, /FAIL |Process crashed|INSTRUMENTATION_FAILED/);
  // Real signed upgrade: seed v1 data using the original release, then install-r
  // without clearing data and verify it with the current production release.
  await adb(["uninstall", "id.nusastudio.vault.test"]);
  await adb(["uninstall", "id.nusastudio.vault"]);
  await adb([
    "install",
    "--no-streaming",
    process.env.NUSA_PREVIOUS_APK || "artifacts/nusa-vault-1.0.0.apk",
  ]);
  await adb(["install", "--no-streaming", "-t", `${temporary}/tests.apk`]);
  const seed = await adb([
    "shell",
    "am",
    "instrument",
    "-w",
    "-r",
    "-e",
    "suite",
    "migration-seed",
    "id.nusastudio.vault.test/id.nusastudio.vault.VaultInstrumentation",
  ]);
  assert.match(seed, /PASS migration seed/);
  assert.doesNotMatch(seed, /FAIL |Process crashed|INSTRUMENTATION_FAILED/);
  await adb([
    "install",
    "--no-streaming",
    "-r",
    `artifacts/nusa-vault-${version}.apk`,
  ]);
  const migration = await adb([
    "shell",
    "am",
    "instrument",
    "-w",
    "-r",
    "-e",
    "suite",
    "migration-verify",
    "id.nusastudio.vault.test/id.nusastudio.vault.VaultInstrumentation",
  ]);
  assert.match(migration, /PASS signed 1.0.0 to current release upgrade/);
  assert.doesNotMatch(
    migration,
    /FAIL |Process crashed|INSTRUMENTATION_FAILED/,
  );
  await writeFile(
    "artifacts/compatibility-test-results.txt",
    `${new Date().toISOString()}\nProduction signed APK, Android API ${api} x86_64 emulator. Matching release certificate; no custom TLS trust.\n${result}\n${seed}\n${migration}`,
  );
  await adb(["uninstall", "id.nusastudio.vault.test"]);
  await adb(["shell", "pm", "clear", "id.nusastudio.vault"]);
  console.log(
    `PASS production release on API ${api}: AES-GCM, wrong-password/tamper rejection, Web Crypto compatibility, encrypted persistence/deduplication/restart, Android Keystore; signed upgrade preserves v1 data`,
  );
} finally {
  await rm(temporary, { recursive: true, force: true });
}
