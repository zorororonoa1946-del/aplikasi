package id.nusastudio.vault;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.json.*;

public final class VaultStore {
  public static final int CHUNK_BYTES = 4 * 1024 * 1024;
  private static final String UUID_PATTERN =
      "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
  private final Context context;
  private final File directory;
  private final Map<String, String> sourceIndex = new HashMap<>();
  private boolean indexed;
  private boolean catalogLoaded, catalogDirty;
  private final Map<String, JSONObject> catalog = new LinkedHashMap<>();

  public VaultStore(Context context) {
    this.context = context.getApplicationContext();
    directory = new File(context.getFilesDir(), "vault");
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new IllegalStateException("Penyimpanan tidak tersedia");
  }

  private File file(String id) throws Exception {
    if (!id.equals("check") && !id.equals("catalog") && !id.matches(UUID_PATTERN))
      throw new Exception("ID tidak valid");
    return new File(directory, id + ".json");
  }

  public File chunk(String id, int index) throws Exception {
    file(id);
    if (!id.matches(UUID_PATTERN) || index < 0) throw new Exception("ID chunk salah");
    return new File(new File(new File(directory, "chunks"), id), index + ".bin");
  }

  public synchronized void clearSession() {
    catalog.clear();
    sourceIndex.clear();
    catalogLoaded = false;
    indexed = false;
  }

  public void log(String message) {
    new PrivateSettings(context).log(message);
  }

  public boolean initialized() {
    return new File(directory, "check.json").exists();
  }

  public synchronized void unlock(char[] password) throws Exception {
    if (password.length < 12) throw new Exception("Kata sandi minimal 12 karakter");
    if (initialized()) {
      if (!"nusa-vault".equals(VaultCrypto.decrypt(read("check"), password).optString("check")))
        throw new Exception("Kata sandi salah");
    } else
      write("check", VaultCrypto.encrypt(new JSONObject().put("check", "nusa-vault"), password));
  }

  public synchronized JSONObject read(String id) throws Exception {
    return new JSONObject(
        new String(
            readLimited(new AtomicFile(file(id)).openRead(), 40 * 1024 * 1024),
            StandardCharsets.UTF_8));
  }

  private void atomic(File destination, byte[] bytes) throws Exception {
    File parent = destination.getParentFile();
    if (!parent.isDirectory() && !parent.mkdirs())
      throw new IOException("Penyimpanan penuh/tidak tersedia");
    AtomicFile a = new AtomicFile(destination);
    FileOutputStream out = null;
    try {
      out = a.startWrite();
      out.write(bytes);
      a.finishWrite(out);
    } catch (Exception e) {
      if (out != null) a.failWrite(out);
      throw e;
    }
  }

  public synchronized void write(String id, JSONObject value) throws Exception {
    atomic(file(id), value.toString().getBytes(StandardCharsets.UTF_8));
    indexed = false;
    catalogLoaded = false;
    catalogDirty = true;
  }

  public List<String> ids() {
    List<String> ids = new ArrayList<>();
    String[] names = directory.list();
    if (names != null)
      for (String name : names)
        if (name.matches(UUID_PATTERN + "\\.json")) ids.add(name.substring(0, 36));
    Collections.sort(ids);
    return ids;
  }

  private static void removeTree(File path) {
    File[] children = path.listFiles();
    if (children != null) for (File child : children) removeTree(child);
    path.delete();
  }

  public synchronized void delete(String id) throws Exception {
    File target = file(id);
    if (!target.exists()) throw new IOException("File tidak ditemukan");
    new AtomicFile(target).delete();
    removeTree(new File(new File(directory, "chunks"), id));
    catalog.remove(id);
    catalogDirty = true;
    indexed = false;
  }

