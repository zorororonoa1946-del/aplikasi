package id.nusastudio.vault;

import android.content.Context;
import android.net.Uri;
import org.json.*;

/**
 * Owner-selected sources, persisted encrypted; failures never prevent healthy sources uploading.
 */
public final class BackupPlan {
  public static JSONArray sources(JSONObject config) throws Exception {
    JSONArray sources = config.optJSONArray("sources");
    if (sources != null) return sources;
    sources = new JSONArray();
    String legacy = config.optString("folder");
    if (!legacy.isEmpty())
      sources.put(
          new JSONObject()
              .put("uri", legacy)
              .put("kind", "folder")
              .put("name", "Folder sebelumnya"));
    return sources;
  }

  public static void add(Context context, Uri uri, String kind, String name) throws Exception {
    synchronized (PrivateSettings.class) {
      PrivateSettings settings = new PrivateSettings(context);
      JSONObject config = settings.read();
      JSONArray sources = sources(config);
      for (int i = 0; i < sources.length(); i++)
        if (sources.getJSONObject(i).getString("uri").equals(uri.toString())) return;
      sources.put(new JSONObject().put("uri", uri.toString()).put("kind", kind).put("name", name));
      config.put("sources", sources);
      config.remove("folder");
      settings.write(config);
    }
  }

  public static void remove(Context context, int index) throws Exception {
    synchronized (PrivateSettings.class) {
      PrivateSettings settings = new PrivateSettings(context);
      JSONObject config = settings.read();
      JSONArray old = sources(config), next = new JSONArray();
      String uri = old.getJSONObject(index).getString("uri");
      for (int i = 0; i < old.length(); i++) if (i != index) next.put(old.get(i));
      config.put("sources", next);
      config.remove("folder");
      settings.write(config);
      try {
        context
            .getContentResolver()
            .releasePersistableUriPermission(
                Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
      } catch (SecurityException | IllegalArgumentException ignored) {
      }
    }
  }

  public static int scan(Context context, VaultStore store, char[] password) throws Exception {
    JSONArray sources = sources(new PrivateSettings(context).read());
    int failures = 0;
    for (int i = 0; i < sources.length(); i++) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
      JSONObject source = sources.getJSONObject(i);
      try {
        Uri uri = Uri.parse(source.getString("uri"));
        if ("shared".equals(source.getString("kind"))) SharedStorage.scan(context, store, password);
        else if ("folder".equals(source.getString("kind"))) store.importTree(uri, password);
        else store.importUri(uri, null, password);
      } catch (Exception e) {
        if (e instanceof InterruptedException) throw e;
        failures++;
        store.log(
            "Sumber gagal: " + source.optString("name") + " — " + e.getClass().getSimpleName());
      }
    }
    return failures;
  }
}
