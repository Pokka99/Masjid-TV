// Masjid TV — Copyright 2026 Pokka99 — Apache License 2.0 (see LICENSE and NOTICE)
package my.masjid.tv;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/** window.Android on the TV page. */
class Bridge {
    static final String ORIGIN = "https://appassets.androidplatform.net";

    private final Context ctx;
    private final Store store;
    private final WebServer server;
    private volatile String slide = "";

    Bridge(Context ctx, Store store, WebServer server) {
        this.ctx = ctx;
        this.store = store;
        this.server = server;
    }

    @JavascriptInterface
    public String config() {
        return store.config();
    }

    @JavascriptInterface
    public void saveConfig(String json) {
        try {
            store.saveConfig(json);
        } catch (Exception ignored) {
        }
    }

    /** The page reports which slide is up, for the phone's slide controls. */
    @JavascriptInterface
    public void showing(String name) {
        slide = name == null ? "" : name;
    }

    String currentSlide() {
        return slide;
    }

    @JavascriptInterface
    public String pin() {
        return store.pin();
    }

    /** "http://192.168.1.50:8080", or "" when the TV has no network. */
    @JavascriptInterface
    public String adminUrl() {
        String ip = lanIp();
        return ip.isEmpty() || server.port() < 0 ? "" : "http://" + ip + ":" + server.port();
    }

    /** Phone uploads first, then images in any folder named "Poster" on the TV or a USB drive. */
    @JavascriptInterface
    public String posters() {
        List<String> out = new ArrayList<>();
        for (String n : store.posterNames()) {
            try {
                out.add(ORIGIN + "/posters/" + URLEncoder.encode(n, "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        TreeMap<String, String> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> volumes = Build.VERSION.SDK_INT >= 29
                ? MediaStore.getExternalVolumeNames(ctx) : Collections.singleton("external");
        String[] cols = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME};
        for (String vol : volumes) {
            Uri base = MediaStore.Images.Media.getContentUri(vol);
            // LIKE ignores case, so "poster", "Poster" and "POSTER" all count.
            try (Cursor c = ctx.getContentResolver().query(base, cols,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " LIKE ?", new String[]{"poster"}, null)) {
                while (c != null && c.moveToNext()) {
                    byName.put(c.getString(1) + "#" + vol, ContentUris.withAppendedId(base, c.getLong(0)).toString());
                }
            } catch (RuntimeException ignored) {
                // no permission yet, or the drive was pulled out
            }
        }
        out.addAll(byName.values());
        return new JSONArray(out).toString();
    }

    private static String lanIp() {
        try {
            String fallback = "";
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String name = ni.getName();
                    if (name.startsWith("wlan") || name.startsWith("eth")) return a.getHostAddress();
                    if (fallback.isEmpty()) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) {
            return "";
        }
    }
}
