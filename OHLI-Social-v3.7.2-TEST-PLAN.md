# OHLI Social Client v3.7.2 — Rencana pengujian perangkat

1. Build `app-debug.apk` melalui GitHub Actions lalu instal di Vivo Android Oreo.
2. Login sekali; pastikan muncul notifikasi tetap kecil `OHLI Social aktif` selama fallback sync berjalan.
3. Pindahkan OHLI ke latar belakang memakai tombol Home (jangan force-stop). Dari HP lain, kirim pesan teks. Periksa apakah notifikasi muncul dan tombol Balas dapat mengirim balasan.
4. Saat OHLI tetap di latar belakang, kirim pesan lagi dan periksa apakah sisi pengirim berubah menjadi centang dua abu-abu setelah service penerima mengambil pesan dari `/api/messages`.
5. Lakukan panggilan suara dan video dari HP lain. Periksa apakah notifikasi panggilan muncul; ketuk notifikasi dan periksa apakah offer masuk tampil di OHLI.
6. Tahan lama foto feed, foto Story, foto chat, dan video. Menu konteks gambar bawaan tidak boleh muncul; aplikasi tetap responsif.
7. Putar video feed/reel/Story dan periksa audionya. Periksa juga kontrol suara OHLI jika aturan autoplay WebView perangkat tetap membisukan video.
8. Uji kamera/mikrofon dari fitur panggilan dan LIVE. Bila masih muncul pesan izin browser, ambil logcat dan catat URL/halaman saat permintaan izin berlangsung.
9. Uji panggilan dan LIVE melalui Wi-Fi serta data seluler pada dua jaringan berbeda. Jika ICE berstatus `failed`, siapkan TURN relay di backend media/signaling; STUN saja tidak cukup pada semua NAT operator.
10. Di Vivo, izinkan autostart/aktivitas latar belakang OHLI dan hilangkan pembatasan baterai bila OS menghentikan foreground service. Uji setelah layar mati dan setelah aplikasi disapu dari Recents. Force-stop tetap menghentikan penerimaan latar belakang sampai aplikasi dibuka lagi.
11. Setelah semua fungsi diperiksa, pantau pemakaian baterai/data karena polling fallback berjalan setiap 7 detik. Untuk rilis produksi, ganti fallback polling dengan FCM + backend push sender.
