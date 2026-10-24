# Nusa Studio — Personal Vault & Backup

Aplikasi Android native dan companion browser untuk vault pribadi serta backup satu arah ke relay milik sendiri. Halaman awal berupa company profile Nusa Studio. Ketuk logo **nusa 7 kali dalam 3 detik**, lalu buat/buka vault dengan kata sandi minimal 12 karakter. Tidak ada kata sandi bawaan.

Unduhan lengkap: `artifacts/nusa-vault-installation-1.1.0.zip`. Mulai dari INSTALL.md; hasil pengujian dan batas verifikasinya ada di VALIDATION.md.

## Paket Android

- Minimum Android 8.0 / API 26; target Android 15 / API 35.
- APK rilis: `artifacts/nusa-vault-1.1.0.apk`, tersedia setelah `scripts/package-android.sh` berhasil. Checksum dan informasi signature ada di direktori yang sama.
- Instal APK melalui file manager Android dan izinkan instalasi dari sumber tersebut, atau gunakan `adb install -r artifacts/nusa-vault-1.1.0.apk`.
- Build debug dan rilis memakai tanda tangan berbeda. Beralih dari debug ke rilis memerlukan uninstall; **backup dan uji pemulihan sebelum uninstall**, karena uninstall menghapus vault lokal dan pengaturan.

## Menggunakan Android

1. Buka Nusa Studio, ketuk logo 7 kali, dan buat kata sandi vault. Simpan kata sandi secara aman: tidak ada reset atau recovery key.
2. Pilih file atau folder melalui pemilih dokumen Android. Beberapa file/folder dapat menjadi sumber otomatis. Opsional, pilih seluruh penyimpanan bersama dan setujui izin khusus Android; izin ini tidak membuka data privat aplikasi lain. Saat kembali dari pemilih file, masukkan kata sandi kembali bila vault telah terkunci.
3. Di Pengaturan relay, masukkan origin HTTPS relay pribadi, misalnya `https://vault.domain-anda.com`, dan token relay terpisah dari kata sandi vault.
4. Pilih **Backup sekarang**. Aplikasi memeriksa ulang semua sumber file/folder yang dipilih; versi dengan isi berubah disimpan sebagai backup baru. File sumber tidak dihapus atau ditimpa.
5. Opsional: aktifkan backup otomatis dan pilih hanya jaringan tanpa batas kuota. Pengguna menyetujui penyimpanan kata sandi background dalam Android Keystore. Android menjalankan tugas sekitar setiap 15 menit atau menundanya sesuai baterai, jaringan, dan kebijakan perangkat. Force-stop menghentikan tugas sampai aplikasi dibuka kembali.
6. Gunakan navigasi folder, pencarian, Simpan salinan, Hapus lokal, dan Riwayat backup. Hapus lokal tidak menghapus backup relay. Simpan salinan menulis **plaintext** ke lokasi pilihan pengguna.

Vault terkunci setelah 3 menit idle dan saat Activity masuk background. Tampilan vault dilindungi `FLAG_SECURE` dari screenshot serta thumbnail recent apps. Izin notifikasi memungkinkan laporan aktivitas backup; aplikasi tidak mengakses SMS, lokasi, notifikasi aplikasi lain, atau remote control.

## Companion laptop

Butuh Node.js >=22. Server tidak memiliki dependency runtime.

```sh
cd /workspace/aplikasi
npm start
```

Server pengembangan mendengarkan localhost port 3000. `RELAY_TOKEN` acak minimal 24 karakter harus tersedia di environment proses untuk mengaktifkan API backup; tanpa token API menolak akses dengan HTTP 503. Jangan commit token atau menuliskannya dalam chat/log.

Untuk memulihkan di laptop, buka origin HTTPS relay yang sama, ketuk logo 7 kali, gunakan **kata sandi vault yang sama dengan Android**, isi token relay, lalu pilih Pulihkan dari relay. File Android dan browser memakai format enkripsi yang kompatibel. Unduh mengeluarkan salinan plaintext. IndexedDB browser hanya menyimpan envelope terenkripsi. Token dan kata sandi browser hanya berada dalam memori sesi.

Environment opsional: `PORT` (3000), `HOST` (127.0.0.1), `DATA_DIR` (`.vault-data` relatif direktori kerja), `MAX_STORAGE_BYTES` (10 GiB). Kuota penuh menolak unggahan baru dengan HTTP 507 dan mempertahankan backup sebelumnya. `TLS_CERT_FILE` dan `TLS_KEY_FILE` mengaktifkan HTTPS langsung. Proses server perlu dimulai ulang setelah task/cloud machine direstorasi.

## Tanpa VPS: relay HTTPS di laptop melalui WiFi

Pasang Node.js >=22 dan OpenSSL. Hubungkan HP/laptop ke jaringan yang sama, cari IPv4 pribadi laptop, lalu jalankan:

```sh
npm run relay:local -- 192.168.1.10
```

