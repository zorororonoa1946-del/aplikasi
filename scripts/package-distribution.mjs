import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";
import { writeFile, readFile, access } from "node:fs/promises";
import { createHash } from "node:crypto";
process.chdir(fileURLToPath(new URL("..", import.meta.url)));
const { version } = JSON.parse(await readFile("package.json", "utf8"));
if (!/^\d+\.\d+\.\d+$/.test(version)) throw Error("Invalid package version");
const apk = `artifacts/nusa-vault-${version}.apk`;
await access(apk);
await access("artifacts/android-test-results.txt");
await access("artifacts/container-test-results.txt");
const source = execFileSync("git", [
  "ls-files",
  "--cached",
  "--others",
  "--exclude-standard",
  "-z",
])
  .toString()
  .split("\0")
  .filter(Boolean)
  .filter((p) => !p.startsWith("artifacts/"));
const files = [
  ...source,
  apk,
  "artifacts/nusa-vault-1.0.0.apk",
  "artifacts/nusa-vault-1.0.0.apk.sha256",
  `${apk}.sha256`,
  "artifacts/apk-signature.txt",
  "artifacts/siservis-home.png",
  "artifacts/android-test-results.txt",
  "artifacts/container-test-results.txt",
  "artifacts/local-container-test-results.txt",
  "artifacts/backend-test-results.txt",
  "artifacts/release-smoke-results.txt",
  "artifacts/compatibility-test-results.txt",
  "artifacts/web-test-results.txt",
];
for (const path of files) {
  if (
    path.startsWith("/") ||
    path.includes("..") ||
    path.includes(".local/") ||
    path.endsWith(".env") ||
    path.endsWith(".p12") ||
    path.endsWith(".jks")
  )
    throw Error("Unsafe distribution entry");
  await access(path);
}
const manifest = { created: new Date().toISOString(), files: [] };
for (const path of files)
  manifest.files.push({
    path,
    sha256: createHash("sha256")
      .update(await readFile(path))
      .digest("hex"),
  });
await writeFile(
  "artifacts/distribution-manifest.json",
  JSON.stringify(manifest, null, 2) + "\n",
);
files.push("artifacts/distribution-manifest.json");
// Python's standard zip writer avoids an extra runtime dependency and preserves exact file paths.
const python = `import sys,zipfile,json,os,stat\npaths=json.load(sys.stdin)\nwith zipfile.ZipFile('artifacts/nusa-vault-installation-${version}.zip','w',zipfile.ZIP_DEFLATED) as z:\n for p in paths:\n  if not stat.S_ISREG(os.lstat(p).st_mode): raise RuntimeError('Not a regular file: '+p)\n  z.write(p,p)\n`;
execFileSync("python3", ["-c", python], { input: JSON.stringify(files) });
const zip = `artifacts/nusa-vault-installation-${version}.zip`;
const hash = createHash("sha256")
  .update(await readFile(zip))
  .digest("hex");
await writeFile(`${zip}.sha256`, `${hash}  ${zip}\n`);
console.log(
  `Created ${zip} with ${files.length} files; signing keys, tokens, data, caches, and test TLS certificates excluded.`,
);
