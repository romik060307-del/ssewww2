# OHLI Social Client v3.7.1 — install compatibility fix

- Perangkat sasaran yang dilaporkan: Vivo Android Oreo (API 26). Minimum source tetap API 21, sehingga Oreo memenuhi minimum.
- Mengubah applicationId dari `com.rk.server` menjadi `com.ohli.social.client`. Ini mencegah konflik pembaruan/tanda tangan dengan APK server lama yang memakai `com.rk.server`. Keduanya bisa dipasang berdampingan.
- Menaikkan versionCode menjadi 58 dan versionName menjadi 3.7.1.
- Workflow dan pemeriksaan source diperbarui untuk package baru.

Catatan: ini memperbaiki satu penyebab pemasangan yang masuk akal, bukan bukti bahwa setiap APK hasil workflow telah berhasil dipasang. Jika masih gagal, minta APK yang gagal atau teks detail Android untuk memastikan penyebab lain (mis. APK rusak/tidak lengkap atau perangkat menolak sertifikat).
