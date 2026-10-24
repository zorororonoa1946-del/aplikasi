package id.nusastudio.vault;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONObject;

public final class VaultStore {
  public static final int MAX_FILE_BYTES = 20 * 1024 * 1024;
  private final Context context;
  private final File directory;

  public VaultStore(Context context) {
    this.context = context.getApplicationContext();
    directory = new File(context.getFilesDir(), "vault");
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new IllegalStateException("Penyimpanan tidak tersedia");
  }

  private File file(String id) throws Exception {
    if (!id.equals("check") && !id.matches("[a-f0-9-]{36}")) throw new Exception("ID tidak valid");
    return new File(directory, id + ".json");
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

  public synchronized void write(String id, JSONObject value) throws Exception {
    AtomicFile atomic = new AtomicFile(file(id));
    FileOutputStream stream = null;
    try {
      stream = atomic.startWrite();
      stream.write(value.toString().getBytes(StandardCharsets.UTF_8));
      atomic.finishWrite(stream);
    } catch (Exception e) {
      if (stream != null) atomic.failWrite(stream);
      throw e;
    }
  }

  public List<String> ids() {
    List<String> ids = new ArrayList<>();
    String[] names = directory.list();
    if (names != null)
      for (String name : names)
        if (name.matches("[a-f0-9-]{36}\\.json")) ids.add(name.substring(0, 36));
    Collections.sort(ids);
    return ids;
  }

  public void delete(String id) throws Exception {
    if (!file(id).delete()) throw new Exception("Tidak dapat menghapus file");
  }

  public List<JSONObject> list(char[] password) throws Exception {
    List<JSONObject> result = new ArrayList<>();
    for (String id : ids()) {
      JSONObject value = VaultCrypto.decrypt(read(id), password);
      value.remove("data");
      value.put("id", id);
      result.add(value);
    }
    return result;
  }

  public String importBytes(String name, byte[] data, String source, char[] password)
      throws Exception {
    if (data.length > MAX_FILE_BYTES) throw new Exception("Batas file 20 MB");
    String hash = VaultCrypto.encode(MessageDigest.getInstance("SHA-256").digest(data));
    if (!source.isEmpty())
      for (JSONObject previous : list(password))
        if (source.equals(previous.optString("source")) && hash.equals(previous.optString("hash")))
          return previous.getString("id");
    JSONObject value =
        new JSONObject()
            .put("name", name)
            .put("size", data.length)
            .put("date", java.time.Instant.now().toString())
            .put("data", VaultCrypto.encode(data))
            .put("source", source)
            .put("hash", hash);
    String id = UUID.randomUUID().toString();
    write(id, VaultCrypto.encrypt(value, password));
    return id;
  }

  public void importUri(Uri uri, String relative, char[] password) throws Exception {
    String name = relative;
    if (name == null) {
      try (Cursor c =
          context
              .getContentResolver()
              .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
        name = c != null && c.moveToFirst() ? c.getString(0) : "file";
      }
    }
    byte[] bytes = readLimited(context.getContentResolver().openInputStream(uri), MAX_FILE_BYTES);
    try {
      importBytes(name, bytes, uri.toString(), password);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  public int importTree(Uri tree, char[] password) throws Exception {
    return scan(tree, DocumentsContract.getTreeDocumentId(tree), "", password, 0, new HashSet<>());
  }

  private int scan(Uri tree, String id, String prefix, char[] password, int depth, Set<String> seen)
      throws Exception {
    if (depth > 32 || !seen.add(id)) throw new Exception("Folder terlalu dalam atau berulang");
    int count = 0;
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                children,
                new String[] {
                  DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                  DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                  DocumentsContract.Document.COLUMN_MIME_TYPE
                },
                null,
                null,
                null)) {
      if (cursor == null) throw new Exception("Folder tidak dapat dibaca");
      while (cursor.moveToNext()) {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        String child = cursor.getString(0), name = prefix + cursor.getString(1);
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2)))
          count += scan(tree, child, name + "/", password, depth + 1, seen);
        else {
          importUri(DocumentsContract.buildDocumentUriUsingTree(tree, child), name, password);
          count++;
        }
      }
    }
    return count;
  }

  static byte[] readLimited(InputStream input, int limit) throws Exception {
    if (input == null) throw new IOException("File tidak dapat dibaca");
    try (InputStream stream = input;
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[16384];
      int total = 0, n;
      while ((n = stream.read(buffer)) != -1) {
        total += n;
        if (total > limit) throw new IOException("Batas ukuran file terlampaui");
        output.write(buffer, 0, n);
      }
      return output.toByteArray();
    }
  }
}
