# Validasi pembaruan UI Android 1.2.0

Audit backend terbaru pada 27 Oktober 2026, perbaikan penyimpanan, pengujian Docker lokal melalui port host dan SIGKILL, serta batas backend SiServis-PC dijelaskan di [BACKEND_AUDIT.md](BACKEND_AUDIT.md).

Pembaruan 27 Oktober 2026 mengadaptasi UI SiServis-PC menjadi halaman awal native Android. Source acuan: `Shoutoo/siservis-pc` commit `232366f88cd66a8aacccf318ce5028b2641a63b0`. Dashboard, tiket contoh, dan kalkulator bekerja offline; tidak ada koneksi ke backend servis PHP.

- Build APK ARM64, ARMv7 dan x86_64 berhasil; versionName 1.2.0, versionCode 3, package ID tetap sama.
- Lint release: 0 error, 1 peringatan ScopedStorage yang sudah ada.
- Android API 26: 23 tes utama + 1 tes pencabutan izin di proses terpisah lulus. Dua tes tambahan memeriksa pencarian tiket contoh/nomor tidak dikenal dan perhitungan Laptop + Sedang + Thermal Paste + diskon 10% = Rp 202.500.
- Tujuh ketukan membuka dialog, password membuka vault, dan lock kembali ke dashboard SiServis-PC teruji. Backup Android → HTTPS relay → browser menghasilkan byte yang sama.
- Hasil render native pada layar emulator 320 × 640 diperiksa; tangkapan halaman terkunci ada di `artifacts/siservis-home.png`. Gambar dibuat oleh instrumentasi tes dengan menggambar View halaman contoh ke bitmap; FLAG_SECURE produksi tidak dinonaktifkan.
- Tanda tangan v2/v3 dan sertifikat rilis sama dengan versi sebelumnya. Hasil instalasi APK produksi dan uji upgrade tercatat dalam laporan `release-smoke-results.txt` serta `compatibility-test-results.txt`.

Belum diuji pada HP fisik pengguna atau semua versi/vendor Android. Server Docker dan companion web tidak diubah pada pembaruan UI ini; hasil audit 1.1.0 di bawah merupakan bukti historis, bukan pengujian ulang server pada 1.2.0. Status CI untuk commit UI baru dapat dilihat di GitHub Actions; hasil lokal tidak berarti CI remote sudah lulus.

## Hasil validasi rilis 1.1.0 (historis)

Pengujian dalam environment pengembangan pada 24 Oktober 2026. Baseline 1.0.0 diperiksa terhadap PRD; perubahan dan batas penerimaan dijelaskan di AUDIT.md. Ini bukan klaim pengujian semua HP atau deployment VPS publik.

| Pemeriksaan | Bukti |
| --- | --- |
| Server dan helper Node | 9 tes lulus: auth/rate limit, chunk immutable, hash/AAD, batas unggahan, kuota tanpa menghapus backup, format v1/v2, installer, HTTPS LAN dengan verifikasi CA/hostname, restore CLI 25 MiB+17 byte dan penolakan traversal/korupsi |
| Browser Chromium | Lulus: tampilan HP, impor/backup/restore 25 MiB+17 byte, SHA-256 sama, konteks laptop terpisah, backup v1, folder, perbaikan chunk rusak, lock, pembatalan unlock dan kata sandi salah; tanpa page error |
| Native Android 8.0 / API 26 x86_64 | 21 tes instrumentasi utama + 1 pemeriksaan pencabutan izin di proses baru lulus: kripto, Keystore, SAF rekursif, beberapa sumber/izin gagal, JobScheduler benar-benar berjalan, streaming >20 MiB/resume, pemilihan CA, korupsi/repair, izin penyimpanan bersama, company profile/login/lock dan persetujuan sertifikat |
| Interoperabilitas Android → HTTPS relay → browser | Lulus; isi unduhan cocok persis |
| APK rilis produksi di Android 8.0 | Signature/checksum, instalasi dan company profile terkunci lulus; 5 tes kripto/persistence/Keystore pada APK produksi lulus |
| Upgrade APK bertanda tangan | 1.0.0 dipasang, diisi data v1/pengaturan Keystore, lalu 1.1.0 dipasang dengan install-r tanpa clear/uninstall; kata sandi, struktur folder, isi byte dan pengaturan tetap terbaca |
| Build ABI ARM64, ARMv7, x86_64 | Lulus; dukungan alignment native 16 KiB. ARM dikompilasi, belum dijalankan pada HP fisik |
| Lint debug/release | Tidak ada error; 1 peringatan ScopedStorage tentang kebijakan Play Store untuk MANAGE_EXTERNAL_STORAGE, dipertahankan tanpa mematikan pemeriksaan |
| Docker relay | Build dan runtime nonroot/read-only, autentikasi, persistensi volume setelah restart lulus |
| Compose/Caddy | Konfigurasi valid. DNS publik, ACME dan koneksi seluler belum diuji karena belum ada VPS/domain |
| Paket APK | Alignment, signature v2/v3, SHA-256, versionCode 2 dan signer sama dengan 1.0.0 diperiksa; tidak debuggable dan tanpa trust TLS khusus tes |