Ganti IP contoh dengan IP laptop. Helper membuat identitas TLS privat dan token di `.local/local-relay`; token hanya ada di `connection.txt`, tidak dicetak ke terminal. Izinkan koneksi LAN ke TCP 3443 pada firewall laptop. Di Android, masukkan alamat/token tersebut dan impor **sertifikat publik `ca.pem`** melalui pengaturan relay. Cocokkan fingerprint SHA-256 dengan laptop sebelum menyetujui. Jangan salin `ca-key.pem` atau `server-key.pem` ke HP. Sertifikat dipercaya khusus koneksi relay aplikasi; hostname tetap diperiksa.

Untuk companion browser, pasang CA publik itu sebagai CA tepercaya di browser/sistem laptop sesuai pengaturan browser Anda, lalu buka `https://192.168.1.10:3443`. Alternatif tanpa memasang CA ke sistem: gunakan helper restore berikut; ia mempercayai hanya CA yang Anda berikan. Jangan melewati peringatan TLS atau menonaktifkan verifikasi sertifikat.

```sh
npm run restore -- --connection .local/local-relay/connection.txt --ca .local/local-relay/ca.pem --out restored-vault
```

Kata sandi diminta tanpa ditampilkan. Helper menulis salinan plaintext secara streaming setelah verifikasi, mempertahankan folder/versi dan tidak menimpa file yang berbeda. Lindungi folder hasil restore. Untuk relay VPS gunakan `--url https://vault.domain-anda.com --env-file deployment/.env`. LAN hanya dapat diakses saat laptop menyala dan HP dapat menjangkaunya. Untuk akses dari luar rumah, pasang paket VPS di bawah. Jangan port-forward relay lokal sebagai pengganti konfigurasi publik tersebut.

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
NUSA_DISPOSABLE_EMULATOR=1 npm run test:android
# Setelah packaging, pada emulator disposable:
NUSA_DISPOSABLE_EMULATOR=1 npm run test:release
NUSA_DISPOSABLE_EMULATOR=1 npm run test:compatibility
```

Tes Android membutuhkan emulator disposable dan `openssl`. Skrip menghapus data aplikasi **pada emulator tersebut**, memasang APK app/test, membuat sertifikat TLS sementara yang dipercaya hanya untuk localhost pada debug build, lalu menjalankan instrumentasi native dan pemeriksaan hasil Android pada browser laptop. Tidak ada verifikasi TLS yang dinonaktifkan. Resource trust khusus tes dihapus setelah pengujian. APK rilis tidak menyertakannya. Jangan gunakan skrip ini untuk data pribadi di perangkat fisik.

`npm test` menjalankan tes integrasi HTTP relay. Tes browser menguji impor, backup, pemulihan pada konteks browser terpisah, isi unduhan, dan penolakan kata sandi salah. Tes native menguji enkripsi, data rusak, interoperabilitas Web Crypto, penyimpanan ulang, deduplikasi, Keystore, HTTPS relay, JobScheduler, serta company profile dan kunci UI. Lihat `TESTING.md` untuk pemeriksaan perangkat fisik dan cakupan yang belum otomatis.

## Batas yang masih berlaku

- File baru memakai chunk AES-256-GCM 4 MiB dengan UUID/index terikat sebagai AAD; metadata/kunci file dibungkus PBKDF2-SHA256 600.000 + AES-GCM. Batas lama 20 MiB dihapus; batas praktis mengikuti disk, kuota relay, kapasitas browser dan ukuran manifest maksimum 40 MiB. Backup format v1 tetap dapat dibaca.
- Android membatasi data privat aplikasi lain, folder sistem dan sebagian `Android/data`/`Android/obb`. Izin penyimpanan bersama bersifat opsional; sumber SAF tetap dapat dipakai tanpa izin itu.
- Browser melakukan backup manual. Unduhan browser di atas 64 MiB membutuhkan Chrome/Edge dengan File System Access; helper restore laptop dapat digunakan untuk streaming tanpa ketergantungan fitur browser itu.
- Backup otomatis Android mengikuti penjadwalan sistem dan kebijakan baterai. Ini bukan jaminan tepat waktu, >95% di semua jaringan, atau zero data loss. Periksa riwayat dan lakukan uji restore.
- Single-user, satu token relay bersama. Pemegang token dapat mengambil ciphertext dan menggunakan API penghapusan metadata. Ada pembatasan autentikasi gagal dan kuota total; tidak ada retensi/garbage collection otomatis. Chunk unggahan terputus dipertahankan untuk resume dan tetap memakai kuota.
- Riwayat terenkripsi menyimpan 500 aktivitas terbaru, termasuk nama file dan hasil parsial. File sumber tidak dihapus; perubahan isi menghasilkan versi baru. Hapus lokal tidak menghapus backup relay.
- Pengujian emulator tidak menggantikan pengujian HP fisik, jaringan seluler, atau deployment VPS publik. Tidak ada audit keamanan independen. Cakupan dan bukti aktual ada di VALIDATION.md.
