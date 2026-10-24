import { fileURLToPath } from "node:url";
import {
  mkdir,
  readFile,
  writeFile,
  access,
  open,
  unlink,
  chmod,
} from "node:fs/promises";
import { resolve } from "node:path";
import { randomBytes, X509Certificate } from "node:crypto";
import { spawnSync } from "node:child_process";
import { createServer } from "../server.js";
process.chdir(fileURLToPath(new URL("..", import.meta.url)));
const args = process.argv.slice(2),
  host = args[0] && !args[0].startsWith("--") ? args[0] : "127.0.0.1";
const parts = host.split(".").map(Number);
if (
  parts.length !== 4 ||
  parts.some((n) => !Number.isInteger(n) || n < 0 || n > 255) ||
  !(
    /^(10\.|127\.|192\.168\.)/.test(host) ||
    (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31)
  )
)
  throw Error("Masukkan IPv4 LAN pribadi laptop, misalnya 192.168.1.10");
const port = args.includes("--port")
  ? Number(args[args.indexOf("--port") + 1])
  : 3443;
if (!Number.isInteger(port) || port < 0 || port > 65535)
  throw Error("Port tidak valid");
const directory = resolve(process.env.LOCAL_RELAY_DIR || ".local/local-relay");
await mkdir(directory, { recursive: true, mode: 0o700 });
const exists = (path) =>
  access(path).then(
    () => true,
    () => false,
  );
const lockPath = resolve(directory, ".setup.lock");
let lock;
try {
  lock = await open(lockPath, "wx", 0o600);
} catch (error) {
  if (error.code !== "EEXIST") throw error;
  let old;
  try {
    old = JSON.parse(await readFile(lockPath, "utf8"));
    process.kill(old.pid, 0);
  } catch (e) {
    if (e.code === "ESRCH") {
      await unlink(lockPath);
      lock = await open(lockPath, "wx", 0o600);
    } else
      throw Error(
        "Setup relay lain sedang berjalan; jangan menghapus lock milik proses aktif.",
      );
  }
  if (!lock) throw Error("Setup relay lain sedang berjalan.");
}
await lock.writeFile(JSON.stringify({ pid: process.pid }));
function openssl(args, allowFailure = false) {
  const result = spawnSync("openssl", args, { encoding: "utf8" });
  if (result.error)
    throw Error("OpenSSL diperlukan. Instal OpenSSL lalu ulangi.");
  if (result.status !== 0 && !allowFailure)
    throw Error(
      "OpenSSL gagal membuat/memeriksa sertifikat. Periksa izin direktori dan versi OpenSSL.",
    );
  return result.status === 0;
}
let config, certificate, key, ca;
try {
  const configPath = resolve(directory, "config.json");
  if (await exists(configPath)) {
    config = JSON.parse(await readFile(configPath, "utf8"));
    if (typeof config.token !== "string" || config.token.length < 24)
      throw Error("Konfigurasi token tidak valid; file lama dipertahankan.");
  } else {
    config = { token: randomBytes(32).toString("base64url") };
    await writeFile(configPath, JSON.stringify(config), {
      flag: "wx",
      mode: 0o600,
    });
  }
  const caPath = resolve(directory, "ca.pem"),
    caKey = resolve(directory, "ca-key.pem"),
    serverKey = resolve(directory, "server-key.pem"),
    serverCert = resolve(directory, "server.pem");
  if ((await exists(caPath)) !== (await exists(caKey)))
    throw Error(
      "Pasangan CA tidak lengkap. Pulihkan backup CA pribadi sebelum melanjutkan.",
    );
  if (!(await exists(caPath)))
    openssl([
      "req",
      "-x509",
      "-newkey",
      "rsa:3072",
      "-nodes",
      "-keyout",
      caKey,
      "-out",
      caPath,
      "-days",
      "3650",
      "-subj",
      "/CN=Nusa Owner Local Relay CA",
      "-addext",
      "basicConstraints=critical,CA:TRUE",
      "-addext",
      "keyUsage=critical,keyCertSign,cRLSign",
    ]);
  if (!(await exists(serverKey)))
    openssl([
      "genpkey",
      "-algorithm",
      "RSA",
      "-pkeyopt",
      "rsa_keygen_bits:2048",
      "-out",
      serverKey,
    ]);
  if (
    config.host !== host ||
    !(await exists(serverCert)) ||
    !openssl(["x509", "-checkend", "604800", "-noout", "-in", serverCert], true)
  ) {
    const csr = resolve(directory, "server.csr"),
      extensions = resolve(directory, "server.ext");
    await writeFile(
      extensions,
      `basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost,IP:127.0.0.1,IP:${host}\n`,
      { mode: 0o600 },
    );
    openssl([
      "req",
      "-new",
      "-key",
      serverKey,
      "-out",
      csr,
      "-subj",
      "/CN=Nusa Local Relay",
    ]);
    openssl([
      "x509",
      "-req",
      "-in",
      csr,
      "-CA",
      caPath,
      "-CAkey",
      caKey,
      "-CAcreateserial",
      "-out",
      serverCert,
      "-days",
      "365",
      "-sha256",
      "-extfile",
      extensions,
    ]);
    await unlink(csr);
    await unlink(extensions);
  }
  for (const path of [caKey, serverKey]) await chmod(path, 0o600);
  config.host = host;
  await writeFile(configPath, JSON.stringify(config), { mode: 0o600 });
  certificate = await readFile(serverCert);
  key = await readFile(serverKey);
  ca = await readFile(caPath);
} finally {
  await lock.close();
  await unlink(lockPath);
}
const fingerprint = new X509Certificate(ca).fingerprint256.replaceAll(":", "");
async function instructions(actualPort) {
  await writeFile(
    resolve(directory, "connection.txt"),
    `Relay: https://${host}:${actualPort}\nToken: ${config.token}\nCA certificate: ca.pem\nCA SHA-256: ${fingerprint}\n`,
    { mode: 0o600 },
  );
  console.log(
    `Sertifikat publik untuk diimpor di Android: ${resolve(directory, "ca.pem")}`,
  );
  console.log(`Cocokkan fingerprint SHA-256: ${fingerprint}`);
  console.log(
    `Alamat dan token ada di ${resolve(directory, "connection.txt")}; baca secara privat, jangan kirim melalui chat atau Git.`,
  );
}
if (args.includes("--init-only")) await instructions(port);
else {
  const server = createServer({
    token: config.token,
    dataDir: resolve(directory, "data"),
    tls: { cert: certificate, key },
  });
  server.on("error", (error) => {
    console.error(
      `Relay tidak dapat dimulai (${error.code || "TLS"}). Data/config lama dipertahankan.`,
    );
    process.exitCode = 1;
  });
  server.listen(port, "0.0.0.0", () => instructions(server.address().port));
  for (const signal of ["SIGTERM", "SIGINT"])
    process.on(signal, () => server.close(() => process.exit(0)));
}
