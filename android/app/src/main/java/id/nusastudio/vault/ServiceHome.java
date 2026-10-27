package id.nusastudio.vault;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Offline, native mobile adaptation of Shoutoo/siservis-pc's public demonstration UI. */
final class ServiceHome {
  private static final int BLUE = Color.rgb(30, 58, 138),
      INK = Color.rgb(30, 41, 59),
      MUTED = Color.rgb(100, 116, 139),
      BORDER = Color.rgb(226, 232, 240),
      CYAN = Color.rgb(2, 132, 199),
      BG = Color.rgb(248, 250, 252);
  private final Activity activity;
  private final Runnable onLogo;
  private final NumberFormat currency = NumberFormat.getIntegerInstance(new Locale("id", "ID"));
  private static final String[][] TICKETS = {
    {
      "SRV-202610-001",
      "Ahmad Fauzi",
      "0857-1122-3344",
      "Laptop",
      "Lenovo ThinkPad T480",
      "Layar blank hitam setelah booting, kipas berputar kencang",
      "Budi Pratama (Teknisi Hardware)",
      "Proses Servis",
      "4",
      "14-10-2026",
      "IC Power terindikasi korosi ringan, slot RAM berdebu. Dilakukan pembersihan sirkuit dan"
          + " re-pasting thermal.",
      "125000",
      "75000"
    },
    {
      "SRV-202610-002",
      "Siti Nurhaliza",
      "0878-9988-7766",
      "PC Desktop",
      "Custom PC Core i5 12400",
      "Blue Screen (BSOD) terus menerus saat membuka file berat",
      "Budi Pratama (Teknisi Hardware)",
      "Menunggu Sparepart",
      "3",
      "16-10-2026",
      "Health SSD NVMe terdeteksi 0% dengan ribuan bad sector. Diperlukan penggantian SSD baru dan"
          + " clean install OS.",
      "150000",
      "550000"
    },
    {
      "SRV-202610-003",
      "Rian Hidayat",
      "0813-4455-6677",
      "All-in-One PC",
      "HP 24-df0012d AIO",
      "Kinerja sangat lambat dan sering freeze saat booting awal",
      "Muhammad Soffin Halim Sampurna (Koordinator Lab)",
      "Selesai",
      "5",
      "11-10-2026",
      "Optimasi sistem startup, pembersihan temporary cache, dan upgrade RAM DDR4 8GB selesai"
          + " dilakukan. Unit siap diambil.",
      "100000",
      "250000"
    }
  };

  ServiceHome(Activity activity, Runnable onLogo) {
    this.activity = activity;
    this.onLogo = onLogo;
  }

  private int dp(int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }

  private LinearLayout column() {
    LinearLayout v = new LinearLayout(activity);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  private GradientDrawable shape(int color, int radius, boolean border) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    if (border) d.setStroke(dp(1), BORDER);
    return d;
  }

  private TextView text(LinearLayout parent, String value, int size, int color, boolean bold) {
    TextView t = new TextView(activity);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    t.setPadding(0, dp(5), 0, dp(5));
    t.setLineSpacing(dp(2), 1);
    parent.addView(t, new LinearLayout.LayoutParams(-1, -2));
    return t;
  }

  private void gap(LinearLayout parent, int height) {
    View v = new View(activity);
    parent.addView(v, new LinearLayout.LayoutParams(1, dp(height)));
  }

  private Button button(LinearLayout parent, String label, int color, Runnable action) {
    Button b = new Button(activity);
    b.setText(label);
    b.setTextSize(14);
    b.setAllCaps(false);
    b.setTextColor(Color.WHITE);
    b.setBackgroundTintList(null);
    b.setBackground(shape(color, 8, false));
    b.setMinHeight(dp(48));
    b.setPadding(dp(12), dp(12), dp(12), dp(12));
    b.setOnClickListener(v -> action.run());
    parent.addView(b, new LinearLayout.LayoutParams(-1, -2));
    return b;
  }

