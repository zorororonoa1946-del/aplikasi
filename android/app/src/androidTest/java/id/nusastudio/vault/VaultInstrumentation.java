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

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    char[] pass = "pengujian-vault-aman-123".toCharArray();
    try {
      Context context = getTargetContext();
      VaultStore store = new VaultStore(context);
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
            require(relay.backup(store) == 1, "Upload gagal");
            require(relay.backup(store) == 1, "Retry gagal");
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
