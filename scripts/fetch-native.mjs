import { fileURLToPath } from "node:url";
import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile, rename, access } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
const revision = "8f77af2e983b73df4f018e2a141450d005f5587b";
const sha256 =
  "af937f7eaa814083130d5f0881f5ddad9c7b8a2f024e19d3431941f5cb75442e";
process.chdir(fileURLToPath(new URL("..", import.meta.url)));
const root = resolve(".local"),
  source = resolve(".local/boringssl"),
  archive = resolve(`.local/boringssl-${revision}.tar.gz`);
await mkdir(root, { recursive: true });
let cached = false;
try {
  cached =
    (await readFile(`${source}/.source-sha256`, "utf8")).trim() === sha256;
  await access(`${source}/CMakeLists.txt`);
} catch {}
if (cached) process.exit(0);
try {
  await access(archive);
} catch {
  const result = spawnSync(
    "curl",
    [
      "--fail",
      "--location",
      "--retry",
      "2",
      "--proto",
      "=https",
      "--tlsv1.2",
      `https://codeload.github.com/google/boringssl/tar.gz/${revision}`,
      "--output",
      `${archive}.partial`,
    ],
    { stdio: "inherit" },
  );
  if (result.status !== 0) process.exit(result.status || 1);
  await rename(`${archive}.partial`, archive);
}
if (
  createHash("sha256")
    .update(await readFile(archive))
    .digest("hex") !== sha256
)
  throw Error("BoringSSL checksum mismatch; refusing to use source");
await mkdir(source, { recursive: true });
const extracted = spawnSync(
  "tar",
  ["-xzf", archive, "-C", source, "--strip-components=1"],
  { stdio: "inherit" },
);
if (extracted.status !== 0) process.exit(extracted.status || 1);
await writeFile(`${source}/.source-sha256`, sha256 + "\n");
console.log(`Verified BoringSSL source ${revision}`);
