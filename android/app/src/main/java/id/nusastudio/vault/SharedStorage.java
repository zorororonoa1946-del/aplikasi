package id.nusastudio.vault;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import java.io.*;
import java.util.*;

/** Optional, owner-approved shared-storage access; never accesses private app/system storage. */
public final class SharedStorage {
  public static boolean allowed(Context context) {
    if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
    return context.checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE")
        == PackageManager.PERMISSION_GRANTED;
  }

  public static int scan(Context context, VaultStore store, char[] password) throws Exception {
    if (!allowed(context))
      throw new SecurityException("Izin penyimpanan bersama belum diberikan atau dicabut");
    File root = Environment.getExternalStorageDirectory().getCanonicalFile();
    String prefix = root.getPath() + File.separator;
    ArrayDeque<File> pending = new ArrayDeque<>();
    Set<String> visited = new HashSet<>();
    pending.push(root);
    int count = 0, failed = 0;
    while (!pending.isEmpty()) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
      File selected = pending.pop(), canonical;
      try {
        canonical = selected.getCanonicalFile();
      } catch (IOException | SecurityException error) {
        failed++;
        store.log("Path tidak terbaca: " + selected.getName());
        continue;
      }
      String path = canonical.getPath();
      if (!path.equals(root.getPath()) && !path.startsWith(prefix)) {
        store.log("Dilewati: symlink di luar sumber penyimpanan bersama");
        continue;
      }
      if (!visited.add(path)) continue;
      String relative = path.equals(root.getPath()) ? "" : path.substring(prefix.length());
      if (Build.VERSION.SDK_INT >= 30
          && (relative.equals("Android/data")
              || relative.equals("Android/obb")
              || relative.startsWith("Android/data/")
              || relative.startsWith("Android/obb/"))) {
        store.log("Dilewati sesuai batas Android: " + relative);
        continue;
      }
      if (canonical.isDirectory()) {
        File[] children;
        try {
          children = canonical.listFiles();
        } catch (SecurityException error) {
          children = null;
        }
        if (children == null) {
          failed++;
          store.log("Folder tidak terbaca: " + relative);
        } else for (File child : children) pending.push(child);
      } else if (canonical.isFile())
        try {
          store.importStream(
              "Penyimpanan bersama/" + relative,
              new FileInputStream(canonical),
              android.net.Uri.fromFile(canonical).toString(),
              password);
          count++;
        } catch (Exception error) {
          if (error instanceof InterruptedException) throw error;
          failed++;
          store.log("File tidak terbaca: " + relative + " — " + error.getClass().getSimpleName());
        }
    }
    if (failed > 0)
      throw new IOException(
          count + " file diproses, " + failed + " gagal/dibatasi. Sumber sehat tetap tersimpan.");
    return count;
  }
}
