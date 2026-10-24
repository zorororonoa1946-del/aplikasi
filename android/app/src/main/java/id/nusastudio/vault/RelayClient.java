package id.nusastudio.vault;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import org.json.*;

public final class RelayClient {
  private final String base, token;

  public RelayClient(String base, String token) throws Exception {
    URI uri = new URI(base);
    if (!"https".equals(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
      throw new Exception("Gunakan origin HTTPS, tanpa path atau kredensial");
    if (token.length() < 24) throw new Exception("Token relay minimal 24 karakter");
    this.base = base.replaceAll("/+$", "");
    this.token = token;
  }

  private String request(String method, String path, String body) throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection) new URL(base + "/api/files" + path).openConnection();
    connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout(15000);
    connection.setReadTimeout(60000);
    connection.setRequestMethod(method);
    connection.setRequestProperty("Authorization", "Bearer " + token);
    try {
      if (body != null) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = connection.getOutputStream()) {
          out.write(bytes);
        }
      }
      int code = connection.getResponseCode();
      if (code == 409) throw new Conflict();
      if (code < 200 || code >= 300)
        throw new IOException("Relay HTTP " + code + ". Periksa token, alamat, dan koneksi.");
      return new String(
          VaultStore.readLimited(connection.getInputStream(), 40 * 1024 * 1024),
          StandardCharsets.UTF_8);
    } finally {
      connection.disconnect();
    }
  }

  public int backup(VaultStore store) throws Exception {
    int count = 0;
    for (String id : store.ids()) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
      JSONObject envelope = store.read(id);
      try {
        request("PUT", "/" + id, envelope.toString());
      } catch (Conflict e) {
        JSONObject previous = new JSONObject(request("GET", "/" + id, null));
        for (String field : new String[] {"salt", "iv", "ciphertext"})
          if (!previous.getString(field).equals(envelope.getString(field)))
            throw new IOException("Konflik backup; data lama dipertahankan");
      }
      count++;
    }
    return count;
  }

  public int restore(VaultStore store, char[] password) throws Exception {
    JSONArray ids = new JSONArray(request("GET", "", null));
    int count = 0;
    for (int i = 0; i < ids.length(); i++) {
      String id = ids.getString(i);
      if (!id.matches("[a-f0-9-]{36}")) throw new IOException("ID relay tidak valid");
      if (store.ids().contains(id)) continue;
      JSONObject envelope = new JSONObject(request("GET", "/" + id, null));
      JSONObject value = VaultCrypto.decrypt(envelope, password);
      byte[] data = VaultCrypto.decode(value.getString("data"));
      if (value.getLong("size") != data.length || data.length > VaultStore.MAX_FILE_BYTES)
        throw new IOException("Isi backup tidak valid");
      if (value.has("hash")
          && !MessageDigest.isEqual(
              VaultCrypto.decode(value.getString("hash")),
              MessageDigest.getInstance("SHA-256").digest(data)))
        throw new IOException("Checksum file salah");
      Arrays.fill(data, (byte) 0);
      value.getString("name");
      store.write(id, envelope);
      count++;
    }
    return count;
  }

  private static class Conflict extends IOException {}
}
