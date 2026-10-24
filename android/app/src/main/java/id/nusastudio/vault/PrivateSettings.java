package id.nusastudio.vault;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Values stay in the app sandbox, encrypted with a non-exportable Android Keystore key. */
public final class PrivateSettings {
  private final Context context;

  public PrivateSettings(Context context) {
    this.context = context.getApplicationContext();
  }

  private SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (!store.containsAlias("nusa-settings")) {
      KeyGenerator generator =
          KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      generator.init(
          new KeyGenParameterSpec.Builder(
                  "nusa-settings", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      generator.generateKey();
    }
    return (SecretKey) store.getKey("nusa-settings", null);
  }

  public synchronized JSONObject read() throws Exception {
    String saved = context.getSharedPreferences("private", 0).getString("settings", null);
    if (saved == null) return new JSONObject();
    JSONObject value = new JSONObject(saved);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, VaultCrypto.decode(value.getString("iv"))));
    return new JSONObject(
        new String(
            cipher.doFinal(VaultCrypto.decode(value.getString("data"))), StandardCharsets.UTF_8));
  }

  public synchronized void write(JSONObject value) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    JSONObject saved =
        new JSONObject()
            .put("iv", VaultCrypto.encode(cipher.getIV()))
            .put(
                "data",
                VaultCrypto.encode(
                    cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8))));
    if (!context
        .getSharedPreferences("private", 0)
        .edit()
        .putString("settings", saved.toString())
        .commit()) throw new Exception("Gagal menyimpan pengaturan");
  }

  public void log(String message) {
    synchronized (PrivateSettings.class) {
      try {
        JSONObject value = read();
        org.json.JSONArray old = value.optJSONArray("history"), next = new org.json.JSONArray();
        next.put(
            new java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.ROOT)
                    .format(new java.util.Date())
                + " · "
                + message);
        if (old != null) for (int i = 0; i < Math.min(old.length(), 499); i++) next.put(old.get(i));
        value.put("history", next);
        write(value);
      } catch (Exception ignored) {
        android.util.Log.w("NusaVault", "Tidak dapat menyimpan riwayat");
      }
    }
  }
}
