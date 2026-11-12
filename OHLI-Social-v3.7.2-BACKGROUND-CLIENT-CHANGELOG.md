# OHLI Social Client v3.7.2 — Background & Media Reliability

## Perubahan source
- Menambahkan Android foreground `NotificationSyncService` untuk memeriksa `/api/messages` dan `/api/calls` setelah login, termasuk saat Activity/WebView berada di latar belakang. Interval fallback polling saat ini 7 detik.
- GET `/api/messages` memakai token pada query dan header. Pada backend Java yang disertakan, permintaan ini juga memberi `deliveredAt`; karena itu, jika backend online menerapkan perilaku yang sama, sisi pengirim dapat menampilkan centang dua abu-abu setelah perangkat penerima mengambil pesan.
- Pesan baru menghasilkan notifikasi Android native dengan aksi balas langsung. Balasan tetap diproses oleh `NotificationReplyReceiver`; `PendingIntent` balasan dibuat mutable pada Android 8–11 agar input balasan dapat diteruskan, dan memakai flag mutable eksplisit pada Android 12 ke atas.
- Tawaran panggilan masuk menghasilkan notifikasi panggilan native. Mengetuk notifikasi membuka aplikasi dan memberi kesempatan WebView mengambil offer panggilan yang masih berlaku.
- Service foreground memiliki notifikasi kecil tetap `OHLI Social aktif`; ini diperlukan untuk fallback polling pada Android versi lama dan dapat menambah penggunaan baterai/data.
- Notifikasi WebView ditekan saat service native aktif agar tidak ganda.
- Handler izin WebView kini hanya memberikan izin kamera/mikrofon yang memang diminta dan didukung, setelah izin Android aplikasi tersedia; permintaan dari origin lain ditolak.
- Long-press pada foto/gambar/video tidak lagi memanggil menu konteks gambar bawaan WebView.
- Menambahkan pemulihan ketika renderer WebView dihentikan Android.
- Video sosial mencoba diputar dengan suara secara default; pengaturan native WebView juga mengizinkan playback tanpa gestur tambahan.

## Batas yang harus diketahui
- Background sync di versi ini adalah fallback polling foreground service, **bukan FCM**. Ia bekerja selama Android mempertahankan proses/service. Force-stop, pembatasan baterai/auto-start Vivo, atau penghentian proses oleh sistem dapat menghentikan notifikasi. Untuk penerimaan ala WhatsApp yang benar-benar andal tanpa service polling permanen, perlu Firebase Cloud Messaging (`FirebaseMessagingService`) dan endpoint backend online untuk mengirim push ke token perangkat. ZIP ini belum menyertakan Firebase project config atau kredensial pengirim.
- Polling 7 detik adalah kompromi untuk uji fungsi, bukan rancangan akhir produksi; ia menghasilkan permintaan jaringan berkala dan bisa memakai baterai/data lebih banyak. FCM lebih tepat untuk rilis stabil.
- GET `/api/messages` hanya akan mengubah status menjadi terkirim bila endpoint online `/api/messages` benar-benar memakai kontrak yang sama. Website Netlify statis tidak otomatis menjadi backend API; ZIP ini tidak menambahkan Netlify Functions atau proxy `/api/*`.
- Konfigurasi WebRTC yang ada hanya memakai STUN, belum TURN. Panggilan pada sebagian jaringan seluler/Wi-Fi memerlukan TURN relay dengan kredensial sah. ZIP sumber tidak menyertakan kredensial TURN atau implementasi backend signaling online, sehingga koneksi video call belum dapat dijamin hanya lewat perubahan APK.
- LIVE yang ada memakai peer-to-peer dan tidak memiliki SFU relay. Siaran multi-penonton yang stabil memerlukan SFU/media relay bila backend saat ini belum menyediakannya.
- Izin Android dan grant WebView sudah disiapkan, tetapi panggilan/LIVE tetap harus diuji pada perangkat dan origin online yang sesungguhnya. Jika WebView masih menampilkan pesan izin browser, perlu logcat dan pemeriksaan asal permintaan WebView untuk menemukan titik penolakannya.
- Android WebView masih dapat menolak autoplay audio tergantung versi WebView/perangkat. Source kini tidak memaksa video sosial tetap mute; beberapa perangkat tetap dapat membutuhkan sentuhan pertama pada video untuk mengaktifkan suara.
- Penghalang menu konteks dan pemulihan renderer WebView mengurangi sebagian sumber gangguan saat menahan gambar, tetapi belum membuktikan semua kasus freeze karena tekanan memori telah selesai.
