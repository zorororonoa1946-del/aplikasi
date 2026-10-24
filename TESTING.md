# Pengujian instalasi dan backup

## Otomatis

- `npm test`: autentikasi relay, relay tanpa konfigurasi, validasi envelope, batas unggahan, round trip, penolakan overwrite, unggahan bersamaan, penghapusan. Tes helper installer juga memeriksa mode file kredensial 0600, tidak tercetaknya token, domain tidak valid, dan penolakan overwrite.
- `npm run test:browser`: tampilan HP, impor/enkripsi, backup, pemulihan di browser laptop terpisah, kesamaan byte unduhan, penguncian, kata sandi salah.
- `npm run test:android`: kompilasi dan instalasi APK app/test di emulator disposable; tes instrumentasi nyata, HTTPS relay dengan trust sertifikat localhost khusus tes, dan pemulihan hasil Android di browser laptop.
- `scripts/android-build.sh lintRelease`: pemeriksaan statis Android. Build yang diserahkan memiliki 0 temuan lint debug maupun release; jangan menonaktifkan pemeriksaan kesalahan.
- `scripts/package-android.sh`: build rilis, alignment APK, signature, verifikasi signature, dan checksum SHA-256.

Tes Android menghapus data package `id.nusastudio.vault` hanya pada emulator. Gunakan perangkat emulator khusus pengujian. Hasil akhir native dan interoperabilitas ditulis ke `artifacts/android-test-results.txt` setelah seluruh pemeriksaan berhasil; file yang tidak ada tidak membuktikan tes lulus.

## Perangkat fisik — checklist penerimaan

Tes ini memerlukan pemilik perangkat dan belum dapat digantikan emulator untuk kebijakan vendor/baterai:

1. Cocokkan SHA-256 APK dengan checksum, instal APK rilis, buka company profile, dan pastikan vault tidak tampil sebelum trigger serta kata sandi.
2. Buat vault baru. Salah kata sandi harus ditolak. Kunci, pindah aplikasi, dan tunggu idle >3 menit; vault harus kembali terkunci.
3. Impor file lewat pemilih Android. Batalkan pemilihan, coba izin ditolak/dicabut, file kosong, Unicode pada nama, dan file yang melebihi 20 MiB. File sumber harus tetap utuh.
4. Pilih folder yang memang dapat dipilih Android dan berisi subfolder. Uji pemindaian ulang, file baru, file berubah, file hilang, serta izin folder yang dicabut. Kegagalan harus dilaporkan dan backup sebelumnya tetap ada.
5. Konfigurasi relay HTTPS dan token. Coba token salah, sertifikat tidak tepercaya, jaringan putus, serta retry. Backup yang sudah ada tidak boleh ditimpa dengan isi berbeda.
6. Pulihkan di laptop dengan kata sandi yang sama, unduh file, dan bandingkan checksum dengan sumber. Coba kata sandi berbeda; dekripsi harus gagal.
7. Aktifkan backup otomatis dengan persetujuan eksplisit. Uji jaringan sesuai pilihan, jadwal tertunda karena baterai, notifikasi, reboot, dan force-stop. Menonaktifkan backup harus membatalkan job serta menghapus kata sandi background dari pengaturan terenkripsi.
8. Sebelum uninstall/update/signature change, buktikan pemulihan backup di perangkat kedua. Jangan menguji uninstall dengan satu-satunya salinan data pribadi.

## Deployment

Validasi Compose memakai `docker compose -f deployment/compose.yaml config --quiet` dengan variabel dummy pengujian; jangan mencetak konfigurasi berisi token asli. Uji volume persisten melalui restart container. Public DNS, penerbitan sertifikat publik, akses dari jaringan seluler, dan perilaku OEM Android memerlukan deployment/perangkat nyata.
