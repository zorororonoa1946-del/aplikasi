# Instalasi mandiri — Nusa Personal Vault

## 1. Pasang di HP Android

1. Ambil `nusa-vault-1.2.0.apk` dari direktori `artifacts` di dalam paket.
2. Periksa checksum dengan file `.apk.sha256`. Windows: `Get-FileHash .\artifacts\nusa-vault-1.2.0.apk -Algorithm SHA256`; Linux: `sha256sum -c artifacts/nusa-vault-1.2.0.apk.sha256`.
3. Salin APK ke HP Android 8.0 atau lebih baru. Buka file dan izinkan instalasi dari aplikasi file manager yang Anda pakai.
4. Aplikasi tampil sebagai **SiServis-PC**. Ketuk logo **SiServis-PC 7 kali** dengan jeda antarketukan tidak lebih dari 3 detik.
5. Buat kata sandi minimal 12 karakter. Tidak ada PIN/kata sandi bawaan atau pemulihan kata sandi.
6. Pilih file untuk menguji vault lokal. Keluar ke aplikasi lain lalu kembali: vault harus terkunci. Buka kembali, pilih **Simpan salinan**, dan periksa isi file.

Rilis 1.2.0 memakai kunci yang sama dengan 1.0.0 dan 1.1.0; instal di atas versi lama untuk mempertahankan data. Backup dahulu. APK rilis memiliki tanda tangan sendiri. Jika sebelumnya terpasang build debug, Android akan menolak penggantian karena tanda tangan berbeda. Backup dan pastikan bisa dipulihkan sebelum uninstall build lama.

Untuk server Docker lokal, ikuti [langkah clone dan Docker Compose di README](README.md#server-lokal-dengan-docker-tanpa-nodeopenssl-di-komputer).

## 2. Tanpa VPS: relay lokal di laptop

Pasang Node.js >=22 dan OpenSSL, hubungkan HP/laptop ke WiFi yang sama, lalu jalankan `npm run relay:local -- 192.168.1.10` (ganti dengan IP LAN laptop). Izinkan port TCP 3443 dari jaringan lokal. Baca alamat/token secara privat dari `.local/local-relay/connection.txt`. Impor hanya `ca.pem` ke Pengaturan relay Android dan setujui setelah mencocokkan fingerprint SHA-256 dengan laptop. Simpan kunci privat tetap di laptop.

Untuk browser, percayai CA publik itu melalui pengaturan sertifikat browser/OS. Jangan menonaktifkan verifikasi TLS. Alternatif langsung: `npm run restore -- --connection .local/local-relay/connection.txt --ca .local/local-relay/ca.pem --out restored-vault`; masukkan kata sandi ketika diminta. Hasil restore adalah plaintext, lindungi foldernya. Laptop harus menyala agar backup LAN berjalan. Detail pemulihan, pergantian IP dan sertifikat ada di README.md.

## 3. Menyiapkan relay pribadi melalui internet

Backup HP ke laptop melalui internet memerlukan relay HTTPS. Paket ini tidak menyewa VPS/domain dan tidak mengirim data ke penyedia relay pihak lain. Sampai relay disiapkan, vault lokal tetap bisa digunakan.

Siapkan VPS Linux dengan minimal 1 GB RAM, disk yang cukup untuk salinan file, Docker Engine + Compose v2, dan Node.js >=22 untuk helper konfigurasi. Arahkan DNS domain pilihan ke IP VPS, buka port TCP 80 dan 443, lalu salin isi paket source ke direktori kerja privat.

```sh
cd direktori-paket
npm run relay:init -- vault.domain-anda.com
docker compose -f deployment/compose.yaml up -d --build
docker compose -f deployment/compose.yaml ps
```

Helper membuat `deployment/.env` dengan token acak. Jangan kirim file itu melalui chat, commit ke Git, atau mencetaknya dalam log publik. Token berbeda dari kata sandi vault. Caddy otomatis mengurus HTTPS untuk domain tersebut setelah DNS dan port siap. Buka domain melalui browser untuk memeriksa company profile.

Di Android, buka Pengaturan relay & backup otomatis. Isi origin HTTPS domain dan token dari `.env`, lalu simpan. Pilih file/folder, kemudian Backup sekarang. Folder dipilih melalui izin Android; folder privat aplikasi lain dan folder yang dibatasi Android tidak tersedia.

## 4. Memulihkan di laptop

1. Buka origin HTTPS relay yang sama di browser laptop.
2. Ketuk logo 7 kali. Masukkan **kata sandi yang sama dengan HP** saat membuat vault browser.
3. Isi token relay di Koneksi relay lalu pilih Pulihkan dari relay.
4. Unduh file, periksa isinya, dan bandingkan checksum dengan sumber. Unduhan berupa plaintext.

Jangan menghapus sumber atau uninstall aplikasi sebelum pemulihan ini berhasil. Simpan backup volume Docker di lokasi terpisah. Jangan memakai `docker compose down -v` jika data masih dibutuhkan.

## 5. Backup otomatis

Di Android, aktifkan Izinkan backup otomatis setelah koneksi manual berhasil. Pilih jaringan tanpa batas kuota jika perlu dan izinkan notifikasi. Ini persetujuan untuk menyimpan kredensial background terenkripsi dengan Android Keystore. Jadwal sekitar 15 menit dapat ditunda Android/vendor karena baterai/jaringan. Lihat Riwayat backup untuk hasil sebenarnya; jadwal aktif bukan bukti backup sudah berhasil.

## 6. Pengujian ulang dan source

Source Android, companion web, relay, lockfile, Gradle wrapper, skrip build, serta tes disertakan. Perintah lengkap ada di README.md dan TESTING.md. Hasil pengujian saat paket dibuat ada di `artifacts`.

File besar diproses dalam chunk 4 MiB, tanpa batas lama 20 MiB. Beberapa file/folder dapat dipilih untuk pemindaian otomatis; opsi seluruh penyimpanan bersama memerlukan persetujuan izin Android. Data privat aplikasi lain tetap dibatasi. Browser memerlukan streaming file picker untuk unduhan >64 MiB; gunakan helper restore bila tidak tersedia. Kuota relay bawaan 10 GiB dapat diubah melalui MAX_STORAGE_BYTES. Remote SMS/lokasi/notifikasi tidak termasuk. APK belum diuji pada semua merek HP; ikuti checklist perangkat fisik di TESTING.md.

## Kunci untuk pembaruan APK

ZIP tidak menyertakan kunci penandatanganan privat. Jika ingin membangun pembaruan yang dapat menggantikan APK ini, backup `.local/signing` dari workspace pembangun melalui penyimpanan privat. Source dapat dibangun tanpa kunci tersebut, tetapi tanda tangan baru memerlukan uninstall instalasi lama dan pemulihan dari backup.

## Mencoba tampilan SiServis-PC

Halaman awal Android 1.2.0 dapat dipakai tanpa server/internet. Pilih contoh tiket `SRV-202610-001`, `002`, atau `003` untuk melihat status dan rincian demo. Nomor di luar contoh menampilkan pesan tidak ditemukan. Kalkulator mengikuti tarif dari repositori SiServis-PC: Laptop + Sedang + Thermal Paste + diskon civitas menghasilkan Rp 202.500, dengan estimasi 3 hari kerja. Data ini belum terhubung ke backend servis.

Untuk masuk ke vault, ketuk tulisan **SiServis-PC** di header tujuh kali. Saat dikunci atau aplikasi masuk background, tampilan kembali ke dashboard servis. Di browser laptop, logo akses vault masih **nusa**.
