# Hasil validasi rilis 1.0.0

Dijalankan dalam environment pengembangan pada 24 Oktober 2026. Ini hasil pada instance ini, bukan klaim deployment VPS publik atau pengujian semua HP.

| Pemeriksaan | Hasil |
| --- | --- |
| Node relay dan installer | 3 tes lulus; autentikasi, envelope, batas ukuran, persistensi, konflik/unggahan bersamaan, helper kredensial privat dan non-overwrite |
| Browser Chromium, tampilan HP + konteks laptop terpisah | Lulus: impor, enkripsi, backup, pemulihan, kesamaan byte unduhan, kunci, pembatalan unlock yang sedang diproses, kata sandi salah |
| Native Android 15 / API 35 x86_64 | 14 tes instrumentasi lulus; termasuk folder/subfolder melalui DocumentsProvider dan izin URI yang benar, deduplikasi, JobScheduler yang benar-benar menjalankan backup, UI login/lock |
| Interoperabilitas Android → HTTPS relay → browser | Lulus; byte file unduhan sama persis dengan sumber Android |
| APK rilis bertanda tangan, Android 8.0 / API 26 x86_64 | Signature/checksum, instalasi, launch dan company profile terkunci lulus |
| Kriptografi pada APK rilis sebenarnya di Android 8.0 | 5 tes kompatibilitas lulus: AES-GCM, password/tamper, fixture Web Crypto, persistence/deduplication/restart, Keystore. APK tes ditandatangani dengan sertifikat rilis yang sama |
| Build ABI ARM64, ARMv7, x86_64 | Lulus; ARM64/ARMv7 dikompilasi tetapi belum dijalankan pada HP fisik |
| Lint debug dan release | 0 temuan |
| Docker relay | Build dan runtime nonroot/read-only lulus; autentikasi dan volume persisten tetap bekerja setelah restart |
| Compose dan Caddy HTTPS | Konfigurasi valid; public DNS/ACME belum dijalankan karena pengguna memilih instalasi mandiri tanpa VPS/domain |
| Paket APK | Alignment, APK Signature Scheme v2/v3 dan SHA-256 diverifikasi; tidak debuggable dan tidak memuat trust TLS khusus tes |

Laporan runner tersimpan di `artifacts/android-test-results.txt`, `compatibility-test-results.txt`, `release-smoke-results.txt`, dan `container-test-results.txt`. Versi OS aktual tercantum dalam laporan. Android 15 pada emulator software tanpa KVM mengalami masalah System UI/ANR saat pemeriksaan foreground APK rilis; pemeriksaan launch rilis dilakukan dan lulus pada Android 8.0. Masalah emulator itu tidak disembunyikan sebagai tes yang lulus.

Yang belum divalidasi: kebijakan baterai OEM, pemilih folder pada setiap merek HP, koneksi jaringan seluler nyata, domain/sertifikat VPS publik, semua perangkat ARM fisik, dan file di atas 20 MiB (tidak didukung rilis ini). Checklist manual ada di TESTING.md. CI tersedia dalam source tetapi belum dijalankan di GitHub.
