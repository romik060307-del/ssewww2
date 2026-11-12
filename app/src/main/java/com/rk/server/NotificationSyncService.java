package com.rk.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps delivery receipts and incoming message/call alerts working after the WebView Activity
 * is moved to the background. This is a foreground polling fallback; FCM is still preferred
 * for delivery after force-stop/OEM process termination.
 */
public class NotificationSyncService extends Service {
    public static final String ACTION_START = "com.ohli.social.client.NOTIFICATION_SYNC_START";
    public static final String ACTION_STOP = "com.ohli.social.client.NOTIFICATION_SYNC_STOP";
    private static final String SYNC_PREFS = "ohli_native_background_sync";
    private static final String CHANNEL_SYNC = "ohli_sync";
    private static final int ONGOING_ID = 73001;
    private static final long POLL_SECONDS = 7L;
    public static volatile boolean isRunning = false;

    private ScheduledExecutorService executor;
    private final AtomicBoolean polling = new AtomicBoolean(false);

    public static void start(Context context) {
        try {
            Intent i = new Intent(context, NotificationSyncService.class).setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
            else context.startService(i);
        } catch (Exception ignored) { }
    }

    public static void stop(Context context) {
        try { context.stopService(new Intent(context, NotificationSyncService.class).setAction(ACTION_STOP)); }
        catch (Exception ignored) { }
    }

    @Override public void onCreate() {
        super.onCreate();
        createSyncChannel();
        startForeground(ONGOING_ID, buildOngoingNotification());
        isRunning = true;
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { pollSafely(); }
        }, 0L, POLL_SECONDS, TimeUnit.SECONDS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (ACTION_STOP.equals(intent == null ? null : intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        SharedPreferences session = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        if (session.getString("token", "").length() == 0 || session.getString("phone", "").length() == 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void pollSafely() {
        if (!polling.compareAndSet(false, true)) return;
        try {
            SharedPreferences session = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
            String token = session.getString("token", "");
            String phone = session.getString("phone", "");
            if (token.length() == 0 || phone.length() == 0 || !hasNetwork()) return;
            pollMessages(token, phone);
            pollCallSignals(token, phone);
        } catch (Exception ignored) {
        } finally { polling.set(false); }
    }

    private boolean hasNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= 23) {
                android.net.Network n = cm.getActiveNetwork();
                if (n == null) return false;
                android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
                return c != null && c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            NetworkInfo n = cm.getActiveNetworkInfo();
            return n != null && n.isConnected();
        } catch (Exception e) { return false; }
    }

    private JSONObject getJson(String relativePath, String token) {
        HttpURLConnection c = null;
        try {
            String encoded = URLEncoder.encode(token, "UTF-8");
            String separator = relativePath.contains("?") ? "&" : "?";
            URL url = new URL(MainActivity.SITE_URL + relativePath + separator + "token=" + encoded);
            c = (HttpURLConnection)url.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(6500);
            c.setReadTimeout(8500);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("X-RKM-Token", token);
            int status = c.getResponseCode();
            InputStream input = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
            if (input == null || status < 200 || status >= 300) return null;
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            StringBuilder body = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) body.append(line);
            reader.close();
            JSONObject json = new JSONObject(body.toString());
            return json.optBoolean("ok", true) ? json : null;
        } catch (Exception ignored) {
            return null;
        } finally { if (c != null) c.disconnect(); }
    }

