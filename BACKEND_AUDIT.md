# Audit backend — 27 Oktober 2026

Backend relay vault menjalankan penyimpanan ciphertext, autentikasi token, backup bertahap, dan pengambilan data melalui HTTPS. Backend transaksi SiServis-PC (pendaftaran pelanggan/tiket, penyimpanan status teknisi, pembaruan status langsung) **belum diimplementasikan**: UI servis Android menggunakan tiga tiket dan statistik contoh, sama seperti prototipe sumber.

## Perbaikan hasil audit

- Saat mulai menerima request pertama, relay membersihkan file sementara `.upload-UUID` yang tertinggal akibat proses berhenti. File backup yang sudah diterbitkan melalui hard link tetap dipertahankan. Kuota dihitung kembali dari file permanen.
- `DELETE /api/files/:id` kini menghapus manifest dan chunk yang dimiliki record tersebut, sehingga kuota kembali tersedia. Tombol **Hapus lokal** pada aplikasi tetap hanya menghapus salinan lokal, bukan memanggil penghapusan relay.
- Compose lokal memiliki healthcheck HTTPS terautentikasi yang memeriksa token dan akses direktori data. `healthy` berarti endpoint data berhasil merespons, bukan sekadar proses hidup.
- Runtime Docker lokal menggunakan Node slim dan salinan OpenSSL/librarinya dari image Debian yang dipatok digest. Build tidak memerlukan `apt`. Stage sumber OpenSSL tetap diunduh saat build pertama; ini mengurangi ukuran runtime, bukan menghilangkan kebutuhan ruang build.
- Tes Docker lokal otomatis menggunakan project/volume sementara dan port host acak khusus tes. CI kini menjalankannya bersama tes container relay publik.

## Bukti pengujian

- 12 tes Node: autentikasi, rate limit, metadata invalid, immutable upload, upload bersamaan, kuota, pemulihan file sementara, penghapusan chunk, TLS/hostname, dan restore CLI >25 MiB.
- Browser Chromium: backup–restore file 25 MiB+17 byte dengan SHA-256 sama, format lama, folder, perbaikan chunk rusak, lock, pembatalan login, password salah.
- Docker lokal: root `compose.yaml` dibangun dan dijalankan, akses dari host melalui port publish, CA diverifikasi, token salah/CA tidak dikenal ditolak, unggahan dan pemulihan chunk terenkripsi 25 MiB+17 byte cocok. Setelah SIGKILL dan start kembali, CA, token dan isi backup tetap sama.
- Android API 26: 23 tes utama, pencabutan izin di proses terpisah, dan pemulihan backup Android melalui browser independen. Pengujian mencakup file besar, resume, sertifikat, sumber folder, background job, login dan lock.
- Container relay publik: nonroot/read-only, autentikasi, data tetap ada setelah restart, serta validasi konfigurasi Caddy. Domain publik dan penerbitan sertifikat ACME belum diuji.

Laporan eksekusi ada di `artifacts/backend-test-results.txt`, `local-container-test-results.txt`, `android-test-results.txt`, `web-test-results.txt`, dan `container-test-results.txt`.

Dua percobaan Docker/Android sempat terhalang disk environment cloud yang penuh. Ruang dibebaskan dari cache/toolchain pengujian. Percobaan pertama uji restart Docker juga menemukan kesalahan harness: Docker mengganti port host acak setelah restart; harness diperbaiki untuk membaca kembali port tersebut. Konfigurasi pengguna tetap memakai port 3443. Percobaan gagal tidak dihitung sebagai bukti lulus.

## Batas penggunaan nyata

- Uji ini memakai emulator dan container lokal, bukan HP fisik atau router pengguna. HP/laptop harus satu jaringan yang saling dapat menjangkau, TCP 3443 diizinkan firewall, dan CA lokal harus diimpor serta fingerprint-nya dicocokkan.
- Relay adalah penyimpanan satu pemilik dengan satu token. Jalankan **satu proses relay per direktori/volume data**, bukan beberapa replica yang menulis ke volume yang sama. Server tidak mengetahui kata sandi vault dan tidak bisa memulihkannya jika hilang.
- Chunk permanen dari unggahan yang belum selesai dipertahankan untuk resume dan tetap memakai kuota. Pembersihan file sementara tidak menghapus chunk backup tersebut. Belum ada UI retensi/garbage collection server otomatis.
- Lulus restart/SIGKILL bukan jaminan terhadap kerusakan disk, kehilangan laptop, atau seluruh skenario mati listrik. Backup volume Docker tetap diperlukan. Jangan memakai `docker compose down -v` untuk data yang ingin disimpan.
- Akses dari internet, remote layar HP, SMS, dan lokasi bukan kemampuan backend LAN ini. Untuk mengambil file dari laptop, file harus sudah di-backup oleh HP.

## Memasang pembaruan server

```sh
git pull --ff-only
docker compose up -d --build --wait
docker compose ps
```

Jalankan di direktori repositori yang sudah memiliki `.env` berisi `LAN_IP` laptop. Untuk mengambil alamat/token secara privat: `docker compose exec vault cat /data/connection.txt`. Jangan mengirim token atau kunci privat dalam chat/log publik. APK 1.2.0 yang sudah terpasang tetap kompatibel; audit ini tidak mengubah format backup maupun aplikasi produksi.
