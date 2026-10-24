# Nusa Studio — Personal Vault & Backup

Aplikasi Android native dan companion browser untuk vault pribadi serta backup satu arah ke relay milik sendiri. Halaman awal berupa company profile Nusa Studio. Ketuk logo **nusa 7 kali dalam 3 detik**, lalu buat/buka vault dengan kata sandi minimal 12 karakter. Tidak ada kata sandi bawaan.

Unduhan lengkap: `artifacts/nusa-vault-installation-1.0.0.zip`. Mulai dari INSTALL.md; hasil pengujian dan batas verifikasinya ada di VALIDATION.md.

## Paket Android

- Minimum Android 8.0 / API 26; target Android 15 / API 35.
- APK rilis: `artifacts/nusa-vault-1.0.0.apk`, tersedia setelah `scripts/package-android.sh` berhasil. Checksum dan informasi signature ada di direktori yang sama.
- Instal APK melalui file manager Android dan izinkan instalasi dari sumber tersebut, atau gunakan `adb install -r artifacts/nusa-vault-1.0.0.apk`.
- Build debug dan rilis memakai tanda tangan berbeda. Beralih dari debug ke rilis memerlukan uninstall; **backup dan uji pemulihan sebelum uninstall**, karena uninstall menghapus vault lokal dan pengaturan.

## Menggunakan Android

1. Buka Nusa Studio, ketuk logo 7 kali, dan buat kata sandi vault. Simpan kata sandi secara aman: tidak ada reset atau recovery key.
2. Pilih file atau folder melalui pemilih dokumen Android. Aplikasi meminta akses hanya ke pilihan itu. Saat kembali dari pemilih file, masukkan kata sandi kembali bila vault telah terkunci.
3. Di Pengaturan relay, masukkan origin HTTPS relay pribadi, misalnya `https://vault.domain-anda.com`, dan token relay terpisah dari kata sandi vault.
4. Pilih **Backup sekarang**. Untuk folder, aplikasi memeriksa isi folder lagi; versi dengan isi berubah disimpan sebagai backup baru. File sumber tidak dihapus atau ditimpa.
5. Opsional: aktifkan backup otomatis dan pilih hanya jaringan tanpa batas kuota. Pengguna menyetujui penyimpanan kata sandi background dalam Android Keystore. Android menjalankan tugas sekitar setiap 15 menit atau menundanya sesuai baterai, jaringan, dan kebijakan perangkat. Force-stop menghentikan tugas sampai aplikasi dibuka kembali.
6. Gunakan pencarian, Simpan salinan, Hapus lokal, dan Riwayat backup. Hapus lokal tidak menghapus backup relay. Simpan salinan menulis **plaintext** ke lokasi pilihan pengguna.

Vault terkunci setelah 3 menit idle dan saat Activity masuk background. Tampilan vault dilindungi `FLAG_SECURE` dari screenshot serta thumbnail recent apps. Izin notifikasi memungkinkan laporan aktivitas backup; aplikasi tidak mengakses SMS, lokasi, notifikasi aplikasi lain, atau remote control.

## Companion laptop

Butuh Node.js >=22. Server tidak memiliki dependency runtime.

```sh
cd /workspace/aplikasi
npm start
```

Server pengembangan mendengarkan localhost port 3000. `RELAY_TOKEN` acak minimal 24 karakter harus tersedia di environment proses untuk mengaktifkan API backup; tanpa token API menolak akses dengan HTTP 503. Jangan commit token atau menuliskannya dalam chat/log.

Untuk memulihkan di laptop, buka origin HTTPS relay yang sama, ketuk logo 7 kali, gunakan **kata sandi vault yang sama dengan Android**, isi token relay, lalu pilih Pulihkan dari relay. File Android dan browser memakai format enkripsi yang kompatibel. Unduh mengeluarkan salinan plaintext. IndexedDB browser hanya menyimpan envelope terenkripsi. Token dan kata sandi browser hanya berada dalam memori sesi.

Environment opsional: `PORT` (3000), `HOST` (127.0.0.1), `DATA_DIR` (`.vault-data` relatif direktori kerja). Proses server perlu dimulai ulang setelah task/cloud machine direstorasi.

## Relay HTTPS pada VPS pribadi

Butuh domain yang DNS-nya menunjuk ke VPS, Docker dengan Compose v2, disk persisten, serta port 80/443 yang dapat diakses. Perintah ini menyiapkan layanan; tidak ada VPS pengguna yang di-deploy otomatis oleh proyek.

```sh
npm run relay:init -- vault.domain-anda.com
docker compose -f deployment/compose.yaml up -d --build
```

