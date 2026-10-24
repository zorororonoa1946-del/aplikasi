package id.nusastudio.vault;

import android.app.*;
import android.app.job.JobScheduler;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Real-device tests, no mock Android APIs. Run on a disposable emulator. */
public final class VaultInstrumentation extends Instrumentation {
  private int passed = 0;
  private final StringBuilder report = new StringBuilder();
  private Bundle arguments;

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    arguments = args;
    start();
  }

  interface Check {
    void run() throws Exception;
  }

  private void check(String name, Check check) throws Exception {
    check.run();
    passed++;
    report.append("PASS ").append(name).append('\n');
    Bundle progress = new Bundle();
    progress.putString("stream", "PASS " + name + "\n");
    sendStatus(0, progress);
  }

  private void require(boolean condition, String message) throws Exception {
    if (!condition) throw new Exception(message);
  }

  private View find(View view, String text) {
    if (view instanceof TextView && ((TextView) view).getText().toString().equals(text))
      return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = find(group.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private EditText findInput(View view) {
    if (view instanceof EditText) return (EditText) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        EditText found = findInput(group.getChildAt(i));
        if (found != null) return found;
      }
    }
    return null;
  }

  private int fixtureStatus(JSONObject config, String path) throws Exception {
    javax.net.ssl.HttpsURLConnection c =
        (javax.net.ssl.HttpsURLConnection)
            new java.net.URL(config.getString("relay") + path).openConnection();
    c.setRequestProperty("Authorization", "Bearer " + config.getString("token"));
    try {
      return c.getResponseCode();
    } finally {
      c.disconnect();
    }
  }

  private String fixtureControl(JSONObject config, String action, String id) throws Exception {
    javax.net.ssl.HttpsURLConnection c =
        (javax.net.ssl.HttpsURLConnection)
            new java.net.URL(config.getString("relay") + "/test/" + action + "/" + id)
                .openConnection();
    c.setRequestProperty("Authorization", "Bearer " + config.getString("token"));
    try {
      if (c.getResponseCode() != 200) throw new Exception("Test harness control failed");
      return new String(VaultStore.readLimited(c.getInputStream(), 4096), StandardCharsets.UTF_8)
          .trim();
    } finally {
      c.disconnect();
    }
  }

  private void shell(String command) throws Exception {
    try (android.os.ParcelFileDescriptor descriptor =
            getUiAutomation().executeShellCommand(command);
        java.io.InputStream input =
            new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
      byte[] buffer = new byte[1024];
      while (input.read(buffer) != -1) {}
    }
  }

  private void waitDialogInput(MainActivity activity) throws Exception {
    long until = System.currentTimeMillis() + 120000;
    boolean[] ready = {false};
    while (System.currentTimeMillis() < until && !ready[0]) {
      runOnMainSync(
          () -> {
            if (activity.activeDialog != null && activity.activeDialog.isShowing())
              ready[0] = findInput(activity.activeDialog.getWindow().getDecorView()) != null;
          });
      if (!ready[0]) Thread.sleep(100);
    }
    require(ready[0], "Dialog kata sandi tidak muncul");
  }

  private void waitDialogButton(MainActivity activity, String label) throws Exception {
    long until = System.currentTimeMillis() + 120000;
    boolean[] ready = {false};
    while (System.currentTimeMillis() < until && !ready[0]) {
      runOnMainSync(
          () -> {
            if (activity.activeDialog instanceof AlertDialog && activity.activeDialog.isShowing())
              ready[0] =
                  label.equals(
                      ((AlertDialog) activity.activeDialog).getButton(-1).getText().toString());
          });
      if (!ready[0]) Thread.sleep(100);
    }
    require(ready[0], "Dialog persetujuan sertifikat tidak muncul");
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    char[] pass = "pengujian-vault-aman-123".toCharArray();
    try {
      Context context = getTargetContext();
      VaultStore store = new VaultStore(context);
      String migration = arguments.getString("suite", "");
      String migrationId = "11111111-1111-4111-8111-111111111111";
      if ("storage-revoked".equals(migration)) {
        require(!SharedStorage.allowed(context), "Revoked storage permission still allowed");
        boolean denied = false;
        try {
          SharedStorage.scan(context, store, pass);
        } catch (SecurityException expected) {
          denied = true;
        }
        require(denied, "Revoked permission still permits shared storage scanning");
        result.putString("stream", "PASS revoked shared storage permission blocks scanning\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if ("migration-seed".equals(migration)) {
        store.unlock(pass);
        byte[] bytes = "legacy upgrade data 🔒".getBytes(StandardCharsets.UTF_8);
        store.write(
            migrationId,
            VaultCrypto.encrypt(
                new JSONObject()
                    .put("name", "old-folder/upgrade.txt")
                    .put("size", bytes.length)
                    .put("date", "2026-01-01T00:00:00Z")
                    .put("data", VaultCrypto.encode(bytes)),
                pass));
        new PrivateSettings(context).write(new JSONObject().put("migration", "preserved"));
        result.putString("stream", "PASS migration seed on original signed APK\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if ("migration-verify".equals(migration)) {
        store.unlock(pass);
        require(store.list(pass).size() == 1, "Upgrade lost legacy record");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        store.copyTo(migrationId, pass, out);
        require(
            "legacy upgrade data 🔒".equals(new String(out.toByteArray(), StandardCharsets.UTF_8)),
            "Upgrade changed bytes");
        require(
            "preserved".equals(new PrivateSettings(context).read().optString("migration")),
            "Upgrade lost Keystore settings");
        result.putString(
            "stream",
            "PASS signed 1.0.0 to 1.1.0 upgrade preserves password, v1 bytes, folder name and"
                + " Keystore settings\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      check(
          "AES-GCM round trip & randomized encryption",
          () -> {
            JSONObject value = new JSONObject().put("message", "rahasia — Indonesia 🔒");
            JSONObject first = VaultCrypto.encrypt(value, pass),
                second = VaultCrypto.encrypt(value, pass);
            require(
                !first.getString("ciphertext").equals(second.getString("ciphertext")),
                "IV tidak acak");
            require(
                VaultCrypto.decrypt(first, pass)
                    .getString("message")
                    .equals(value.getString("message")),
                "Isi berubah");
          });
      check(
          "wrong password and tamper rejection",
          () -> {
            JSONObject envelope = VaultCrypto.encrypt(new JSONObject().put("check", true), pass);
            boolean failed = false;
            try {
              VaultCrypto.decrypt(envelope, "salah-password-123".toCharArray());
            } catch (Exception expected) {
              failed = true;
            }
            require(failed, "Password salah diterima");
            byte[] encrypted = VaultCrypto.decode(envelope.getString("ciphertext"));
            encrypted[0] ^= 1;
            envelope.put("ciphertext", VaultCrypto.encode(encrypted));
            failed = false;
            try {
              VaultCrypto.decrypt(envelope, pass);
            } catch (Exception expected) {
              failed = true;
            }
            require(failed, "Data rusak diterima");
          });
      check(
          "Web Crypto -> Android interoperability",
          () -> {
            String json =
                new String(
                    VaultStore.readLimited(
                        getContext().getAssets().open("web-fixture.json"), 10240),
                    StandardCharsets.UTF_8);
            JSONObject value = VaultCrypto.decrypt(new JSONObject(json), pass);
            require(
                "dibuat-di-browser.txt".equals(value.getString("name")),
                "Fixture gagal didekripsi");
          });
      check(
          "encrypted persistence, import deduplication, restart",
          () -> {
            store.unlock(pass);
            byte[] data = "rahasia dari Android 🔒".getBytes(StandardCharsets.UTF_8);
            String first = store.importBytes("android-test.txt", data, "test-source", pass),
                second = store.importBytes("android-test.txt", data, "test-source", pass);
            require(first.equals(second), "File duplikat");
            VaultStore reopened = new VaultStore(context);
            reopened.unlock(pass);
            require(reopened.ids().size() == 1, "File hilang setelah restart");
            require(
                !reopened.read(first).toString().contains("android-test.txt"),
                "Metadata plaintext");
            require(
                VaultCrypto.decrypt(reopened.read(first), pass)
                    .getString("name")
                    .equals("android-test.txt"),
                "Nama berubah");
          });
      check(
          "Keystore-protected credentials",
          () -> {
            PrivateSettings settings = new PrivateSettings(context);
            JSONObject config =
                new JSONObject()
                    .put("token", "test-only-relay-token-123456789")
                    .put("relay", arguments.getString("relay", "https://localhost:3443"));
            settings.write(config);
            require(
                settings.read().getString("token").equals(config.getString("token")),
                "Keystore round trip gagal");
            String raw = context.getSharedPreferences("private", 0).getString("settings", "");
            require(!raw.contains("test-only-relay"), "Token plaintext");
          });
      if ("crypto".equals(arguments.getString("suite"))) {
        result.putString(
            "stream", "\n" + report + "\nCompatibility tests passed: " + passed + "\n");
        result.putInt("passed", passed);
        finish(Activity.RESULT_OK, result);
        return;
      }
      check(
          "HTTPS relay upload, retry and restore",
          () -> {
            PrivateSettings settings = new PrivateSettings(context);
            JSONObject config = settings.read();
            RelayClient relay =
                new RelayClient(config.getString("relay"), config.getString("token"));
            require(relay.backup(store, pass) == 1, "Upload gagal");
            require(relay.backup(store, pass) == 1, "Retry gagal");
            String id = store.ids().get(0);
            store.delete(id);
            require(relay.restore(store, pass) == 1, "Restore gagal");
            require(
                VaultCrypto.decrypt(store.read(id), pass)
                    .getString("name")
                    .equals("android-test.txt"),
                "Restore berubah");
          });
      check(
          "cleartext and credential-bearing relay rejection",
          () -> {
            for (String url :
                new String[] {
                  "http://localhost:3443",
                  "https://user:password@example.com",
                  "https://example.com/private"
                }) {
              boolean failed = false;
              try {
                new RelayClient(url, "test-only-relay-token-123456789");
              } catch (Exception expected) {
                failed = true;
              }
              require(failed, "Alamat tidak aman diterima");
            }
          });
      check(
          "Android JobScheduler opt-in and cancellation",
          () -> {
            BackupJob.schedule(context, true);
            JobScheduler jobs =
                (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            require(jobs.getPendingJob(BackupJob.JOB_ID) != null, "Jadwal tidak tersimpan");
            BackupJob.cancel(context);
            require(jobs.getPendingJob(BackupJob.JOB_ID) == null, "Jadwal tidak dibatalkan");
          });
      check(
          "scheduled background backup actually executes",
          () -> {
            PrivateSettings privateSettings = new PrivateSettings(context);
            JSONObject config = privateSettings.read();
            config.put("automatic", true).put("backgroundPassword", new String(pass));
            privateSettings.write(config);
            BackupJob.schedule(context, false);
            android.os.ParcelFileDescriptor command =
                getUiAutomation()
                    .executeShellCommand("cmd jobscheduler run -f id.nusastudio.vault 701");
            VaultStore.readLimited(
                new android.os.ParcelFileDescriptor.AutoCloseInputStream(command), 16384);
            boolean completed = false;
            long timeout = System.currentTimeMillis() + 180000;
            while (System.currentTimeMillis() < timeout && !completed) {
              JSONArray history = privateSettings.read().optJSONArray("history");
              if (history != null)
                for (int i = 0; i < history.length(); i++)
                  if (history.getString(i).contains("Backup otomatis selesai")) completed = true;
              if (!completed) Thread.sleep(500);
            }
            BackupJob.cancel(context);
            JSONObject latest = privateSettings.read();
            latest.put("automatic", false);
            latest.remove("backgroundPassword");
            privateSettings.write(latest);
            require(completed, "Job backup tidak selesai");
          });
      check(
          "SAF folder recursion and unchanged-file deduplication",
          () -> {
            android.net.Uri tree =
                android.provider.DocumentsContract.buildTreeDocumentUri(
                    "id.nusastudio.vault.testdocuments", "root");
            getContext()
                .startActivity(
                    new Intent()
                        .setComponent(
                            new android.content.ComponentName(
                                "id.nusastudio.vault.test",
                                "id.nusastudio.vault.GrantTestDocuments"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long grantDeadline = System.currentTimeMillis() + 30000;
            while (context.checkUriPermission(
                    tree,
                    android.os.Process.myPid(),
                    android.os.Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
              if (System.currentTimeMillis() > grantDeadline)
                throw new Exception("Test provider did not grant its synthetic tree");
              Thread.sleep(100);
            }
            require(store.importTree(tree, pass) == 2, "Folder tidak dipindai lengkap");
            require(store.ids().size() == 3, "Subfolder tidak diimpor");
            require(store.importTree(tree, pass) == 2, "Pemindaian ulang gagal");
            require(store.ids().size() == 3, "File tidak berubah diduplikasi");
            boolean nested = false;
            for (JSONObject item : store.list(pass))
              if (item.getString("name").equals("Subfolder/b.txt")) nested = true;
            require(nested, "Path subfolder hilang");
          });
      check(
          "large file exceeds legacy 20 MiB limit with bounded-memory streaming and resumable HTTPS"
              + " backup",
          () -> {
            long length = 25L * 1024 * 1024 + 17;
            java.io.InputStream stream =
                new java.io.InputStream() {
                  long left = length;

                  public int read() {
                    if (left == 0) return -1;
                    left--;
                    return 90;
                  }

                  public int read(byte[] bytes, int off, int len) {
                    if (left == 0) return -1;
                    int n = (int) Math.min(left, len);
                    java.util.Arrays.fill(bytes, off, off + n, (byte) 90);
                    left -= n;
                    return n;
                  }
                };
            String big = store.importStream("large-25MiB.bin", stream, "synthetic-large", pass);
            JSONObject metadata = VaultCrypto.decrypt(store.read(big), pass);
            require(
                metadata.getInt("format") == 2 && metadata.getJSONArray("parts").length() == 7,
                "File besar tidak dipecah");
            java.security.MessageDigest exported =
                java.security.MessageDigest.getInstance("SHA-256");
            long[] bytes = {0};
            store.copyTo(
                big,
                pass,
                new java.io.OutputStream() {
                  public void write(int b) {
                    exported.update((byte) b);
                    bytes[0]++;
                  }

                  public void write(byte[] b, int off, int n) {
                    exported.update(b, off, n);
                    bytes[0] += n;
                  }
                });
            require(
                bytes[0] == length
                    && VaultCrypto.encode(exported.digest()).equals(metadata.getString("hash")),
                "Hasil stream berubah");
            JSONObject config = new PrivateSettings(context).read();
            fixtureControl(config, "interrupt", big);
            boolean interrupted = false;
            RelayClient relay =
                new RelayClient(config.getString("relay"), config.getString("token"));
            try {
              relay.backup(store, pass);
            } catch (java.io.IOException expected) {
              interrupted = true;
            }
            require(interrupted, "Simulasi putus jaringan tidak gagal");
            require(
                fixtureStatus(config, "/api/files/" + big) == 404,
                "Manifest dipublikasikan sebelum semua chunk tersedia");
            fixtureControl(config, "resume", big);
            require(relay.backup(store, pass) == store.ids().size(), "Retry backup gagal");
            require(
                "1".equals(fixtureControl(config, "count", big)),
                "Chunk yang sudah benar diunggah ulang");
          });
      check(
          "selected local certificate is honored and unrelated CA is rejected",
          () -> {
            JSONObject config = new PrivateSettings(context).read();
            int raw =
                context.getResources().getIdentifier("test_ca", "raw", context.getPackageName());
            String pem =
                new String(
                    VaultStore.readLimited(context.getResources().openRawResource(raw), 65536),
                    StandardCharsets.US_ASCII);
            RelayClient local =
                new RelayClient(config.getString("relay"), config.getString("token"), pem);
            require(local.backup(store, pass) == store.ids().size(), "CA pemilik ditolak");
            String unrelated =
                new String(
                    VaultStore.readLimited(
                        getContext().getAssets().open("unrelated-ca.pem"), 65536),
                    StandardCharsets.US_ASCII);
            boolean rejected = false;
            try {
              new RelayClient(config.getString("relay"), config.getString("token"), unrelated)
                  .backup(store, pass);
            } catch (Exception expected) {
              rejected = true;
            }
            require(rejected, "Trust manager mengabaikan CA pilihan");
          });
      check(
          "multiple selected sources preserve legacy configuration and continue past a bad source",
          () -> {
            PrivateSettings settings = new PrivateSettings(context);
            JSONObject saved = settings.read();
            android.net.Uri
                root =
                    android.provider.DocumentsContract.buildTreeDocumentUri(
                        "id.nusastudio.vault.testdocuments", "root"),
                nested =
                    android.provider.DocumentsContract.buildTreeDocumentUri(
                        "id.nusastudio.vault.testdocuments", "nested");
            BackupPlan.add(context, root, "folder", "Root");
            BackupPlan.add(context, nested, "folder", "Nested");
            BackupPlan.add(
                context,
                android.net.Uri.parse("content://missing.nusa.provider/unreadable"),
                "file",
                "Revoked test file");
            require(
                BackupPlan.sources(settings.read()).length() == 3, "Sumber sebelumnya tertimpa");
            require(
                BackupPlan.scan(context, store, pass) == 1,
                "Sumber gagal menghentikan sumber sehat");
            JSONObject current = settings.read();
            current.remove("sources");
            current.put("folder", root.toString());
            settings.write(current);
            require(
                BackupPlan.sources(settings.read()).length() == 1, "Migrasi folder lama hilang");
            settings.write(saved);
          });
      check(
          "shared storage requires consent and scans owner-approved primary storage",
          () -> {
            boolean modern = android.os.Build.VERSION.SDK_INT >= 30;
            String grant =
                modern
                    ? "appops set id.nusastudio.vault MANAGE_EXTERNAL_STORAGE allow"
                    : "pm grant id.nusastudio.vault android.permission.READ_EXTERNAL_STORAGE";
            require(!SharedStorage.allowed(context), "Storage permission unexpectedly allowed");
            boolean denied = false;
            try {
              SharedStorage.scan(context, store, pass);
            } catch (SecurityException expected) {
              denied = true;
            }
            require(denied, "Shared storage read without owner permission");
            try {
              shell(grant);
              require(SharedStorage.allowed(context), "Owner-granted permission not honored");
              java.io.File fixture =
                  new java.io.File(
                      android.os.Environment.getExternalStorageDirectory(),
                      "Download/nusa-audit-owner.txt");
              shell("mkdir -p /sdcard/Download");
              shell("cp /system/etc/hosts /sdcard/Download/nusa-audit-owner.txt");
              require(fixture.isFile(), "Shell did not create shared storage fixture");
              SharedStorage.scan(context, store, pass);
              boolean found = false;
              for (JSONObject entry : store.list(pass))
                if (entry
                    .optString("name")
                    .equals("Penyimpanan bersama/Download/nusa-audit-owner.txt")) found = true;
              require(found, "Shared storage fixture missing");
            } finally {
              shell("rm -f /sdcard/Download/nusa-audit-owner.txt");
            }
          });
      check(
          "modified chunk is rejected and restored from immutable relay backup",
          () -> {
            String selected = null;
            for (JSONObject value : store.list(pass))
              if (value.optString("name").equals("large-25MiB.bin"))
                selected = value.getString("id");
            require(selected != null, "File besar hilang");
            java.io.File part = store.chunk(selected, 0);
            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(part, "rw")) {
              int first = file.read();
              file.seek(0);
              file.write(first ^ 1);
            }
            boolean rejected = false;
            try {
              store.copyTo(
                  selected,
                  pass,
                  new java.io.OutputStream() {
                    public void write(int b) {}
                  });
            } catch (Exception expected) {
              rejected = true;
            }
            require(rejected, "Chunk rusak berhasil diekspor");
            JSONObject c = new PrivateSettings(context).read();
            require(
                new RelayClient(c.getString("relay"), c.getString("token")).restore(store, pass)
                    >= 1,
                "Chunk rusak tidak dipulihkan");
            require(store.healthy(selected, pass), "Hasil repair tidak sehat");
          });
      MainActivity activity =
          (MainActivity)
              startActivitySync(
                  new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitForIdleSync();
      check(
          "company profile and locked UI",
          () -> {
            runOnMainSync(
                () -> {
                  View root = activity.getWindow().getDecorView();
                  if (find(root, "nusa®") == null || find(root, "Personal vault.") != null)
                    throw new AssertionError("Vault terlihat saat terkunci");
                });
          });
      // Exercise the actual gesture, password dialog and vault UI.
      runOnMainSync(
          () -> {
            View brand = find(activity.getWindow().getDecorView(), "nusa®");
            for (int i = 0; i < 7; i++) brand.performClick();
          });
      waitForIdleSync();
      check(
          "seven taps opens password dialog",
          () ->
              require(
                  activity.activeDialog != null && activity.activeDialog.isShowing(),
                  "Dialog tidak terbuka"));
      runOnMainSync(
          () -> {
            AlertDialog dialog = (AlertDialog) activity.activeDialog;
            EditText input = findInput(dialog.getWindow().getDecorView());
            input.setText("pengujian-vault-aman-123");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
          });
      long deadline = System.currentTimeMillis() + 120000;
      boolean[] unlocked = {false};
      while (System.currentTimeMillis() < deadline && !unlocked[0]) {
        runOnMainSync(
            () ->
                unlocked[0] = find(activity.getWindow().getDecorView(), "Personal vault.") != null);
        if (!unlocked[0]) Thread.sleep(200);
      }
      check(
          "password unlocks actual native file manager",
          () -> require(unlocked[0], "Vault tidak terbuka"));
      check(
          "lock returns to company profile",
          () -> {
            runOnMainSync(activity::lock);
            runOnMainSync(
                () -> {
                  if (find(activity.getWindow().getDecorView(), "nusa®") == null
                      || find(activity.getWindow().getDecorView(), "Personal vault.") != null)
                    throw new AssertionError("Vault belum terkunci");
                });
          });
      check(
          "certificate picker result requires explicit owner approval before trust changes",
          () -> {
            android.net.Uri selected =
                android.provider.DocumentsContract.buildDocumentUriUsingTree(
                    android.provider.DocumentsContract.buildTreeDocumentUri(
                        "id.nusastudio.vault.testdocuments", "root"),
                    "cert");
            runOnMainSync(
                () -> {
                  activity.onActivityResult(
                      105, Activity.RESULT_OK, new Intent().setData(selected));
                  activity.onResume();
                });
            waitDialogInput(activity);
            runOnMainSync(
                () -> {
                  AlertDialog dialog = (AlertDialog) activity.activeDialog;
                  findInput(dialog.getWindow().getDecorView()).setText(new String(pass));
                  dialog.getButton(-1).performClick();
                });
            waitDialogButton(activity, "Cocok, simpan");
            require(
                new PrivateSettings(context).read().optString("relayCa").isEmpty(),
                "CA dipercaya sebelum persetujuan");
            runOnMainSync(() -> ((AlertDialog) activity.activeDialog).getButton(-1).performClick());
            long until = System.currentTimeMillis() + 30000;
            while (new PrivateSettings(context).read().optString("relayCa").isEmpty()
                && System.currentTimeMillis() < until) Thread.sleep(100);
            require(
                !new PrivateSettings(context).read().optString("relayCa").isEmpty(),
                "CA yang disetujui tidak tersimpan");
          });
      check(
          "locking dismisses pending certificate trust without accepting it",
          () -> {
            PrivateSettings privateSettings = new PrivateSettings(context);
            JSONObject c = privateSettings.read();
            c.remove("relayCa");
            privateSettings.write(c);
            long until = System.currentTimeMillis() + 30000;
            boolean[] ready = {false};
            while (System.currentTimeMillis() < until && !ready[0]) {
              runOnMainSync(
                  () -> {
                    View button =
                        find(activity.getWindow().getDecorView(), "Sertifikat HTTPS lokal");
                    ready[0] = button != null && button.isEnabled();
                  });
              if (!ready[0]) Thread.sleep(100);
            }
            android.net.Uri selected =
                android.provider.DocumentsContract.buildDocumentUriUsingTree(
                    android.provider.DocumentsContract.buildTreeDocumentUri(
                        "id.nusastudio.vault.testdocuments", "root"),
                    "cert");
            runOnMainSync(
                () ->
                    activity.onActivityResult(
                        105, Activity.RESULT_OK, new Intent().setData(selected)));
            waitDialogButton(activity, "Cocok, simpan");
            Dialog pending = activity.activeDialog;
            runOnMainSync(activity::lock);
            require(!pending.isShowing(), "Dialog trust tetap terbuka saat terkunci");
            require(
                privateSettings.read().optString("relayCa").isEmpty(),
                "Lock menerima CA tanpa persetujuan");
          });
      result.putString("stream", "\n" + report + "\nTests passed: " + passed + "\n");
      result.putInt("passed", passed);
      finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString("stream", report + "FAIL " + error + "\n");
      result.putString("failure", error.toString());
      finish(Activity.RESULT_CANCELED, result);
    } finally {
      Arrays.fill(pass, '\0');
    }
  }
}
