// Shared v2 format helpers. Metadata remains encrypted using the v1 envelope.
export const CHUNK_BYTES = 4 * 1024 * 1024;
export const ID =
  /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
export const encoder = new TextEncoder();
export const base64 = (bytes) => {
  let text = "";
  for (let i = 0; i < bytes.length; i += 8192)
    text += String.fromCharCode(...bytes.subarray(i, i + 8192));
  return btoa(text);
};
export function unbase64(text) {
  if (
    typeof text !== "string" ||
    text.length % 4 !== 0 ||
    !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(
      text,
    )
  )
    throw Error("Base64 tidak valid");
  return Uint8Array.from(atob(text), (c) => c.charCodeAt(0));
}
export const aad = (id, index) => encoder.encode(`nusa-v2:${id}:${index}`);
export async function derive(pass, salt) {
  const material = await crypto.subtle.importKey(
    "raw",
    encoder.encode(pass),
    "PBKDF2",
    false,
    ["deriveKey"],
  );
  return crypto.subtle.deriveKey(
    { name: "PBKDF2", hash: "SHA-256", salt, iterations: 600000 },
    material,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}
export async function encrypt(value, pass) {
  const salt = crypto.getRandomValues(new Uint8Array(16)),
    iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv },
    await derive(pass, salt),
    encoder.encode(JSON.stringify(value)),
  );
  return {
    version: 1,
    salt: base64(salt),
    iv: base64(iv),
    ciphertext: base64(new Uint8Array(ciphertext)),
  };
}
export async function decrypt(envelope, pass) {
  if (!envelope || envelope.version !== 1)
    throw Error("Versi enkripsi tidak didukung");
  const salt = unbase64(envelope.salt),
    iv = unbase64(envelope.iv),
    cipher = unbase64(envelope.ciphertext);
  if (salt.length !== 16 || iv.length !== 12 || cipher.length < 16)
    throw Error("Envelope rusak");
  return JSON.parse(
    new TextDecoder("utf-8", { fatal: true }).decode(
      await crypto.subtle.decrypt(
        { name: "AES-GCM", iv },
        await derive(pass, salt),
        cipher,
      ),
    ),
  );
}
export function validate(value, id) {
  if (
    !value ||
    typeof value.name !== "string" ||
    !value.name ||
    !Number.isSafeInteger(value.size) ||
    value.size < 0 ||
    typeof value.date !== "string" ||
    !Number.isFinite(Date.parse(value.date))
  )
    throw Error("Metadata file rusak");
  if (value.format === 2) {
    if (
      !ID.test(id) ||
      value.fileId !== id ||
      unbase64(value.key).length !== 32 ||
      value.chunkSize !== CHUNK_BYTES ||
      !Array.isArray(value.parts) ||
      value.parts.length !== Math.ceil(value.size / CHUNK_BYTES)
    )
      throw Error("Manifest chunk tidak valid");
    value.parts.forEach((part, i) => {
      const expected = Math.min(CHUNK_BYTES, value.size - i * CHUNK_BYTES);
      if (
        !part ||
        part.size !== expected ||
        unbase64(part.iv).length !== 12 ||
        unbase64(part.sha256).length !== 32
      )
        throw Error("Deskriptor chunk rusak");
    });
  } else if (unbase64(value.data).length !== value.size)
    throw Error("Ukuran file tidak cocok");
  return value;
}
export async function openChunk(value, id, index, ciphertext) {
  const part = value.parts[index];
  if (
    ciphertext.byteLength !== part.size + 16 ||
    base64(
      new Uint8Array(await crypto.subtle.digest("SHA-256", ciphertext)),
    ) !== part.sha256
  )
    throw Error("Checksum chunk salah");
  const key = await crypto.subtle.importKey(
    "raw",
    unbase64(value.key),
    "AES-GCM",
    false,
    ["decrypt"],
  );
  return new Uint8Array(
    await crypto.subtle.decrypt(
      {
        name: "AES-GCM",
        iv: unbase64(part.iv),
        additionalData: aad(id, index),
      },
      key,
      ciphertext,
    ),
  );
}
