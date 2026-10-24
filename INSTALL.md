# Instalasi mandiri — Nusa Personal Vault

## 1. Pasang di HP Android

1. Ambil `nusa-vault-1.0.0.apk` dari direktori `artifacts` di dalam paket.
2. Periksa checksum dengan file `.apk.sha256`. Windows: `Get-FileHash .\artifacts\nusa-vault-1.0.0.apk -Algorithm SHA256`; Linux: `sha256sum -c artifacts/nusa-vault-1.0.0.apk.sha256`.
3. Salin APK ke HP Android 8.0 atau lebih baru. Buka file dan izinkan instalasi dari aplikasi file manager yang Anda pakai.
4. Aplikasi tampil sebagai **Nusa Studio**. Ketuk logo **nusa 7 kali** dengan jeda antarketukan tidak lebih dari 3 detik.
5. Buat kata sandi minimal 12 karakter. Tidak ada PIN/kata sandi bawaan atau pemulihan kata sandi.
6. Pilih file untuk menguji vault lokal. Keluar ke aplikasi lain lalu kembali: vault harus terkunci. Buka kembali, pilih **Simpan salinan**, dan periksa isi file.

APK rilis memiliki tanda tangan sendiri. Jika sebelumnya terpasang build debug, Android akan menolak penggantian karena tanda tangan berbeda. Backup dan pastikan bisa dipulihkan sebelum uninstall build lama.

## 2. Menyiapkan relay pribadi

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

## 3. Memulihkan di laptop

1. Buka origin HTTPS relay yang sama di browser laptop.
2. Ketuk logo 7 kali. Masukkan **kata sandi yang sama dengan HP** saat membuat vault browser.
3. Isi token relay di Koneksi relay lalu pilih Pulihkan dari relay.
4. Unduh file, periksa isinya, dan bandingkan checksum dengan sumber. Unduhan berupa plaintext.

Jangan menghapus sumber atau uninstall aplikasi sebelum pemulihan ini berhasil. Simpan backup volume Docker di lokasi terpisah. Jangan memakai `docker compose down -v` jika data masih dibutuhkan.

## 4. Backup otomatis

Di Android, aktifkan Izinkan backup otomatis setelah koneksi manual berhasil. Pilih jaringan tanpa batas kuota jika perlu dan izinkan notifikasi. Ini persetujuan untuk menyimpan kredensial background terenkripsi dengan Android Keystore. Jadwal sekitar 15 menit dapat ditunda Android/vendor karena baterai/jaringan. Lihat Riwayat backup untuk hasil sebenarnya; jadwal aktif bukan bukti backup sudah berhasil.

## 5. Pengujian ulang dan source

Source Android, companion web, relay, lockfile, Gradle wrapper, skrip build, serta tes disertakan. Perintah lengkap ada di README.md dan TESTING.md. Hasil pengujian saat paket dibuat ada di `artifacts`.

Batas rilis ini: file asli maksimum 20 MiB, satu folder untuk pemindaian otomatis, akses hanya ke file/folder yang diizinkan Android, dan tidak mencakup remote SMS/lokasi/notifikasi. APK belum diuji pada semua merek HP; ikuti checklist perangkat fisik di TESTING.md.

## Kunci untuk pembaruan APK

ZIP tidak menyertakan kunci penandatanganan privat. Jika ingin membangun pembaruan yang dapat menggantikan APK ini, backup `.local/signing` dari workspace pembangun melalui penyimpanan privat. Source dapat dibangun tanpa kunci tersebut, tetapi tanda tangan baru memerlukan uninstall instalasi lama dan pemulihan dari backup.
