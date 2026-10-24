package id.nusastudio.vault;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;
import org.json.*;

public final class RelayClient {
  private final String base, token;
  private final SSLSocketFactory trusted;

  public RelayClient(String base, String token) throws Exception {
    this(base, token, "");
  }

  public RelayClient(String base, String token, String ca) throws Exception {
    URI uri = new URI(base);
    if (!"https".equals(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
        || uri.getPort() == 0
        || uri.getPort() > 65535)
      throw new Exception("Gunakan origin HTTPS, tanpa path atau kredensial");
    if (token.length() < 24 || token.contains("\n") || token.contains("\r"))
      throw new Exception("Token relay minimal 24 karakter, tanpa baris baru");
    this.base = base.replaceAll("/+$", "");
    this.token = token;
    if (ca.isEmpty()) trusted = null;
    else {
      java.security.cert.Certificate certificate = certificate(ca);
      KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType());
      keys.load(null);
      keys.setCertificateEntry("relay-selected-by-owner", certificate);
      TrustManagerFactory factory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      factory.init(keys);
      SSLContext ssl = SSLContext.getInstance("TLS");
      ssl.init(null, factory.getTrustManagers(), new SecureRandom());
      trusted = ssl.getSocketFactory();
    }
  }

  public static java.security.cert.Certificate certificate(String pem) throws Exception {
    X509Certificate certificate =
        (X509Certificate)
            CertificateFactory.getInstance("X.509")
                .generateCertificate(
                    new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    certificate.checkValidity();
    return certificate;
  }

  public static String fingerprint(String pem) throws Exception {
    byte[] bytes = MessageDigest.getInstance("SHA-256").digest(certificate(pem).getEncoded());
    StringBuilder s = new StringBuilder();
    for (byte b : bytes) s.append(String.format(java.util.Locale.ROOT, "%02X", b));
    return s.toString();
  }

  private static class Response {
    int code;
    byte[] body;
    String hash;
  }

  private Response request(String method, String path, byte[] body) throws Exception {
    for (int attempt = 0; ; attempt++)
      try {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        HttpsURLConnection c = (HttpsURLConnection) new URL(base + "/api" + path).openConnection();
        if (trusted != null)
          c.setSSLSocketFactory(
              trusted); // Default hostname verification is intentionally retained.
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + token);
        try {
          if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty(
                "Content-Type",
                path.startsWith("/chunks/") ? "application/octet-stream" : "application/json");
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) {
              out.write(body);
            }
          }
          Response result = new Response();
          result.code = c.getResponseCode();
          result.hash = c.getHeaderField("X-Content-SHA256");
          if (result.code == 429 || result.code >= 500 && result.code != 507)
            throw new IOException("Relay sementara tidak tersedia (HTTP " + result.code + ")");
          if (result.code == 404 || result.code == 409) return result;
          if (result.code < 200 || result.code >= 300)
            throw new Exception(
                "Relay HTTP " + result.code + ". Periksa token, alamat, kuota, dan koneksi.");
          result.body =
              method.equals("HEAD")
                  ? new byte[0]
                  : VaultStore.readLimited(c.getInputStream(), 40 * 1024 * 1024);
          return result;
        } finally {
          c.disconnect();
        }
      } catch (IOException error) {
        if (attempt >= 2) throw error;
        Thread.sleep(500L * (attempt + 1));
      }
  }

  private JSONObject json(String method, String path, JSONObject body) throws Exception {
    Response result =
        request(
            method, path, body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8));
    if (result.code == 404) throw new FileNotFoundException("Backup tidak ditemukan");
    if (result.code == 409) return null;
    return new JSONObject(new String(result.body, StandardCharsets.UTF_8));
  }

  public int backup(VaultStore store, char[] password) throws Exception {
    Response listing = request("GET", "/files", null);
    new JSONArray(new String(listing.body, StandardCharsets.UTF_8));
    int count = 0, failed = 0;
    for (String id : store.ids()) {
      String name = id;
      try {
        JSONObject envelope = store.read(id), value = VaultCrypto.decrypt(envelope, password);
        VaultStore.validate(value, id);
        name = value.getString("name");
        if (value.optInt("format") == 2) {
          JSONArray parts = value.getJSONArray("parts");
          for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            String path = "/chunks/" + id + "/" + i;
            Response existing = request("HEAD", path, null);
            if (existing.code != 404) {
              if (!part.getString("sha256").equals(existing.hash))
                throw new IOException("Konflik chunk; backup lama dipertahankan");
              continue;
            }
            byte[] data =
                VaultStore.readLimited(
                    new FileInputStream(store.chunk(id, i)), VaultStore.CHUNK_BYTES + 16);
            if (!MessageDigest.isEqual(
                VaultCrypto.decode(part.getString("sha256")),
                MessageDigest.getInstance("SHA-256").digest(data)))
              throw new IOException("Chunk lokal rusak");
            Response saved = request("PUT", path, data);
            if (saved.code == 409
                && !part.getString("sha256").equals(request("HEAD", path, null).hash))
              throw new IOException("Konflik chunk");
          }
        }
        JSONObject saved = json("PUT", "/files/" + id, envelope);
        if (saved == null) {
          JSONObject previous = json("GET", "/files/" + id, null);
          for (String key : new String[] {"salt", "iv", "ciphertext"})
            if (!previous.getString(key).equals(envelope.getString(key)))
              throw new IOException("Konflik backup; data lama dipertahankan");
        }
        count++;
        store.log("Backup berhasil: " + name);
      } catch (Exception e) {
        if (e instanceof InterruptedException) throw e;
        failed++;
        store.log("Backup gagal: " + name + " — " + e.getMessage());
      }
    }
    store.flushCatalog(password);
    if (failed > 0)
      throw new IOException(
          count
              + " file berhasil, "
              + failed
              + " file gagal. Backup berhasil tetap disimpan; coba lagi untuk melanjutkan.");
    return count;
  }

  public int restore(VaultStore store, char[] password) throws Exception {
    Response response = request("GET", "/files", null);
    JSONArray ids = new JSONArray(new String(response.body, StandardCharsets.UTF_8));
    int count = 0, failed = 0;
    for (int n = 0; n < ids.length(); n++) {
      String id = ids.getString(n);
      if (!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
        throw new IOException("ID relay tidak valid");
      if (store.healthy(id, password)) continue;
      String name = id;
      try {
        JSONObject envelope = json("GET", "/files/" + id, null),
            value = VaultCrypto.decrypt(envelope, password);
        VaultStore.validate(value, id);
        name = value.getString("name");
        if (value.optInt("format") == 2) {
          JSONArray parts = value.getJSONArray("parts");
          byte[] key = VaultCrypto.decode(value.getString("key"));
          MessageDigest hash = MessageDigest.getInstance("SHA-256");
          long size = 0;
          try {
            for (int i = 0; i < parts.length(); i++) {
              JSONObject part = parts.getJSONObject(i);
              Response chunk = request("GET", "/chunks/" + id + "/" + i, null);
              if (chunk.code == 404) throw new IOException("Backup belum lengkap; chunk hilang");
              byte[] encrypted = chunk.body;
              if (!MessageDigest.isEqual(
                  VaultCrypto.decode(part.getString("sha256")),
                  MessageDigest.getInstance("SHA-256").digest(encrypted)))
                throw new IOException("Checksum chunk salah");
              byte[] plain =
                  VaultStore.cryptChunk(
                      javax.crypto.Cipher.DECRYPT_MODE,
                      key,
                      id,
                      i,
                      VaultCrypto.decode(part.getString("iv")),
                      encrypted);
              try {
                if (plain.length != part.getInt("size"))
                  throw new IOException("Ukuran chunk salah");
                hash.update(plain);
                size += plain.length;
              } finally {
                java.util.Arrays.fill(plain, (byte) 0);
              }
              android.util.AtomicFile atomic = new android.util.AtomicFile(store.chunk(id, i));
              File parent = store.chunk(id, i).getParentFile();
              if (!parent.isDirectory() && !parent.mkdirs())
                throw new IOException("Penyimpanan penuh");
              FileOutputStream out = null;
              try {
                out = atomic.startWrite();
                out.write(encrypted);
                atomic.finishWrite(out);
              } catch (Exception error) {
                if (out != null) atomic.failWrite(out);
                throw error;
              }
            }
            if (size != value.getLong("size")
                || value.has("hash")
                    && !value.getString("hash").isEmpty()
                    && !MessageDigest.isEqual(
                        VaultCrypto.decode(value.getString("hash")), hash.digest()))
              throw new IOException("Checksum/ukuran file salah");
          } finally {
            java.util.Arrays.fill(key, (byte) 0);
          }
        }
        store.saveRecord(id, envelope, value, password);
        count++;
        store.log("Pulih berhasil: " + name);
      } catch (Exception error) {
        if (error instanceof InterruptedException) throw error;
        failed++;
        store.log("Pulih gagal: " + name + " — " + error.getClass().getSimpleName());
      }
    }
    store.flushCatalog(password);
    if (failed > 0)
      throw new IOException(
          count
              + " file dipulihkan; "
              + failed
              + " belum dapat dibuka/lengkap. Periksa kata sandi dan riwayat.");
    return count;
  }
}