  public static void validate(JSONObject value, String id) throws Exception {
    if (value.optString("name").isEmpty()
        || !(value.get("size") instanceof Number)
        || value.getDouble("size") != value.getLong("size")
        || value.getLong("size") < 0
        || value.getLong("size") > 9007199254740991L)
      throw new IOException("Metadata ukuran/nama rusak");
    java.time.Instant.parse(value.getString("date"));
    if (value.optInt("format") == 2) {
      long size = value.getLong("size");
      JSONArray parts = value.getJSONArray("parts");
      if (!id.equals(value.getString("fileId"))
          || VaultCrypto.decode(value.getString("key")).length != 32
          || value.getInt("chunkSize") != CHUNK_BYTES
          || parts.length() != (size + CHUNK_BYTES - 1) / CHUNK_BYTES)
        throw new IOException("Manifest chunk rusak");
      for (int i = 0; i < parts.length(); i++) {
        JSONObject part = parts.getJSONObject(i);
        if (part.getInt("size") != Math.min(CHUNK_BYTES, size - (long) i * CHUNK_BYTES)
            || VaultCrypto.decode(part.getString("iv")).length != 12
            || VaultCrypto.decode(part.getString("sha256")).length != 32)
          throw new IOException("Deskriptor chunk rusak");
      }
    } else if (VaultCrypto.decode(value.getString("data")).length != value.getLong("size"))
      throw new IOException("Ukuran file tidak cocok");
  }

  private JSONObject catalogEntry(JSONObject value, String id) throws Exception {
    JSONObject entry = new JSONObject().put("id", id);
    for (String key :
        new String[] {"name", "size", "date", "source", "hash", "format", "unreadable"})
      if (value.has(key)) entry.put(key, value.get(key));
    return entry;
  }

  private synchronized void loadCatalog(char[] password) throws Exception {
    List<String> ids = ids();
    java.util.HashSet<String> actual = new java.util.HashSet<>(ids);
    if (catalogLoaded && actual.equals(catalog.keySet())) return;
    catalog.clear();
    try {
      JSONObject saved = VaultCrypto.decrypt(read("catalog"), password);
      if (!"catalog-v1".equals(saved.getString("type"))) throw new IOException();
      JSONArray entries = saved.getJSONArray("files");
      for (int i = 0; i < entries.length(); i++) {
        JSONObject value = entries.getJSONObject(i);
        String id = value.getString("id");
        if (!id.matches(UUID_PATTERN) || value.optString("name").isEmpty()) throw new IOException();
        catalog.put(id, value);
      }
      if (!actual.equals(catalog.keySet()) || catalog.size() != entries.length())
        throw new IOException();
      catalogLoaded = true;
      catalogDirty = false;
      return;
    } catch (Exception stale) {
      catalog.clear();
    }
    for (String id : ids)
      try {
        JSONObject value = VaultCrypto.decrypt(read(id), password);
        validate(value, id);
        catalog.put(id, catalogEntry(value, id));
      } catch (Exception error) {
        catalog.put(
            id,
            new JSONObject()
                .put("id", id)
                .put("name", "File tidak dapat dibuka (" + id.substring(0, 8) + ")")
                .put("size", 0)
                .put("date", "")
                .put("unreadable", true));
      }
    catalogLoaded = true;
    catalogDirty = true;
  }

  public synchronized void flushCatalog(char[] password) throws Exception {
    loadCatalog(password);
    if (!catalogDirty) return;
    JSONArray values = new JSONArray();
    for (String id : ids()) values.put(catalog.get(id));
    JSONObject encrypted =
        VaultCrypto.encrypt(
            new JSONObject().put("type", "catalog-v1").put("files", values), password);
    atomic(file("catalog"), encrypted.toString().getBytes(StandardCharsets.UTF_8));
    catalogDirty = false;
  }

  public synchronized void saveRecord(
      String id, JSONObject envelope, JSONObject value, char[] password) throws Exception {
    loadCatalog(password);
    write(id, envelope);
    catalog.put(id, catalogEntry(value, id));
    catalogLoaded = true;
    catalogDirty = true;
  }

  public synchronized List<JSONObject> list(char[] password) throws Exception {
    loadCatalog(password);
    flushCatalog(password);
    List<JSONObject> result = new ArrayList<>();
    for (String id : ids()) result.add(new JSONObject(catalog.get(id).toString()));
    return result;
  }