Laporan aktual tersimpan di artifacts/android-test-results.txt, web-test-results.txt, compatibility-test-results.txt, release-smoke-results.txt, container-test-results.txt dan apk-signature.txt. Manifest distribusi mencantumkan SHA-256 setiap berkas paket. Trust TLS tes hanya dibuat di varian debug dan dibersihkan setelah tes.

Pada percobaan awal, harness izin menghentikan instrumentasi ketika mencabut izin, dan fixture belum terbentuk dengan benar. Pada Android modern, pencabutan izin all-files memang mematikan proses aplikasi. Harness diperbaiki: suite utama memeriksa akses sebelum/sesudah persetujuan; host mencabut izin setelah suite selesai, lalu instrumentasi baru memeriksa penolakan akses. Semua percobaan gagal tersebut tidak dihitung sebagai tes lulus.

Belum dibuktikan untuk rilis ini: perilaku izin all-files/penjadwalan pada semua Android modern/vendor, Android 15 foreground di emulator software yang sebelumnya mengalami ANR System UI, perangkat ARM fisik, data privat aplikasi lain (dibatasi OS), deployment VPS dan jaringan seluler nyata, reliability >95% jangka panjang, atau zero data loss dalam seluruh kondisi. Browser download >64 MiB memerlukan File System Access atau helper CLI. Jadwal background dapat ditunda OS. Checklist perangkat fisik ada di TESTING.md.

Workflow GitHub Actions menjalankan regresi web/container dan native pada setiap push: Android 35 dengan KVM jika tersedia, Android 26 software sebagai fallback; compile/target SDK tetap 35. Run audit pertama gagal pada setup KVM sebelum tes, lalu workflow diperbaiki untuk mendeteksi kemampuan runner tanpa mematikan pengujian native. Status remote aktual: https://github.com/zorororonoa1946-del/aplikasi/actions. Tes lokal tidak dianggap bukti CI remote lulus.

## Verifikasi GitHub Actions

Run https://github.com/zorororonoa1946-del/aplikasi/actions/runs/40074751665 lulus untuk job web dan Android pada kode `114d9e892d6c858fd18e908beecbcaa5577be909`. Kegagalan setup KVM dan pencabutan izin saat instrumentasi telah diperbaiki. Run tersebut merupakan bukti audit historis 1.1.0. Workflow mengabaikan push yang hanya mengubah Markdown dan artifacts.

## Docker LAN lokal

Image `deployment/Dockerfile.local` dan `compose.yaml` root telah diuji: build berhasil, proses nonroot/read-only, HTTPS dengan verifikasi CA/hostname, penolakan token salah, backup terenkripsi dan dekripsi hasilnya berhasil. Setelah restart container, token/CA serta backup tetap dapat digunakan. Volume uji terpisah dibersihkan setelah pemeriksaan; tidak memakai data pengguna. Koneksi dari HP fisik melalui router/firewall pengguna tetap perlu dicoba pada jaringan pengguna.
