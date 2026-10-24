# Hasil validasi rilis 1.1.0

Pengujian dalam environment pengembangan pada 24 Oktober 2026. Baseline 1.0.0 diperiksa terhadap PRD; perubahan dan batas penerimaan dijelaskan di AUDIT.md. Ini bukan klaim pengujian semua HP atau deployment VPS publik.

| Pemeriksaan | Bukti |
| --- | --- |
| Server dan helper Node | 9 tes lulus: auth/rate limit, chunk immutable, hash/AAD, batas unggahan, kuota tanpa menghapus backup, format v1/v2, installer, HTTPS LAN dengan verifikasi CA/hostname, restore CLI 25 MiB+17 byte dan penolakan traversal/korupsi |
| Browser Chromium | Lulus: tampilan HP, impor/backup/restore 25 MiB+17 byte, SHA-256 sama, konteks laptop terpisah, backup v1, folder, perbaikan chunk rusak, lock, pembatalan unlock dan kata sandi salah; tanpa page error |
| Native Android 8.0 / API 26 x86_64 | 21 tes instrumentasi lulus: kripto, Keystore, SAF rekursif, beberapa sumber/izin gagal, JobScheduler benar-benar berjalan, streaming >20 MiB/resume, pemilihan CA, korupsi/repair, izin penyimpanan bersama, company profile/login/lock dan persetujuan sertifikat |
| Interoperabilitas Android → HTTPS relay → browser | Lulus; isi unduhan cocok persis |
| APK rilis produksi di Android 8.0 | Signature/checksum, instalasi dan company profile terkunci lulus; 5 tes kripto/persistence/Keystore pada APK produksi lulus |
| Upgrade APK bertanda tangan | 1.0.0 dipasang, diisi data v1/pengaturan Keystore, lalu 1.1.0 dipasang dengan install-r tanpa clear/uninstall; kata sandi, struktur folder, isi byte dan pengaturan tetap terbaca |
| Build ABI ARM64, ARMv7, x86_64 | Lulus; dukungan alignment native 16 KiB. ARM dikompilasi, belum dijalankan pada HP fisik |
| Lint debug/release | Tidak ada error; 1 peringatan ScopedStorage tentang kebijakan Play Store untuk MANAGE_EXTERNAL_STORAGE, dipertahankan tanpa mematikan pemeriksaan |
| Docker relay | Build dan runtime nonroot/read-only, autentikasi, persistensi volume setelah restart lulus |
| Compose/Caddy | Konfigurasi valid. DNS publik, ACME dan koneksi seluler belum diuji karena belum ada VPS/domain |
| Paket APK | Alignment, signature v2/v3, SHA-256, versionCode 2 dan signer sama dengan 1.0.0 diperiksa; tidak debuggable dan tanpa trust TLS khusus tes |

Laporan aktual tersimpan di artifacts/android-test-results.txt, web-test-results.txt, compatibility-test-results.txt, release-smoke-results.txt, container-test-results.txt dan apk-signature.txt. Manifest distribusi mencantumkan SHA-256 setiap berkas paket. Trust TLS tes hanya dibuat di varian debug dan dibersihkan setelah tes.

Pada percobaan awal suite izin, pencabutan runtime permission menghentikan instrumentasi dan fixture belum terbentuk dengan benar. Harness dan fixture diperbaiki: pengujian Android 8 dimulai tanpa izin lalu memberikan izin pada emulator disposable; pencabutan izin khusus all-files Android 11+ memakai app-ops. Pengujian diulang setelah perbaikan. Hasil gagal awal tidak dianggap lulus.

Belum dibuktikan untuk rilis ini: perilaku izin all-files/penjadwalan pada semua Android modern/vendor, Android 15 foreground di emulator software yang sebelumnya mengalami ANR System UI, perangkat ARM fisik, data privat aplikasi lain (dibatasi OS), deployment VPS dan jaringan seluler nyata, reliability >95% jangka panjang, atau zero data loss dalam seluruh kondisi. Browser download >64 MiB memerlukan File System Access atau helper CLI. Jadwal background dapat ditunda OS. Checklist perangkat fisik ada di TESTING.md.

Workflow GitHub Actions menjalankan regresi web/container dan Android 35 pada setiap push. Status remote aktual: https://github.com/zorororonoa1946-del/aplikasi/actions. Tes lokal tidak dianggap bukti CI remote lulus.
