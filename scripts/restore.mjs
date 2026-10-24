import https from "node:https";
import { createHash, randomUUID } from "node:crypto";
import { mkdir, readFile, open, link, unlink, lstat } from "node:fs/promises";
import { createReadStream } from "node:fs";
import { resolve, join, dirname, basename, extname } from "node:path";
import { parseEnv } from "node:util";
import readline from "node:readline";
import {
  decrypt,
  validate,
  openChunk,
  ID,
  unbase64,
} from "../public/format.js";
const args = process.argv.slice(2),
  option = (name) => {
    const i = args.indexOf(name);
    return i >= 0 ? args[i + 1] : undefined;
  };
let url = option("--url"),
  token = process.env.RELAY_TOKEN,
  ca;
const connection = option("--connection");
if (connection) {
  const text = await readFile(connection, "utf8");
  url ||= text.match(/^Relay: (.+)$/m)?.[1];
  token ||= text.match(/^Token: (.+)$/m)?.[1];
}
if (option("--env-file")) {
  const env = parseEnv(await readFile(option("--env-file"), "utf8"));
  token ||= env.RELAY_TOKEN;
  url ||= env.VAULT_DOMAIN ? `https://${env.VAULT_DOMAIN}` : undefined;
}
if (option("--ca")) ca = await readFile(option("--ca"));
if (!url || !token)
  throw Error(
    "Gunakan --url https://relay --out folder dan RELAY_TOKEN, --env-file deployment/.env, atau --connection connection.txt. Untuk LAN tambahkan --ca ca.pem.",
  );
const origin = new URL(url);
if (
  origin.protocol !== "https:" ||
  origin.username ||
  origin.password ||
  origin.pathname !== "/" ||
  origin.search ||
  origin.hash
)
  throw Error("Relay harus origin HTTPS tanpa path atau kredensial");
if (token.length < 24 || /[\r\n]/.test(token))
  throw Error("Token relay tidak valid");
function request(path) {
  return new Promise((resolve, reject) => {
    const req = https.get(
      new URL(`/api${path}`, origin),
      { ca, headers: { Authorization: `Bearer ${token}` }, timeout: 60000 },
      (res) => {
        if (res.statusCode !== 200) {
          res.resume();
          reject(Error(`Relay HTTP ${res.statusCode}`));
          return;
        }
        let size = 0;
        const chunks = [];
        res.on("data", (data) => {
          size += data.length;
          if (size > 40 * 1024 * 1024) {
            res.destroy(Error("Respons terlalu besar"));
            return;
          }
          chunks.push(data);
        });
        res.on("end", () => resolve(Buffer.concat(chunks)));
        res.on("error", reject);
      },
    );
    req.on("timeout", () => req.destroy(Error("Relay timeout")));
    req.on("error", reject);
  });
}
async function password() {
  if (!process.stdin.isTTY) {
    let text = "";
    for await (const chunk of process.stdin) {
      text += chunk;
      if (text.length > 4096) throw Error("Kata sandi terlalu panjang");
    }
    return text.replace(/\r?\n$/, "");
  }
  return new Promise((resolve, reject) => {
    let value = "";
    process.stdout.write("Kata sandi vault (tidak ditampilkan): ");
    readline.emitKeypressEvents(process.stdin);
    process.stdin.setRawMode(true);
    process.stdin.resume();
    const stop = () => {
      process.stdin.removeListener("keypress", onKey);
      process.stdin.setRawMode(false);
      process.stdin.pause();
      process.stdout.write("\n");
    };
    const onKey = (text, key) => {
      if (key?.ctrl && key.name === "c") {
        stop();
        reject(Error("Dibatalkan"));
      } else if (key?.name === "return") {
        stop();
        resolve(value);
      } else if (key?.name === "backspace")
        value = [...value].slice(0, -1).join("");
      else if (text && !key?.ctrl) value += text;
    };
    process.stdin.on("keypress", onKey);
  });
}
const pass = await password();
if (pass.length < 12) throw Error("Kata sandi minimal 12 karakter");
const output = resolve(option("--out") || "restored-vault");
await mkdir(output, { recursive: true, mode: 0o700 });
if ((await lstat(output)).isSymbolicLink())
  throw Error("Folder tujuan tidak boleh symlink");
