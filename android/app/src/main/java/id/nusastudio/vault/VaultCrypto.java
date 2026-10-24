package id.nusastudio.vault;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

public final class VaultCrypto {
  public static final int ITERATIONS = 600_000;
  private static final SecureRandom RANDOM = new SecureRandom();

  private static byte[] random(int length) {
    byte[] value = new byte[length];
    RANDOM.nextBytes(value);
    return value;
  }

  static String encode(byte[] value) {
    return Base64.encodeToString(value, Base64.NO_WRAP);
  }

  static byte[] decode(String value) {
    return Base64.decode(value, Base64.NO_WRAP);
  }

  private static SecretKeySpec key(char[] password, byte[] salt) throws Exception {
    byte[] encoded = new String(password).getBytes(StandardCharsets.UTF_8);
    byte[] raw = null;
    try {
      raw = NativeKdf.derive(encoded, salt);
      return new SecretKeySpec(raw, "AES");
    } finally {
      Arrays.fill(encoded, (byte) 0);
      if (raw != null) Arrays.fill(raw, (byte) 0);
    }
  }

  public static JSONObject encrypt(JSONObject value, char[] password) throws Exception {
    byte[] salt = random(16), iv = random(12);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), new GCMParameterSpec(128, iv));
    byte[] plain = value.toString().getBytes(StandardCharsets.UTF_8);
    try {
      return new JSONObject()
          .put("version", 1)
          .put("salt", encode(salt))
          .put("iv", encode(iv))
          .put("ciphertext", encode(cipher.doFinal(plain)));
    } finally {
      Arrays.fill(plain, (byte) 0);
    }
  }

  public static JSONObject decrypt(JSONObject envelope, char[] password) throws Exception {
    if (envelope.getInt("version") != 1) throw new Exception("Versi backup tidak didukung");
    byte[] salt = decode(envelope.getString("salt")),
        iv = decode(envelope.getString("iv")),
        data = decode(envelope.getString("ciphertext"));
    if (salt.length != 16 || iv.length != 12 || data.length < 16)
      throw new Exception("Format enkripsi salah");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key(password, salt), new GCMParameterSpec(128, iv));
    byte[] plain = cipher.doFinal(data);
    try {
      return new JSONObject(new String(plain, StandardCharsets.UTF_8));
    } finally {
      Arrays.fill(plain, (byte) 0);
    }
  }
}