`relay:init` membuat `deployment/.env` dengan token acak 256 bit dan mode file 0600. Perintah menolak menimpa file yang telah ada dan tidak mencetak token. Baca file itu secara privat untuk memasukkan token ke Android dan laptop. Caddy memperoleh sertifikat TLS dan meneruskan trafik ke relay; hanya Caddy membuka port publik. Jangan mengekspos HTTP relay langsung ke internet.

Volume `vault-data` berisi backup terenkripsi; volume Caddy menyimpan sertifikat. Simpan salinan volume tersebut secara terpisah. Jangan menjalankan `docker compose down -v` pada data yang perlu dipertahankan. Nama dan isi file dienkripsi sebelum sampai relay. Metadata trafik, ukuran envelope, ID acak, dan token koneksi tetap terlihat oleh relay.

## Build Android

Butuh JDK lengkap 17 atau lebih baru, Android SDK platform 35/build-tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1, dan akses Google Maven/Maven Central. Gunakan Android Studio atau:

```sh
npm ci
scripts/android-build.sh assembleDebug assembleAndroidTest lintDebug
scripts/package-android.sh
```

Gradle wrapper 8.13 menyertakan checksum distribusi. PBKDF2 native memakai BoringSSL resmi pada commit yang dipin; skrip fetch memverifikasi SHA-256 source archive. Library dibangun untuk arm64-v8a, armeabi-v7a, dan x86_64 dengan alignment halaman 16 KiB. Lisensi upstream ada di `android/THIRD_PARTY_NOTICES.txt`. Pada environment cloud ini, helper mengaktifkan toolchain yang sudah disiapkan di `/workspace/toolchains`. Di mesin lain, atur `JAVA_HOME` dan `ANDROID_HOME` sesuai instalasi. `scripts/package-android.sh` membuat kunci rilis lokal sekali di `.local/signing` (atau `NUSA_SIGNING_DIR`) dan memakainya kembali. **Backup direktori signing secara privat**: kunci yang hilang membuat APK pembaruan tidak dapat menggantikan instalasi lama. Kunci, password, token, data vault, dan output build tidak masuk Git.

## Pengujian

```sh
npm ci
npm test
CHROMIUM_PATH=/usr/bin/chromium npm run test:browser
# Mulai emulator disposable, tunggu boot selesai, lalu:
npm run test:android
# Setelah packaging, pada emulator disposable:
npm run test:release
npm run test:compatibility
```

Tes Android membutuhkan emulator disposable dan `openssl`. Skrip menghapus data aplikasi **pada emulator tersebut**, memasang APK app/test, membuat sertifikat TLS sementara yang dipercaya hanya untuk localhost pada debug build, lalu menjalankan instrumentasi native dan pemeriksaan hasil Android pada browser laptop. Tidak ada verifikasi TLS yang dinonaktifkan. Resource trust khusus tes dihapus setelah pengujian. APK rilis tidak menyertakannya. Jangan gunakan skrip ini untuk data pribadi di perangkat fisik.

`npm test` menjalankan tes integrasi HTTP relay. Tes browser menguji impor, backup, pemulihan pada konteks browser terpisah, isi unduhan, dan penolakan kata sandi salah. Tes native menguji enkripsi, data rusak, interoperabilitas Web Crypto, penyimpanan ulang, deduplikasi, Keystore, HTTPS relay, JobScheduler, serta company profile dan kunci UI. Lihat `TESTING.md` untuk pemeriksaan perangkat fisik dan cakupan yang belum otomatis.

## Batas yang masih berlaku

- Maksimum file asli 20 MiB, semua jenis file diperbolehkan. Envelope terenkripsi dibatasi 40 MiB. File diproses di memori; belum ada chunking, resume, atau dukungan file video besar di atas batas ini.
- Android Scoped Storage membatasi folder yang dapat dipilih. Data privat aplikasi lain, folder sistem, dan akses yang dilarang Android tidak dapat diambil. Ini bukan akses penuh ke seluruh filesystem.
- Satu folder dapat didaftarkan untuk pemindaian ulang otomatis; beberapa file dapat diimpor manual. File yang berubah menghasilkan versi baru. Tidak ada penghapusan versi relay melalui UI atau retensi otomatis; pantau kapasitas VPS.
- Browser hanya melakukan backup manual. Auto backup Android mengikuti penjadwalan sistem, bukan jaminan tepat waktu atau zero data loss.
- Single-user, satu token relay bersama. Pemegang token dapat mengambil ciphertext dan menggunakan API penghapusan; ganti token jika bocor. Belum ada rate limiting per pengguna atau kuota total penyimpanan dalam aplikasi.
- Tidak ada audit keamanan independen. Uji pemulihan dengan data non-sensitif dan simpan lebih dari satu backup sebelum mengandalkan aplikasi ini.
