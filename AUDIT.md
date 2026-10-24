# Audit kesesuaian PRD

Baseline GitHub: `0e1066691dd2a97131d2878eb5ad2f1ccaa1d7a5`.

## Temuan dan perbaikan rilis 1.1.0

| Persyaratan Tier 1 | Temuan baseline | Perbaikan implementasi |
| --- | --- | --- |
| File tanpa pembatasan kategori | Semua jenis diterima, tetapi batas 20 MiB dan pemrosesan seluruh file dalam RAM menghalangi media besar | Transfer/enkripsi dalam chunk dengan memori terbatas; format lama tetap terbaca |
| Pilih beberapa file/folder untuk backup otomatis | Hanya satu folder; file impor tidak dipindai ulang otomatis | Daftar sumber dengan izin persisten, beberapa folder/file, pengelolaan sumber |
| Backup lokal ketika belum mempunyai VPS | Hanya HTTPS relay publik, belum ada setup lokal/pairing sertifikat | Relay HTTPS lokal pribadi di laptop dan trust sertifikat eksplisit pada Android |
| Browse/cari file/folder | Daftar datar | Navigasi folder dan pencarian |
| Riwayat: file, waktu, berhasil/gagal | Log sebagian besar hanya jumlah file; error satu sumber menghentikan backup lain | Log per file dan kelanjutan pada sumber sehat dengan hasil parsial yang jujur |
| Enkripsi, lock, data tidak rusak | AES-GCM/PBKDF2 ada, tetapi validasi restore/browser dan race pembatalan perlu diperkuat | Validasi manifest/chunk/ukuran, uji data rusak dan operasi yang dibatalkan |
| Backup tidak tertimpa/data hilang | Publish atomik ada; belum ada protokol resume untuk file besar | Chunk immutable, manifest dipublikasikan terakhir, retry memeriksa isi yang sama |
| Install/build/CI | Artefak ada; CI belum dibuktikan di GitHub; hasil lama tidak membuktikan perubahan baru | Build ulang, tes regresi, paket/signature/checksum baru dan verifikasi remote |

Fitur Tier 2 seperti SMS, notifikasi, lokasi, remote control dan remote wipe dinyatakan opsional dalam PRD dan tidak diaktifkan diam-diam. Deploy internet membutuhkan VPS/domain yang pengguna belum miliki; paket instalasi mandiri tetap disediakan. Android tetap membatasi data privat aplikasi lain dan beberapa folder sistem.

Perubahan implementasi tersedia dalam source; laporan pengujian terbaru ada di VALIDATION.md dan artifacts. Upgrade menggunakan versionCode 2 dan sertifikat rilis yang sama dengan 1.0.0. Skrip pengujian yang menghapus data hanya menerima emulator disposable dengan NUSA_DISPOSABLE_EMULATOR=1.

## Batas penerimaan

- Semua kategori file diterima dan file besar memakai chunk 4 MiB; batas praktis mengikuti kapasitas/kuota, bukan janji ukuran tanpa batas.
- Sumber otomatis dapat berupa beberapa file/folder SAF atau seluruh penyimpanan bersama setelah persetujuan. Data privat aplikasi lain tetap mengikuti batas Android.
- Akses internet disiapkan melalui paket VPS/Caddy. Pengguna belum memiliki VPS/domain sehingga koneksi publik nyata belum dapat divalidasi; jalur HTTPS LAN dapat dipasang tanpa itu.
- Target >95% sukses dan zero data loss adalah sasaran PRD, bukan jaminan yang dapat dibuktikan oleh tes lokal. Tes memeriksa putus jaringan, resume, korupsi, konflik, kuota, kesamaan byte dan upgrade. Simpan lebih dari satu backup.
- Peringatan lint ScopedStorage dipertahankan: izin all-files terkait kebijakan Play Store. Paket ditujukan untuk sideload pribadi, sesuai PRD.
- CI baseline GitHub gagal. Workflow diperbaiki dengan action terpin, runner tetap, SDK, deteksi KVM dan fallback emulator software; status run untuk setiap commit tersedia pada tab Actions GitHub. Tes lokal tidak dianggap bukti CI GitHub lulus.

CI terakhir yang diverifikasi: https://github.com/zorororonoa1946-del/aplikasi/actions/runs/40074751665 — **web dan Android lulus**, kode `114d9e892d6c858fd18e908beecbcaa5577be909`. Hasil lokal terbaru: 21 pemeriksaan native utama + 1 pencabutan izin di proses baru, 9 tes Node, browser file besar/format lama/perbaikan korupsi, container, APK produksi dan upgrade bertanda tangan.