async function destination(name) {
  const components = name.split("/");
  if (
    !components.length ||
    components.some(
      (p) =>
        !p ||
        p === "." ||
        p === ".." ||
        p.includes("\\") ||
        p.includes(":") ||
        p.includes("\0"),
    )
  )
    throw Error("Nama/path backup tidak aman");
  let current = output;
  for (const part of components.slice(0, -1)) {
    current = join(current, part);
    await mkdir(current, { mode: 0o700 }).catch((error) => {
      if (error.code !== "EEXIST") throw error;
    });
    const info = await lstat(current);
    if (!info.isDirectory() || info.isSymbolicLink())
      throw Error("Komponen folder tujuan tidak aman");
  }
  return join(current, components.at(-1));
}
async function hashFile(path) {
  const hash = createHash("sha256");
  for await (const data of createReadStream(path)) hash.update(data);
  return hash.digest("base64");
}
const ids = JSON.parse((await request("/files")).toString());
if (!Array.isArray(ids)) throw Error("Daftar relay tidak valid");
let restored = 0,
  failed = 0;
for (const id of ids) {
  let temporary;
  try {
    if (!ID.test(id)) throw Error("ID relay tidak valid");
    const envelope = JSON.parse((await request(`/files/${id}`)).toString()),
      value = validate(await decrypt(envelope, pass), id);
    let target = await destination(value.name);
    temporary = join(dirname(target), `.nusa-restore-${randomUUID()}`);
    const handle = await open(temporary, "wx", 0o600),
      hash = createHash("sha256");
    let total = 0;
    try {
      if (value.format === 2) {
        for (let i = 0; i < value.parts.length; i++) {
          const plaintext = await openChunk(
            value,
            id,
            i,
            await request(`/chunks/${id}/${i}`),
          );
          try {
            await handle.writeFile(plaintext);
            hash.update(plaintext);
            total += plaintext.length;
          } finally {
            plaintext.fill(0);
          }
        }
      } else {
        const plaintext = unbase64(value.data);
        await handle.writeFile(plaintext);
        hash.update(plaintext);
        total = plaintext.length;
        plaintext.fill(0);
      }
      const checksum = hash.digest("base64");
      if (total !== value.size || (value.hash && checksum !== value.hash))
        throw Error("Ukuran/checksum hasil pemulihan berbeda");
      await handle.sync();
      await handle.close();
      try {
        await link(temporary, target);
      } catch (error) {
        if (error.code !== "EEXIST") throw error;
        const info = await lstat(target);
        if (info.isSymbolicLink() || !info.isFile())
          throw Error("Tujuan yang sudah ada tidak aman");
        if ((await hashFile(target)) !== checksum) {
          const extension = extname(target);
          target = join(
            dirname(target),
            `${basename(target, extension)}.${id}${extension}`,
          );
          try {
            await link(temporary, target);
          } catch (conflict) {
            if (
              conflict.code !== "EEXIST" ||
              (await lstat(target)).isSymbolicLink() ||
              (await hashFile(target)) !== checksum
            )
              throw Error("Konflik versi; file tujuan lama dipertahankan");
          }
        }
      }
      restored++;
      console.log(
        `Pulih terverifikasi: ${JSON.stringify(value.name)} (${total} byte)`,
      );
    } finally {
      await handle.close().catch(() => {});
    }
  } catch (error) {
    failed++;
    console.error(
      `Gagal memulihkan ${String(id).slice(0, 8)}: ${error.message}`,
    );
  } finally {
    if (temporary) await unlink(temporary).catch(() => {});
  }
}
console.log(
  `${restored} file dipulihkan/terverifikasi, ${failed} gagal. File lama tidak ditimpa.`,
);
if (failed) process.exitCode = 1;