    /** GET /api/messages also causes the server to set deliveredAt for this account. */
    private void pollMessages(String token, String phone) {
        JSONObject response = getJson("api/messages", token);
        if (response == null) return;
        JSONArray messages = response.optJSONArray("messages");
        if (messages == null) return;
        SharedPreferences prefs = getSharedPreferences(SYNC_PREFS, MODE_PRIVATE);
        Set<String> seen = readIdSet(prefs.getString("seen_message_ids", "[]"));
        boolean initialized = prefs.getBoolean("messages_initialized", false);
        boolean changed = false;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String recipient = m.optString("recipient", "");
            String sender = m.optString("sender", "");
            String id = m.optString("id", "");
            if (id.length() == 0 || !phone.equals(recipient) || phone.equals(sender)) continue;
            if (!seen.contains(id)) {
                if (initialized && !MainActivity.isForeground) showMessageNotification(m);
                seen.add(id); changed = true;
            }
        }
        if (!initialized || changed) {
            trimSet(seen, 600);
            prefs.edit().putBoolean("messages_initialized", true).putString("seen_message_ids", toJson(seen)).apply();
        }
    }

    private void pollCallSignals(String token, String phone) {
        SharedPreferences prefs = getSharedPreferences(SYNC_PREFS, MODE_PRIVATE);
        long since = prefs.getLong("last_call_signal_id", 0L);
        boolean initialized = prefs.getBoolean("calls_initialized", false);
        JSONObject response = getJson("api/calls?since=" + since, token);
        if (response == null) return;
        JSONArray signals = response.optJSONArray("signals");
        if (signals == null) signals = new JSONArray();
        long maxId = since;
        for (int i = 0; i < signals.length(); i++) {
            JSONObject signal = signals.optJSONObject(i);
            if (signal == null) continue;
            long id = signal.optLong("id", 0L);
            if (id > maxId) maxId = id;
            if (!initialized || id <= since || !"offer".equals(signal.optString("type"))) continue;
            String peer = signal.optString("from", "");
            if (peer.length() < 8 || peer.equals(phone)) continue;
            long age = System.currentTimeMillis() - signal.optLong("time", System.currentTimeMillis());
            if (age <= 90000L && !MainActivity.isForeground) showCallNotification(signal);
        }
        prefs.edit().putBoolean("calls_initialized", true).putLong("last_call_signal_id", maxId).apply();
    }

    private void showMessageNotification(JSONObject message) {
        String peer = message.optString("sender", "");
        String name = message.optString("senderName", peer);
        String text = message.optString("text", "");
        String type = message.optString("type", "text");
        if ("image".equals(type)) text = "Mengirim foto";
        else if ("video".equals(type)) text = "Mengirim video";
        else if ("voice".equals(type)) text = "Mengirim pesan suara";
        else if ("file".equals(type)) text = "Mengirim file";
        if (text.length() == 0) text = "Pesan baru";
        int id = stableId("message:" + peer);
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("ohli_peer", peer);
        PendingIntent openPi = PendingIntent.getActivity(this, id, open, pendingFlags(false));
        Notification.Builder b = notificationBuilder("ohli_messages")
                .setSmallIcon(R.drawable.ohli_notification_icon)
                .setContentTitle(name.length() == 0 ? "Pesan OHLI Social" : name)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setContentIntent(openPi);
        Intent reply = new Intent(this, NotificationReplyReceiver.class)
                .setAction(NotificationReplyReceiver.ACTION_REPLY)
                .putExtra(NotificationReplyReceiver.EXTRA_PEER, peer)
                .putExtra(NotificationReplyReceiver.EXTRA_NOTIFICATION_ID, id);
        PendingIntent replyPi = PendingIntent.getBroadcast(this, id + 1, reply, pendingFlags(true));
        RemoteInput remote = new RemoteInput.Builder(NotificationReplyReceiver.KEY_TEXT_REPLY)
                .setLabel("Tulis balasan…").build();
        Notification.Action action = new Notification.Action.Builder(android.R.drawable.ic_menu_send, "Balas", replyPi)
                .addRemoteInput(remote).build();
        b.addAction(action);
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(id, b.build());
    }

    private void showCallNotification(JSONObject signal) {
        String peer = signal.optString("from", "");
        String name = signal.optString("fromName", peer);
        JSONObject payload = signal.optJSONObject("payload");
        String media = payload == null ? "audio" : payload.optString("media", "audio");
        boolean video = "video".equalsIgnoreCase(media);
        String callId = signal.optString("callId", "");
        int id = stableId("call:" + callId);
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("ohli_peer", peer)
                .putExtra("ohli_incoming_call", true)
                .putExtra("ohli_call_id", callId);
        PendingIntent openPi = PendingIntent.getActivity(this, id + 2, open, pendingFlags(false));
        Notification.Builder b = notificationBuilder("ohli_calls")
                .setSmallIcon(R.drawable.ohli_notification_icon)
                .setContentTitle(video ? "Video call masuk · " + name : "Panggilan masuk · " + name)
                .setContentText(video ? "Panggilan video OHLI Social" : "Panggilan suara OHLI Social")
                .setCategory(Notification.CATEGORY_CALL)
                .setPriority(Notification.PRIORITY_MAX)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_menu_view, "Buka untuk menjawab", openPi);
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(id, b.build());
    }

    private void createSyncChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_SYNC, "Koneksi latar belakang OHLI", NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("Menjaga pesan OHLI tetap tersinkron saat aplikasi tidak dibuka.");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildOngoingNotification() {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, ONGOING_ID, open, pendingFlags(false));
        return notificationBuilder(CHANNEL_SYNC)
                .setSmallIcon(R.drawable.ohli_notification_icon)
                .setContentTitle("OHLI Social aktif")
                .setContentText("Menjaga pesan dan panggilan tetap tersinkron")
                .setOngoing(true).setShowWhen(false).setContentIntent(pi)
                .build();
    }

    private Notification.Builder notificationBuilder(String channel) {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(this, channel);
        return new Notification.Builder(this);
    }
    private int pendingFlags(boolean mutable) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= mutable ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE;
        else if (!mutable && Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return flags;
    }
    private int stableId(String value) { return value == null ? 73002 : (value.hashCode() & 0x7fffffff); }
    private Set<String> readIdSet(String raw) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        try { JSONArray a = new JSONArray(raw); for (int i=0;i<a.length();i++) { String v=a.optString(i, ""); if(v.length()>0)set.add(v); } }
        catch (Exception ignored) { }
        return set;
    }
    private void trimSet(Set<String> set, int max) { while (set.size() > max) set.remove(set.iterator().next()); }
    private String toJson(Set<String> set) { JSONArray a = new JSONArray(); for (String s : set) a.put(s); return a.toString(); }

    @Override public void onDestroy() {
        isRunning = false;
        if (executor != null) { executor.shutdownNow(); executor = null; }
        try { stopForeground(true); } catch (Exception ignored) { }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
