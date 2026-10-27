package id.nusastudio.vault;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public class MainActivity extends Activity {
  private final int GREEN = Color.rgb(25, 61, 52), BACKGROUND = Color.rgb(247, 246, 240);
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private VaultStore store;
  private PrivateSettings settings;
  private char[] password;
  private LinearLayout page, files;
  private TextView status;
  private EditText search;
  private List<JSONObject> entries = new ArrayList<>();
  private boolean busy = false;
  private boolean foreground = false;
  Dialog activeDialog;
  private int taps = 0, session = 0;
  private long lastTap = 0, lastInteraction = 0;
  private String exportId;
  private String folderPath = "";
  private Uri pendingCertificate;
  private boolean pendingShared;
  private final ArrayList<Uri> pendingUris = new ArrayList<>();
  private Uri pendingTree, pendingExport;
  private final Runnable idle =
      new Runnable() {
        public void run() {
          if (password != null && System.currentTimeMillis() - lastInteraction > 180000) lock();
          handler.postDelayed(this, 1000);
        }
      };

  interface Work {
    String run(char[] pass) throws Exception;
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow()
        .setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
    store = new VaultStore(this);
    settings = new PrivateSettings(this);
    showCompany();
    handler.post(idle);
    executor.execute(
        () -> {
          try {
            JSONObject config = settings.read();
            if (config.optBoolean("automatic"))
              BackupJob.schedule(this, config.optBoolean("wifi", true));
          } catch (Exception ignored) {
          }
        });
  }

  @Override
  public void onUserInteraction() {
    super.onUserInteraction();
    lastInteraction = System.currentTimeMillis();
  }

  @Override
  protected void onStop() {
    super.onStop();
    foreground = false;
    if (activeDialog != null) {
      activeDialog.dismiss();
      activeDialog = null;
    }
    lock();
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacks(idle);
    executor.shutdownNow();
    if (password != null) Arrays.fill(password, '\0');
    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();
    foreground = true;
    if (!pendingUris.isEmpty()
        || pendingTree != null
        || pendingExport != null
        || pendingCertificate != null
        || pendingShared) handler.postDelayed(this::askPassword, 250);
  }

  private int dp(int n) {
    return (int) (n * getResources().getDisplayMetrics().density);
  }

  private void layout() {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(BACKGROUND);
    page = new LinearLayout(this);
    page.setOrientation(LinearLayout.VERTICAL);
    page.setPadding(dp(24), dp(24), dp(24), dp(40));
    scroll.addView(page);
    setContentView(scroll);
    if (Build.VERSION.SDK_INT >= 35)
      page.setOnApplyWindowInsetsListener(
          (v, i) -> {
            android.graphics.Insets bars = i.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(dp(24), dp(24) + bars.top, dp(24), dp(40) + bars.bottom);
            return i;
          });
  }

  private TextView text(String value, int size) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(GREEN);
    view.setPadding(0, dp(8), 0, dp(8));
    page.addView(view);
    return view;
  }

  private Button button(String label, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setTag(label.equals("Kunci vault") ? "lock" : "action");
    b.setAllCaps(false);
    b.setTextColor(GREEN);
    b.setOnClickListener(v -> action.run());
    page.addView(b, new LinearLayout.LayoutParams(-1, dp(52)));
    return b;
  }

  private void space() {
    text("", 12);
  }

  private void showCompany() {
    taps = 0;
    setContentView(
        new ServiceHome(
                this,
                () -> {
                  long now = System.currentTimeMillis();
                  if (now - lastTap > 3000) taps = 0;
                  lastTap = now;
                  if (++taps == 7) {
                    taps = 0;
                    askPassword();
                  }
                })
            .create());
  }

  void lock() {
    session++;
    if (!executor.isShutdown()) executor.execute(store::clearSession);
    if (activeDialog != null) {
      activeDialog.dismiss();
      activeDialog = null;
    }
    if (password != null) Arrays.fill(password, '\0');
    password = null;
    entries.clear();
    folderPath = "";
    showCompany();
  }

  private void askPassword() {
    if (isFinishing()
        || !foreground
        || password != null
        || (activeDialog != null && activeDialog.isShowing())) return;
    EditText input = new EditText(this);
    input.setInputType(129);
    input.setHint("Minimal 12 karakter");
    input.setPadding(dp(20), dp(12), dp(20), dp(12));
    boolean creating = !store.initialized();
    EditText confirmation = new EditText(this);
    confirmation.setInputType(129);
    confirmation.setHint("Ulangi kata sandi");
    LinearLayout passwordForm = new LinearLayout(this);
    passwordForm.setOrientation(LinearLayout.VERTICAL);
    passwordForm.addView(input);
    if (creating) passwordForm.addView(confirmation);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(store.initialized() ? "Buka vault" : "Buat vault pribadi")
            .setMessage(
                "Gunakan kata sandi yang sama di laptop. Kata sandi yang hilang tidak dapat"
                    + " dipulihkan.")
            .setView(passwordForm)
            .setNegativeButton("Batal", (d, w) -> clearPending())
            .setPositiveButton("Buka", null)
            .create();
    dialog.setOnCancelListener(d -> clearPending());
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    v -> {
                      char[] pass = input.getText().toString().toCharArray();
                      if (creating
                          && !java.util.Arrays.equals(
                              pass, confirmation.getText().toString().toCharArray())) {
                        java.util.Arrays.fill(pass, '\0');
                        confirmation.setError("Kata sandi tidak sama");
                        return;
                      }
                      input.setText("");
                      confirmation.setText("");
                      if (pass.length < 12) {
                        Arrays.fill(pass, '\0');
                        input.setError("Minimal 12 karakter");
                        return;
                      }
                      dialog.getButton(-1).setEnabled(false);
                      int attempt = session;
                      executor.execute(
                          () -> {
                            try {
                              store.unlock(pass);
                              List<JSONObject> values = store.list(pass);
                              runOnUiThread(
                                  () -> {
                                    if (attempt != session || !foreground || !dialog.isShowing()) {
                                      Arrays.fill(pass, '\0');
                                      return;
                                    }
                                    password = pass;
                                    entries = values;
                                    session++;
                                    lastInteraction = System.currentTimeMillis();
                                    dialog.dismiss();
                                    showVault();
                                    processPending();
                                  });
                            } catch (Exception e) {
                              Arrays.fill(pass, '\0');
                              runOnUiThread(
                                  () -> {
                                    input.setError("Kata sandi salah atau data tidak dapat dibuka");
                                    dialog.getButton(-1).setEnabled(true);
                                  });
                            }
                          });
                    }));
    activeDialog = dialog;
    dialog.show();
  }

  private void clearPending() {
    pendingUris.clear();
    pendingTree = null;
    pendingExport = null;
    pendingCertificate = null;
    pendingShared = false;
    exportId = null;
  }

  private void showVault() {
    layout();
    text("RUANG PRIBADI ANDA", 11);
    text("Personal vault.", 36);
    text(entries.size() + " file · AES-256-GCM · Kunci otomatis 3 menit", 13);
    button("Kunci vault", this::lock);
    space();
    button("+ Tambah sumber file", () -> pick(false));
    button("+ Tambah sumber folder", () -> pick(true));
    button(
        "Backup sekarang",
        () ->
            work(
                pass -> {
                  JSONObject c = settings.read();
                  int failures = BackupPlan.scan(this, store, pass);
                  int count =
                      new RelayClient(
                              c.optString("relay"), c.optString("token"), c.optString("relayCa"))
                          .backup(store, pass);
                  if (failures > 0)
                    throw new IOException(
                        count + " file tersalin, " + failures + " sumber gagal. Periksa riwayat.");
                  return count + " file berhasil di-backup";
                }));
    button(
        "Pulihkan dari relay",
        () ->
            work(
                pass -> {
                  JSONObject c = settings.read();
                  int n =
                      new RelayClient(
                              c.optString("relay"), c.optString("token"), c.optString("relayCa"))
                          .restore(store, pass);
                  return n + " file dipulihkan";
                }));
    button("Pengaturan relay & backup otomatis", this::configure);
    button("Tambahkan seluruh penyimpanan bersama", this::selectShared);
    button("Kelola sumber otomatis", this::manageSources);
    button("Sertifikat HTTPS lokal", this::pickCertificate);
    button("Riwayat backup", this::history);
    status = text(busy ? "Memproses…" : "File tersimpan terenkripsi di perangkat ini.", 13);
    search = new EditText(this);
    search.setSingleLine(true);
    search.setHint("Cari nama file…");
    page.addView(search);
    files = new LinearLayout(this);
    files.setOrientation(LinearLayout.VERTICAL);
    page.addView(files);
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

          public void onTextChanged(CharSequence s, int a, int b, int c) {
            renderFiles(s.toString());
          }

          public void afterTextChanged(Editable e) {}
        });
    renderFiles("");
    applyBusy(page);
  }

  private void renderFiles(String query) {
    files.removeAllViews();
    if (query.isEmpty()) {
      TextView path = new TextView(this);
      path.setText(getString(R.string.folder_path, folderPath));
      files.addView(path);
      if (!folderPath.isEmpty()) {
        Button up = new Button(this);
        up.setText(R.string.parent_folder);
        up.setOnClickListener(
            v -> {
              String current = folderPath.substring(0, folderPath.length() - 1);
              int slash = current.lastIndexOf('/');
              folderPath = slash < 0 ? "" : current.substring(0, slash + 1);
              renderFiles("");
            });
        files.addView(up);
      }
      java.util.TreeSet<String> folders = new java.util.TreeSet<>();
      for (JSONObject v : entries) {
        String pathName = v.optString("name");
        if (pathName.startsWith(folderPath)) {
          String tail = pathName.substring(folderPath.length());
          int slash = tail.indexOf('/');
          if (slash >= 0) folders.add(tail.substring(0, slash));
        }
      }
      for (String name : folders) {
        Button folder = new Button(this);
        folder.setText(getString(R.string.folder_name, name));
        folder.setOnClickListener(
            v -> {
              folderPath += name + "/";
              renderFiles("");
            });
        files.addView(folder);
      }
    }
    for (JSONObject entry : entries) {
      String name = entry.optString("name");
      if (query.isEmpty()
          && (!name.startsWith(folderPath) || name.substring(folderPath.length()).contains("/")))
        continue;
      if (!name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) continue;
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.VERTICAL);
      row.setPadding(0, dp(16), 0, dp(12));
      TextView title = new TextView(this);
      title.setText(
          getString(R.string.file_details, name, entry.optLong("size"), entry.optString("date")));
      title.setTextColor(GREEN);
      title.setTextSize(15);
      row.addView(title);
      LinearLayout actions = new LinearLayout(this);
      Button save = new Button(this);
      save.setText(R.string.save_copy);
      save.setAllCaps(false);
      save.setOnClickListener(
          v -> {
            if (busy) return;
            exportId = entry.optString("id");
            Intent intent =
                new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .setType("application/octet-stream")
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_TITLE, new File(name).getName());

            startActivityForResult(intent, 103);
          });
      Button delete = new Button(this);
      delete.setText(R.string.delete_local);
      delete.setAllCaps(false);
      delete.setOnClickListener(
          v ->
              new AlertDialog.Builder(this)
                  .setMessage(
                      "Hapus salinan lokal? Salinan relay hanya ada jika backup sebelumnya"
                          + " berhasil. Pastikan pemulihan sudah diuji.")
                  .setNegativeButton("Batal", null)
                  .setPositiveButton(
                      "Hapus",
                      (d, w) ->
                          work(
                              pass -> {
                                store.delete(entry.getString("id"));
                                return "Salinan lokal dihapus";
                              }))
                  .show());
      actions.addView(save);
      actions.addView(delete);
      row.addView(actions);
      files.addView(row);
    }
    if (files.getChildCount() == 0) {
      TextView empty = new TextView(this);
      empty.setText(R.string.empty_files);
      empty.setPadding(0, dp(24), 0, dp(24));
      files.addView(empty);
    }
  }

  private void pick(boolean folder) {
    if (busy) return;
    Intent intent =
        new Intent(folder ? Intent.ACTION_OPEN_DOCUMENT_TREE : Intent.ACTION_OPEN_DOCUMENT);
    intent.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    if (!folder) {
      intent.setType("*/*");
      intent.addCategory(Intent.CATEGORY_OPENABLE);
      intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    }

    startActivityForResult(intent, folder ? 102 : 101);
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);

    if (result != RESULT_OK || data == null) {
      exportId = null;
      return;
    }
    if (request == 103) {
      pendingExport = data.getData();
    } else if (request == 105) {
      pendingCertificate = data.getData();
    } else if (request == 102) {
      pendingTree = data.getData();
      try {
        getContentResolver()
            .takePersistableUriPermission(pendingTree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      } catch (SecurityException e) {
        toast("Izin folder tidak dapat dipertahankan");
        pendingTree = null;
      }
    } else if (request == 101) {
      if (data.getClipData() != null)
        for (int i = 0; i < data.getClipData().getItemCount(); i++)
          pendingUris.add(data.getClipData().getItemAt(i).getUri());
      else if (data.getData() != null) pendingUris.add(data.getData());
    }
    if (request == 101)
      for (Uri uri : pendingUris)
        try {
          getContentResolver()
              .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
          BackupPlan.add(this, uri, "file", uri.getLastPathSegment());
        } catch (Exception e) {
          settings.log("File hanya diimpor sekali; penyedia tidak memberi izin persisten");
        }
    if (password != null) processPending();
  }

  private void processPending() {
    if (pendingShared) {
      pendingShared = false;
      if (!SharedStorage.allowed(this)) {
        toast("Izin penyimpanan tidak diberikan");
        return;
      }
      work(
          pass -> {
            BackupPlan.add(this, Uri.parse("shared://primary"), "shared", "Penyimpanan bersama");
            int count = SharedStorage.scan(this, store, pass);
            return count + " file penyimpanan bersama diproses";
          });
    } else if (pendingCertificate != null) {
      Uri uri = pendingCertificate;
      pendingCertificate = null;
      int current = session;
      work(
          pass -> {
            String pem =
                new String(
                    VaultStore.readLimited(getContentResolver().openInputStream(uri), 65536),
                    java.nio.charset.StandardCharsets.US_ASCII);
            String fingerprint = RelayClient.fingerprint(pem);
            runOnUiThread(
                () -> {
                  if (password == null || current != session) return;
                  activeDialog =
                      new AlertDialog.Builder(this)
                          .setTitle("Percayai sertifikat relay pribadi?")
                          .setMessage(
                              "Cocokkan SHA-256 dengan laptop Anda:\n"
                                  + fingerprint
                                  + "\n"
                                  + "Sertifikat hanya dipakai aplikasi ini untuk relay yang Anda"
                                  + " atur.")
                          .setNegativeButton("Batal", null)
                          .setPositiveButton(
                              "Cocok, simpan",
                              (dialog, which) -> {
                                if (password == null || current != session) return;
                                try {
                                  synchronized (PrivateSettings.class) {
                                    JSONObject config = settings.read();
                                    config.put("relayCa", pem);
                                    settings.write(config);
                                  }
                                  status.setText(R.string.certificate_saved);
                                } catch (Exception error) {
                                  toast("Sertifikat tidak tersimpan");
                                }
                              })
                          .show();
                });
            return "Sertifikat dibaca; cocokkan fingerprint sebelum menyetujui";
          });
    } else if (pendingExport != null) {
      Uri destination = pendingExport;
      String id = exportId;
      pendingExport = null;
      exportId = null;
      work(
          pass -> {
            try (OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
              if (out == null) throw new IOException("Lokasi tidak dapat ditulis");
              store.copyTo(id, pass, out);
            } catch (Exception error) {
              boolean removed = false;
              try {
                removed =
                    android.provider.DocumentsContract.deleteDocument(
                        getContentResolver(), destination);
              } catch (Exception ignored) {
              }
              if (!removed)
                throw new IOException(
                    "Ekspor gagal; hapus file tujuan yang mungkin belum lengkap. "
                        + error.getMessage(),
                    error);
              throw error;
            }
            return "Salinan didekripsi dan disimpan ke lokasi pilihan Anda";
          });
    } else if (pendingTree != null) {
      Uri tree = pendingTree;
      pendingTree = null;
      work(
          pass -> {
            BackupPlan.add(this, tree, "folder", tree.getLastPathSegment());
            int count = store.importTree(tree, pass);
            return count + " file folder diproses; gunakan Backup sekarang";
          });
    } else if (!pendingUris.isEmpty()) {
      List<Uri> selected = new ArrayList<>(pendingUris);
      pendingUris.clear();
      work(
          pass -> {
            int failed = 0;
            for (Uri uri : selected)
              try {
                store.importUri(uri, null, pass);
              } catch (Exception error) {
                if (error instanceof InterruptedException) throw error;
                failed++;
                store.log(
                    "Impor gagal: "
                        + uri.getLastPathSegment()
                        + " — "
                        + error.getClass().getSimpleName());
              }
            if (failed > 0)
              throw new IOException(
                  (selected.size() - failed)
                      + " file diimpor, "
                      + failed
                      + " gagal; lihat riwayat");
            return selected.size() + " file diimpor";
          });
    }
  }

  private void applyBusy(android.view.View view) {
    if (view instanceof android.widget.Button && !"lock".equals(view.getTag()))
      view.setEnabled(!busy);
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) applyBusy(group.getChildAt(i));
    }
  }

  void work(Work task) {
    if (password == null || busy) return;
    busy = true;
    applyBusy(page);
    int current = session;
    char[] pass = password.clone();
    status.setText(R.string.operation_running);
    executor.execute(
        () -> {
          String message;
          List<JSONObject> values = null;
          try {
            message = task.run(pass);
            settings.log(message);
          } catch (Exception error) {
            message =
                "Gagal/sebagian: "
                    + (error.getMessage() == null
                        ? error.getClass().getSimpleName()
                        : error.getMessage());
            settings.log(message);
          }
          try {
            values = store.list(pass);
          } catch (Exception error) {
            message += "; daftar lokal belum dapat dimuat ulang";
          } finally {
            java.util.Arrays.fill(pass, '\0');
          }
          final String result = message;
          final List<JSONObject> refreshed = values;
          runOnUiThread(
              () -> {
                busy = false;
                if (current == session && password != null) {
                  if (refreshed != null) entries = refreshed;
                  showVault();
                  status.setText(result);
                }
              });
        });
  }

  private void configure() {
    if (password == null || busy) return;
    try {
      JSONObject config = settings.read();
      LinearLayout form = new LinearLayout(this);
      form.setPadding(dp(20), dp(5), dp(20), dp(5));
      form.setOrientation(LinearLayout.VERTICAL);
      EditText url = new EditText(this);
      url.setHint("https://vault.domain-anda.com");
      url.setSingleLine();
      url.setText(config.optString("relay"));
      form.addView(url);
      EditText token = new EditText(this);
      token.setHint("Token relay minimal 24 karakter");
      token.setInputType(129);
      token.setText(config.optString("token"));
      form.addView(token);
      CheckBox automatic = new CheckBox(this);
      automatic.setText(R.string.enable_automatic);
      automatic.setChecked(config.optBoolean("automatic"));
      form.addView(automatic);
      CheckBox wifi = new CheckBox(this);
      wifi.setText(R.string.unmetered_only);
      wifi.setChecked(config.optBoolean("wifi", true));
      form.addView(wifi);
      TextView explanation = new TextView(this);
      explanation.setText(R.string.automatic_explanation);
      form.addView(explanation);
      AlertDialog dialog =
          new AlertDialog.Builder(this)
              .setTitle("Relay pribadi & persetujuan")
              .setView(form)
              .setNegativeButton("Batal", null)
              .setPositiveButton("Simpan", null)
              .create();
      dialog.setOnShowListener(
          d ->
              dialog
                  .getButton(-1)
                  .setOnClickListener(
                      v -> {
                        try {
                          new RelayClient(
                              url.getText().toString().trim(),
                              token.getText().toString(),
                              config.optString("relayCa"));
                          synchronized (PrivateSettings.class) {
                            JSONObject latest = settings.read();
                            latest
                                .put("relay", url.getText().toString().trim())
                                .put("token", token.getText().toString())
                                .put("automatic", automatic.isChecked())
                                .put("wifi", wifi.isChecked());
                            if (automatic.isChecked()) {
                              latest.put("backgroundPassword", new String(password));
                              BackupJob.schedule(this, wifi.isChecked());
                            } else {
                              latest.remove("backgroundPassword");
                              BackupJob.cancel(this);
                            }
                            settings.write(latest);
                          }
                          if (automatic.isChecked() && Build.VERSION.SDK_INT >= 33)
                            requestPermissions(
                                new String[] {"android.permission.POST_NOTIFICATIONS"}, 104);
                          token.setText("");
                          dialog.dismiss();
                          status.setText(R.string.connection_saved);
                        } catch (Exception e) {
                          url.setError(e.getMessage());
                        }
                      }));
      activeDialog = dialog;
      dialog.show();
    } catch (Exception e) {
      toast("Pengaturan tidak dapat dibaca");
    }
  }

  private void history() {
    try {
      JSONArray values = settings.read().optJSONArray("history");
      StringBuilder text = new StringBuilder();
      if (values != null)
        for (int i = 0; i < values.length(); i++) text.append(values.getString(i)).append("\n\n");
      activeDialog =
          new AlertDialog.Builder(this)
              .setTitle("Riwayat aktivitas")
              .setMessage(text.length() == 0 ? "Belum ada aktivitas" : text.toString())
              .setPositiveButton("Tutup", null)
              .show();
    } catch (Exception e) {
      toast("Riwayat tidak dapat dibaca");
    }
  }

  private void selectShared() {
    if (password == null || busy) return;
    activeDialog =
        new AlertDialog.Builder(this)
            .setTitle("Tambahkan seluruh penyimpanan bersama?")
            .setMessage(
                "Memindai file umum di penyimpanan utama milik Anda, semua jenis file. Tidak"
                    + " membuka data privat aplikasi lain atau folder sistem yang dibatasi Android."
                    + " Sumber ini ikut backup otomatis bila fitur itu Anda aktifkan.")
            .setNegativeButton("Batal", null)
            .setPositiveButton(
                "Lanjutkan",
                (dialog, which) -> {
                  if (password == null) return;
                  pendingShared = true;
                  if (SharedStorage.allowed(this)) processPending();
                  else if (Build.VERSION.SDK_INT >= 30) {
                    try {
                      startActivity(
                          new Intent(
                              android.provider.Settings
                                  .ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                              Uri.parse("package:" + getPackageName())));
                    } catch (android.content.ActivityNotFoundException e) {
                      pendingShared = false;
                      toast("Pengaturan izin tidak tersedia; gunakan pemilih folder");
                    }
                  } else
                    requestPermissions(
                        new String[] {"android.permission.READ_EXTERNAL_STORAGE"}, 107);
                })
            .show();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request == 107) {
      if (SharedStorage.allowed(this)) {
        if (password != null) processPending();
        else handler.post(this::askPassword);
      } else {
        pendingShared = false;
        toast("Izin tidak diberikan; sumber folder yang sudah dipilih tetap tersedia");
      }
    }
  }

  private void pickCertificate() {
    if (busy || password == null) return;
    Intent intent =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE);
    startActivityForResult(intent, 105);
  }

  private void manageSources() {
    if (password == null || busy) return;
    try {
      JSONArray sources = BackupPlan.sources(settings.read());
      String[] labels = new String[sources.length() + 1];
      for (int i = 0; i < sources.length(); i++) {
        JSONObject v = sources.getJSONObject(i);
        labels[i] =
            (v.getString("kind").equals("folder") ? "Folder: " : "File: ") + v.optString("name");
      }
      labels[sources.length()] = "Hapus sertifikat lokal (kembali ke TLS publik)";
      activeDialog =
          new AlertDialog.Builder(this)
              .setTitle("Pilih sumber untuk berhenti memindai")
              .setItems(
                  labels,
                  (dialog, index) -> {
                    try {
                      if (index == sources.length()) {
                        synchronized (PrivateSettings.class) {
                          JSONObject c = settings.read();
                          c.remove("relayCa");
                          settings.write(c);
                        }
                      } else BackupPlan.remove(this, index);
                      status.setText(R.string.sources_updated);
                    } catch (Exception e) {
                      toast("Pengaturan tidak dapat diubah");
                    }
                  })
              .setNegativeButton("Tutup", null)
              .show();
    } catch (Exception e) {
      toast("Daftar sumber tidak dapat dibaca");
    }
  }

  private void toast(String message) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
  }
}
