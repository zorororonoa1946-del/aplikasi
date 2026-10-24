import http from "node:http";
import https from "node:https";
import {
  readFile,
  mkdir,
  open,
  link,
  readdir,
  unlink,
  stat,
} from "node:fs/promises";
import { resolve, join } from "node:path";
import { timingSafeEqual, createHash, randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
const root = fileURLToPath(new URL("./public/", import.meta.url));
const uuid = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
const filePattern = new RegExp(`^/api/files/(${uuid})$`);
const chunkPattern = new RegExp(`^/api/chunks/(${uuid})/(0|[1-9][0-9]{0,8})$`);
const fileName = new RegExp(`^${uuid}\\.json$`);
const digest = (value) => createHash("sha256").update(value).digest();
const canonical = (value) =>
  typeof value === "string" &&
  Buffer.from(value, "base64").toString("base64") === value;
async function storageSize(directory) {
  let total = 0;
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (entry.name.startsWith(".upload-")) continue;
    const path = join(directory, entry.name);
    if (entry.isDirectory()) total += await storageSize(path);
    else if (entry.isFile()) total += (await stat(path)).size;
  }
  return total;
}
export function createServer({
  token = process.env.RELAY_TOKEN,
  dataDir = process.env.DATA_DIR || ".vault-data",
  maxBytes = 40 * 1024 * 1024,
  maxStorageBytes = Number(process.env.MAX_STORAGE_BYTES || 10 * 1024 ** 3),
  tls,
} = {}) {
  if (!Number.isSafeInteger(maxStorageBytes) || maxStorageBytes <= 0)
    throw Error("MAX_STORAGE_BYTES harus bilangan positif");
  const directory = resolve(dataDir),
    failures = new Map();
  let initialize,
    used = 0,
    active = 0,
    queue = Promise.resolve();
  const ready = () =>
    (initialize ??= (async () => {
      await mkdir(directory, { recursive: true, mode: 0o700 });
      used = await storageSize(directory);
    })());
  const mutate = (task) => {
    const next = queue.then(task);
    queue = next.catch(() => {});
    return next;
  };
  const makeServer = tls
    ? (handler) => https.createServer(tls, handler)
    : (handler) => http.createServer(handler);
  const server = makeServer(async (req, res) => {
    for (const [name, value] of Object.entries({
      "X-Content-Type-Options": "nosniff",
      "Referrer-Policy": "no-referrer",
      "Cache-Control": "no-store",
      "Content-Security-Policy":
        "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' blob:; object-src 'none'; frame-ancestors 'none'; base-uri 'none'",
    }))
      res.setHeader(name, value);
    const reply = (code, body) => {
      if (res.destroyed || res.writableEnded) return;
      res.writeHead(code, { "Content-Type": "application/json" });
      res.end(JSON.stringify(body));
    };
    try {
      const path = new URL(req.url, "http://localhost").pathname;
      if (!path.startsWith("/api/")) {
        const assets = new Map([
          ["/", "index.html"],
          ["/app.js", "app.js"],
          ["/format.js", "format.js"],
          ["/style.css", "style.css"],
        ]);
        if (req.method !== "GET")
          return reply(405, { error: "Metode tidak didukung" });
        if (!assets.has(path)) return reply(404, { error: "Tidak ditemukan" });
        const body = await readFile(join(root, assets.get(path)));
        res.writeHead(200, {
          "Content-Type": `${path.endsWith(".js") ? "text/javascript" : path.endsWith(".css") ? "text/css" : "text/html"}; charset=utf-8`,
        });
        return res.end(body);
      }
      if (!token || token.length < 24)
        return reply(503, {
          error:
            "Relay belum dikonfigurasi. Atur RELAY_TOKEN minimal 24 karakter.",
        });
      if (
        !timingSafeEqual(
          digest(req.headers.authorization || ""),
          digest(`Bearer ${token}`),
        )
      ) {
        const ip = req.socket.remoteAddress || "unknown",
          now = Date.now(),
          old = failures.get(ip);
        const item =
          old && now - old.start < 60000 ? old : { start: now, count: 0 };
        item.count++;
        if (failures.size >= 1024 && !failures.has(ip))
          failures.delete(failures.keys().next().value);
        failures.set(ip, item);
        if (item.count > 30) {
          res.setHeader("Retry-After", "60");
          return reply(429, {
            error: "Terlalu banyak autentikasi gagal; coba lagi sebentar.",
          });
        }
        return reply(401, { error: "Kredensial relay salah." });
      }
      await ready();
      if (path === "/api/files" && req.method === "GET")
        return reply(
          200,
          (await readdir(directory))
            .filter((name) => fileName.test(name))
            .map((name) => name.slice(0, -5))
            .sort(),
        );
      const fileMatch = path.match(filePattern),
        chunkMatch = path.match(chunkPattern),
        binary = !!chunkMatch;
      if (!fileMatch && !chunkMatch)
        return reply(404, { error: "Tidak ditemukan" });
      const parent = binary
        ? join(directory, "chunks", chunkMatch[1])
        : directory;
      const destination = binary
        ? join(parent, `${chunkMatch[2]}.bin`)
        : join(parent, `${fileMatch[1]}.json`);
      if (req.method === "GET" || req.method === "HEAD") {
        const data = await readFile(destination);
        res.setHeader(
          "Content-Type",
          binary ? "application/octet-stream" : "application/json",
        );
        res.setHeader("Content-Length", data.length);
        res.setHeader("X-Content-SHA256", digest(data).toString("base64"));
        res.setHeader("ETag", `"${digest(data).toString("hex")}"`);
        res.writeHead(200);
        return res.end(req.method === "HEAD" ? undefined : data);
      }
      if (req.method === "DELETE" && !binary) {
        await mutate(async () => {
          const size = (await stat(destination)).size;
          await unlink(destination);
          used -= size;
        });
        return reply(200, { ok: true });
      }
      if (req.method !== "PUT")
        return reply(405, { error: "Metode tidak didukung" });
      if (active >= 8) {
        res.setHeader("Retry-After", "2");
        return reply(429, { error: "Unggahan sedang penuh; coba lagi." });
      }
      active++;
      let temporary, handle;
      try {
        await mkdir(parent, { recursive: true, mode: 0o700 });
        temporary = join(parent, `.upload-${randomUUID()}`);
        handle = await open(temporary, "wx", 0o600);
        let size = 0;
        const limit = binary
          ? Math.min(maxBytes, 4 * 1024 * 1024 + 16)
          : maxBytes;
        const parts = [];
        for await (const chunk of req) {
          size += chunk.length;
          if (size > limit)
            return reply(413, { error: "Batas unggahan terlampaui" });
          if (binary) await handle.writeFile(chunk);
          else parts.push(chunk);
        }
        if (binary) {
          if (size < 16)
            return reply(400, { error: "Chunk terenkripsi terlalu pendek" });
        } else {
          let envelope;
          try {
            envelope = JSON.parse(Buffer.concat(parts).toString());
          } catch {
            return reply(400, { error: "JSON tidak valid" });
          }
          if (
            !envelope ||
            envelope.version !== 1 ||
            !["salt", "iv", "ciphertext"].every((key) =>
              canonical(envelope[key]),
            ) ||
            Buffer.from(envelope.salt, "base64").length !== 16 ||
            Buffer.from(envelope.iv, "base64").length !== 12 ||
            Buffer.from(envelope.ciphertext, "base64").length < 16
          )
            return reply(400, { error: "Format enkripsi tidak valid" });
          const encoded = JSON.stringify({
            version: 1,
            salt: envelope.salt,
            iv: envelope.iv,
            ciphertext: envelope.ciphertext,
          });
          size = Buffer.byteLength(encoded);
          await handle.writeFile(encoded);
        }
        await handle.sync();
        await handle.close();
        handle = null;
        const result = await mutate(async () => {
          try {
            await stat(destination);
            return 409;
          } catch (e) {
            if (e.code !== "ENOENT") throw e;
          }
          if (used + size > maxStorageBytes) return 507;
          await link(temporary, destination);
          used += size;
          const folder = await open(parent, "r");
          try {
            await folder.sync();
          } finally {
            await folder.close();
          }
          return 201;
        });
        return reply(
          result,
          result === 201
            ? { ok: true }
            : result === 409
              ? { error: "ID sudah tersedia; backup lama dipertahankan" }
              : {
                  error:
                    "Kuota penyimpanan relay penuh; backup lama tetap tersimpan",
                },
        );
      } finally {
        active--;
        if (handle) await handle.close().catch(() => {});
        if (temporary) await unlink(temporary).catch(() => {});
      }
    } catch (e) {
      reply(e.code === "ENOENT" ? 404 : e.code === "ENOSPC" ? 507 : 500, {
        error:
          e.code === "ENOENT"
            ? "Tidak ditemukan"
            : e.code === "ENOSPC"
              ? "Disk relay penuh"
              : "Operasi gagal",
      });
    }
  });
  server.requestTimeout = 120000;
  server.headersTimeout = 15000;
  return server;
}
if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const tls =
    process.env.TLS_CERT_FILE && process.env.TLS_KEY_FILE
      ? {
          cert: await readFile(process.env.TLS_CERT_FILE),
          key: await readFile(process.env.TLS_KEY_FILE),
        }
      : undefined;
  const port = Number(process.env.PORT || 3000);
  createServer({ tls }).listen(port, process.env.HOST || "127.0.0.1", () =>
    console.log(
      `Nusa relay ${tls ? "HTTPS" : "HTTP"} listening on port ${port}`,
    ),
  );
}