  private void index(char[] password) throws Exception {
    if (indexed) return;
    sourceIndex.clear();
    for (JSONObject v : list(password))
      if (!v.optBoolean("unreadable") && !v.optString("source").isEmpty())
        sourceIndex.put(
            v.getString("source") + '\0' + v.getString("name") + '\0' + v.optString("hash"),
            v.getString("id"));
    indexed = true;
  }

  public static byte[] cryptChunk(
      int mode, byte[] key, String id, int index, byte[] iv, byte[] bytes) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
    cipher.updateAAD(("nusa-v2:" + id + ":" + index).getBytes(StandardCharsets.UTF_8));
    return cipher.doFinal(bytes);
  }

  public synchronized String importStream(
      String name, InputStream source, String sourceId, char[] password) throws Exception {
    String id = UUID.randomUUID().toString();
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    JSONArray parts = new JSONArray();
    MessageDigest hash = MessageDigest.getInstance("SHA-256");
    long total = 0;
    boolean committed = false;
    byte[] buffer = new byte[CHUNK_BYTES];
    try (InputStream input = source) {
      if (input == null) throw new IOException("File tidak dapat dibaca");
      index(password);
      while (true) {
        int count = 0, n;
        while (count < buffer.length
            && (n = input.read(buffer, count, buffer.length - count)) != -1) {
          if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
          count += n;
        }
        if (count == 0) break;
        hash.update(buffer, 0, count);
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        byte[] plain = Arrays.copyOf(buffer, count);
        byte[] encrypted;
        try {
          encrypted = cryptChunk(Cipher.ENCRYPT_MODE, key, id, parts.length(), iv, plain);
        } finally {
          Arrays.fill(plain, (byte) 0);
        }
        atomic(chunk(id, parts.length()), encrypted);
        parts.put(
            new JSONObject()
                .put("iv", VaultCrypto.encode(iv))
                .put("size", count)
                .put(
                    "sha256",
                    VaultCrypto.encode(MessageDigest.getInstance("SHA-256").digest(encrypted))));
        total += count;
        if (count < buffer.length) break;
      }
      Arrays.fill(buffer, (byte) 0);
      String fingerprint = VaultCrypto.encode(hash.digest()),
          existing = sourceIndex.get(sourceId + '\0' + name + '\0' + fingerprint);
      if (existing != null && healthy(existing, password)) {
        removeTree(new File(new File(directory, "chunks"), id));
        return existing;
      }
      JSONObject metadata =
          new JSONObject()
              .put("format", 2)
              .put("fileId", id)
              .put("chunkSize", CHUNK_BYTES)
              .put("key", VaultCrypto.encode(key))
              .put("parts", parts)
              .put("name", name)
              .put("size", total)
              .put("date", java.time.Instant.now().toString())
              .put("source", sourceId)
              .put("hash", fingerprint);
      saveRecord(id, VaultCrypto.encrypt(metadata, password), metadata, password);
      committed = true;
      sourceIndex.put(sourceId + '\0' + name + '\0' + fingerprint, id);
      indexed = true;
      log("Diimpor: " + name + " (" + total + " byte)");
      return id;
    } finally {
      Arrays.fill(key, (byte) 0);
      Arrays.fill(buffer, (byte) 0);
      if (!committed) removeTree(new File(new File(directory, "chunks"), id));
    }
  }

  public String importBytes(String name, byte[] data, String source, char[] password)
      throws Exception {
    return importStream(name, new ByteArrayInputStream(data), source, password);
  }

  public void importUri(Uri uri, String relative, char[] password) throws Exception {
    String name = relative;
    if (name == null)
      try (Cursor c =
          context
              .getContentResolver()
              .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
        name = c != null && c.moveToFirst() ? c.getString(0) : "file";
      }
    importStream(name, context.getContentResolver().openInputStream(uri), uri.toString(), password);
  }

  public boolean healthy(String id, char[] pass) {
    try {
      JSONObject value = VaultCrypto.decrypt(read(id), pass);
      validate(value, id);
      if (value.optInt("format") == 2) {
        JSONArray parts = value.getJSONArray("parts");
        for (int i = 0; i < parts.length(); i++) {
          byte[] cipher = readLimited(new FileInputStream(chunk(id, i)), CHUNK_BYTES + 16);
          if (!MessageDigest.isEqual(
              VaultCrypto.decode(parts.getJSONObject(i).getString("sha256")),
              MessageDigest.getInstance("SHA-256").digest(cipher))) return false;
        }
      }
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public void copyTo(String id, char[] pass, OutputStream output) throws Exception {
    JSONObject v = VaultCrypto.decrypt(read(id), pass);
    validate(v, id);
    if (v.optInt("format") != 2) {
      byte[] data = VaultCrypto.decode(v.getString("data"));
      try {
        output.write(data);
      } finally {
        Arrays.fill(data, (byte) 0);
      }
      return;
    }
    byte[] key = VaultCrypto.decode(v.getString("key"));
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    long total = 0;
    try {
      JSONArray parts = v.getJSONArray("parts");
      for (int i = 0; i < parts.length(); i++) {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        JSONObject part = parts.getJSONObject(i);
        byte[] cipher = readLimited(new FileInputStream(chunk(id, i)), CHUNK_BYTES + 16);
        if (!MessageDigest.isEqual(
            VaultCrypto.decode(part.getString("sha256")),
            MessageDigest.getInstance("SHA-256").digest(cipher)))
          throw new IOException("Checksum chunk salah");
        byte[] plain =
            cryptChunk(
                Cipher.DECRYPT_MODE, key, id, i, VaultCrypto.decode(part.getString("iv")), cipher);
        try {
          if (plain.length != part.getInt("size")) throw new IOException("Ukuran chunk salah");
          digest.update(plain);
          output.write(plain);
          total += plain.length;
        } finally {
          Arrays.fill(plain, (byte) 0);
        }
      }
      if (total != v.getLong("size")) throw new IOException("Ukuran hasil tidak cocok");
      if (v.has("hash")
          && !v.getString("hash").isEmpty()
          && !MessageDigest.isEqual(VaultCrypto.decode(v.getString("hash")), digest.digest()))
        throw new IOException("Checksum file salah");
    } finally {
      Arrays.fill(key, (byte) 0);
    }
  }

  public int importTree(Uri tree, char[] password) throws Exception {
    ArrayDeque<String[]> pending = new ArrayDeque<>();
    Set<String> seen = new HashSet<>();
    String root = DocumentsContract.getTreeDocumentId(tree);
    pending.push(new String[] {root, ""});
    int count = 0, failed = 0;
    while (!pending.isEmpty()) {
      String[] current = pending.pop();
      if (!seen.add(current[0])) continue;
      try (Cursor c =
          context
              .getContentResolver()
              .query(
                  DocumentsContract.buildChildDocumentsUriUsingTree(tree, current[0]),
                  new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                  },
                  null,
                  null,
                  null)) {
        if (c == null) throw new IOException("Folder tidak dapat dibaca");
        while (c.moveToNext()) {
          if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
          String child = c.getString(0), name = current[1] + c.getString(1);
          if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2)))
            pending.push(new String[] {child, name + "/"});
          else
            try {
              importUri(DocumentsContract.buildDocumentUriUsingTree(tree, child), name, password);
              count++;
            } catch (Exception e) {
              if (e instanceof InterruptedException) throw e;
              failed++;
              log("Gagal impor: " + name + " — " + e.getClass().getSimpleName());
            }
        }
      } catch (Exception e) {
        if (e instanceof InterruptedException) throw e;
        failed++;
        log("Gagal baca folder: " + current[1] + " — " + e.getClass().getSimpleName());
      }
    }
    if (failed > 0)
      throw new IOException(
          count
              + " file diproses, "
              + failed
              + " sumber gagal; salinan yang berhasil tetap disimpan");
    return count;
  }

  public static byte[] readLimited(InputStream input, int limit) throws Exception {
    if (input == null) throw new IOException("File tidak dapat dibaca");
    try (InputStream stream = input;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[16384];
      int total = 0, n;
      while ((n = stream.read(buffer)) != -1) {
        total += n;
        if (total > limit) throw new IOException("Batas ukuran data terlampaui");
        out.write(buffer, 0, n);
      }
      return out.toByteArray();
    }
  }
}