  private LinearLayout card(LinearLayout parent, String title, String subtitle) {
    LinearLayout c = column();
    c.setPadding(dp(18), dp(16), dp(18), dp(18));
    c.setBackground(shape(Color.WHITE, 12, true));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(18);
    parent.addView(c, p);
    TextView heading = text(c, title, 19, INK, true);
    if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
    View line = new View(activity);
    line.setBackgroundColor(BORDER);
    c.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    gap(c, 8);
    text(c, subtitle, 14, MUTED, false);
    gap(c, 12);
    return c;
  }

  View create() {
    ScrollView scroll = new ScrollView(activity);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(BG);
    LinearLayout root = column();
    scroll.addView(root);
    LinearLayout header = column();
    header.setPadding(dp(22), dp(24), dp(22), dp(28));
    GradientDrawable gradient =
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[] {BLUE, Color.rgb(30, 64, 175), Color.rgb(14, 165, 233)});
    header.setBackground(gradient);
    root.addView(header);
    TextView logo = text(header, "SiServis-PC", 30, Color.WHITE, true);
    logo.setContentDescription("SiServis-PC logo");
    logo.setMinHeight(dp(48));
    logo.setOnClickListener(v -> onLogo.run());
    text(
        header,
        "Sistem Informasi Manajemen & Live Tracking Servis Perangkat Komputer",
        14,
        Color.WHITE,
        false);
    gap(header, 12);
    LinearLayout badge = column();
    badge.setPadding(dp(12), dp(10), dp(12), dp(10));
    badge.setBackground(shape(0x22ffffff, 8, false));
    header.addView(badge);
    text(badge, "Muhammad Soffin Halim Sampurna", 13, Color.WHITE, true);
    text(badge, "NIM: 240104019 • D3 Teknik Komputer 24A1", 12, Color.WHITE, false);
    LinearLayout content = column();
    content.setPadding(dp(16), dp(18), dp(16), dp(16));
    root.addView(content);
    text(content, "DASHBOARD SERVIS", 12, BLUE, true);
    text(content, "Pratinjau • Data contoh dari SiServis-PC", 12, MUTED, false);
    gap(content, 8);
    String[] labels = {"Unit Masuk", "Dalam Servis", "Menunggu Part", "Selesai Siap Ambil"};
    String[] values = {"12", "4", "3", "5"};
    int[] colors = {BLUE, CYAN, Color.rgb(180, 110, 0), Color.rgb(4, 120, 87)};
    // Two columns on phones; large accessibility font sizes use one column.
    int columns = activity.getResources().getConfiguration().fontScale > 1.3f ? 1 : 2;
    for (int i = 0; i < 4; i += columns) {
      LinearLayout row = new LinearLayout(activity);
      content.addView(row, new LinearLayout.LayoutParams(-1, -2));
      for (int j = i; j < Math.min(i + columns, 4); j++) {
        LinearLayout stat = column();
        stat.setPadding(dp(14), dp(10), dp(14), dp(10));
        stat.setBackground(shape(Color.WHITE, 12, true));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
        p.bottomMargin = dp(10);
        if (j > i) p.leftMargin = dp(10);
        row.addView(stat, p);
        text(stat, labels[j], 12, MUTED, true);
        text(stat, values[j], 30, colors[j], true);
      }
    }
    gap(content, 8);
    tracking(content);
    calculator(content);
    text(content, "SiServis-PC © 2026", 14, BLUE, true).setGravity(Gravity.CENTER);
    text(
            content,
            "Proyek UTS Rekayasa Perangkat Lunak\n"
                + "D3 Teknik Komputer\n"
                + "Universitas Duta Bangsa Surakarta",
            12,
            MUTED,
            false)
        .setGravity(Gravity.CENTER);
    if (Build.VERSION.SDK_INT >= 35) {
      scroll.setOnApplyWindowInsetsListener(
          (v, insets) -> {
            android.graphics.Insets bars =
                insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
          });
    }
    return scroll;
  }

  private LinearLayout result(LinearLayout parent) {
    LinearLayout box = column();
    box.setPadding(dp(12), dp(12), dp(12), dp(12));
    box.setBackground(shape(BG, 10, true));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = dp(16);
    parent.addView(box, p);
    box.setVisibility(View.GONE);
    box.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    return box;
  }

  private void detail(LinearLayout box, String label, String value) {
    text(box, label, 12, MUTED, false);
    text(box, value, 14, INK, true);
    View line = new View(activity);
    line.setBackgroundColor(BORDER);
    box.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
  }

  private String money(long amount) {
    return "Rp " + currency.format(amount);
  }

  private void tracking(LinearLayout parent) {
    LinearLayout c =
        card(
            parent,
            "Lacak Tiket Servis",
            "Masukkan nomor tiket untuk melihat tahapan pengerjaan perangkat Anda.");
    TextView label = text(c, "Nomor Tiket Servis", 14, INK, true);
    EditText input = new EditText(activity);
    input.setId(View.generateViewId());
    label.setLabelFor(input.getId());
    input.setSingleLine(true);
    input.setTextSize(15);
    input.setTextColor(INK);
    input.setHintTextColor(MUTED);
    input.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
    input.setHint("Contoh: SRV-202610-001");
    input.setPadding(dp(12), dp(12), dp(12), dp(12));
    input.setBackground(shape(Color.WHITE, 8, true));
    c.addView(input, new LinearLayout.LayoutParams(-1, dp(52)));
    gap(c, 12);
    // Insert the result below the example-ticket controls.
    LinearLayout output = column();
    Runnable lookup =
        () -> {
          android.view.inputmethod.InputMethodManager keyboard =
              (android.view.inputmethod.InputMethodManager)
                  activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
          keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
          output.removeAllViews();
          output.setVisibility(View.VISIBLE);
          String id = input.getText().toString().trim().toUpperCase(Locale.ROOT);
          String[] ticket = null;
          for (String[] item : TICKETS) if (item[0].equals(id)) ticket = item;
          if (ticket == null) {
            text(
                output,
                id.isEmpty()
                    ? "Silakan masukkan nomor tiket servis."
                    : "Nomor tiket tidak ditemukan. Periksa kembali atau pilih contoh tiket.",
                14,
                Color.rgb(153, 27, 27),
                false);
            return;
          }
          text(output, ticket[0], 17, BLUE, true);
          text(output, ticket[7], 14, CYAN, true);
          int progress = Integer.parseInt(ticket[8]);
          String[] steps = {"Masuk", "Diagnosa", "Part", "Servis", "Selesai"};
          // Vertical timeline keeps all five stages legible on narrow screens and at large font
          // scales.
          for (int i = 0; i < 5; i++)
            text(
                output,
                (i + 1 < progress ? "✓  " : (i + 1) + "  ")
                    + steps[i]
                    + (i + 1 == progress ? " • Saat ini" : ""),
                13,
                i + 1 < progress ? Color.rgb(4, 120, 87) : i + 1 == progress ? BLUE : MUTED,
                i + 1 == progress);
          detail(output, "Nama Pelanggan", ticket[1] + " (" + ticket[2] + ")");
          detail(output, "Unit Perangkat", ticket[3] + " · " + ticket[4]);
          detail(output, "Keluhan Awal", ticket[5]);
          detail(output, "Diagnosa Teknisi", ticket[10]);
          detail(output, "Teknisi", ticket[6]);
          detail(output, "Estimasi Selesai", ticket[9]);
          detail(
              output,
              "Total Biaya (Jasa + Komponen)",
              money(Long.parseLong(ticket[11]) + Long.parseLong(ticket[12])));
        };
    button(c, "Periksa Status Pengerjaan", BLUE, lookup);
    text(c, "Contoh tiket", 12, MUTED, false);
    for (String[] ticket : TICKETS) {
      Button quick =
          button(
              c,
              ticket[0],
              Color.rgb(224, 242, 254),
              () -> {
                input.setText(ticket[0]);
                lookup.run();
              });
      quick.setTextColor(Color.rgb(3, 105, 161));
      gap(c, 6);
    }
    output.setPadding(dp(12), dp(12), dp(12), dp(12));
    output.setBackground(shape(BG, 10, true));
    c.addView(output);
    output.setVisibility(View.GONE);
    output.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    input.setOnEditorActionListener(
        (v, action, event) -> {
          if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
            lookup.run();
            return true;
          }
          return false;
        });
  }

  private Spinner select(LinearLayout parent, String title, String[] options) {
    TextView label = text(parent, title, 14, INK, true);
    Spinner spinner = new Spinner(activity, Spinner.MODE_DIALOG);
    spinner.setId(View.generateViewId());
    label.setLabelFor(spinner.getId());
    spinner.setPrompt(title);
    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, options);
    spinner.setAdapter(adapter);
    spinner.setBackground(shape(BG, 8, true));
    spinner.setPadding(dp(8), dp(8), dp(8), dp(8));
    spinner.setMinimumHeight(dp(52));
    parent.addView(spinner, new LinearLayout.LayoutParams(-1, -2));
    gap(parent, 8);
    return spinner;
  }

  private void calculator(LinearLayout parent) {
    LinearLayout c =
        card(
            parent,
            "Kalkulator Estimasi",
            "Hitung perkiraan biaya dan durasi perbaikan hardware atau software.");
    Spinner device =
        select(
            c,
            "Kategori Perangkat",
            new String[] {"PC Desktop / Tower", "Laptop / Notebook", "All-in-One PC"});
    Spinner severity =
        select(
            c,
            "Tingkat Kerusakan",
            new String[] {
              "Ringan · 1 hari kerja", "Sedang · 3 hari kerja", "Berat · 5 hari kerja"
            });
    Spinner part =
        select(
            c,
            "Suku Cadang",
            new String[] {
              "Tanpa penggantian · Rp 0",
              "Thermal Paste · Rp 75.000",
              "RAM DDR4 8GB · Rp 250.000",
              "Power Supply 550W · Rp 450.000",
              "SSD NVMe 512GB · Rp 550.000"
            });
    CheckBox discount = new CheckBox(activity);
    discount.setText(R.string.service_academic_discount);
    discount.setTextSize(14);
    discount.setTextColor(INK);
    discount.setMinHeight(dp(48));
    c.addView(discount);
    gap(c, 12);
    LinearLayout output = result(c);
    button(
        c,
        "Hitung Estimasi Biaya & Waktu",
        CYAN,
        () -> {
          long base = new long[] {75000, 100000, 120000}[device.getSelectedItemPosition()];
          long labor = base * new int[] {100, 150, 220}[severity.getSelectedItemPosition()] / 100;
          long component =
              new long[] {0, 75000, 250000, 450000, 550000}[part.getSelectedItemPosition()];
          long saving = discount.isChecked() ? (labor + component) / 10 : 0;
          int days = new int[] {1, 3, 5}[severity.getSelectedItemPosition()];
          LocalDate target = LocalDate.now(ZoneId.of("Asia/Jakarta"));
          for (int left = days; left > 0; ) {
            target = target.plusDays(1);
            if (target.getDayOfWeek() != DayOfWeek.SATURDAY
                && target.getDayOfWeek() != DayOfWeek.SUNDAY) left--;
          }
          output.removeAllViews();
          output.setVisibility(View.VISIBLE);
          text(output, "Rincian Hasil Kalkulasi", 16, BLUE, true);
          detail(output, "Perangkat", device.getSelectedItem().toString());
          detail(output, "Biaya Jasa Teknisi", money(labor));
          detail(output, "Biaya Sparepart", money(component));
          if (saving > 0) detail(output, "Diskon Civitas (10%)", "− " + money(saving));
          detail(output, "Total Estimasi Biaya", money(labor + component - saving));
          detail(
              output,
              "Estimasi Pengerjaan",
              days
                  + " hari kerja • Target "
                  + target.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")));
        });
    c.removeView(output);
    c.addView(output);
  }
}
