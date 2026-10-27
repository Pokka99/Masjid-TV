// Masjid TV — Copyright 2026 Pokka99 — Apache License 2.0 (see LICENSE and NOTICE)
package my.masjid.tv;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small HTTP server on the mosque Wi-Fi so a phone can edit the display (admin.html).
 * Plain Java with no Android imports, so it can be tried on a computer; Android specifics sit behind {@link Backend}.
 */
class WebServer {
    interface Backend {
        byte[] asset(String name) throws IOException;
        String pin();
        String config();                         // JSON, "" when never saved
        void saveConfig(String json) throws IOException;
        String postersJson();                    // JSON array of file names
        byte[] poster(String name) throws IOException;   // null when missing
        boolean addPoster(String name, byte[] data) throws IOException;
        void deletePoster(String name);
        byte[] image(String name) throws IOException;     // logo / ustaz photo, null when missing
        String addImage(String name, byte[] data) throws IOException;   // stored name, or null
        void deleteImage(String name);
        void runJs(String js);                   // on the TV page
        String currentSlide();                   // e.g. "kuliah"
    }

    private static final int MAX_BODY = 20 << 20;
    private static final int MAX_FAILS = 10;
    private static final long LOCK_MS = 5 * 60_000;

    private final Backend b;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private ServerSocket socket;
    private volatile int port = -1;
    private int fails;
    private long lockedUntil;

    WebServer(Backend b) {
        this.b = b;
    }

    int port() {
        return port;
    }

    /** Binds the first free port from 8080 and serves in the background. */
    void start() {
        for (int p = 8080; p < 8090 && socket == null; p++) {
            try {
                socket = new ServerSocket(p);
                port = p;
            } catch (IOException ignored) {
                // taken, try the next one
            }
        }
        if (socket == null) return;
        Thread t = new Thread(() -> {
            while (!socket.isClosed()) {
                try {
                    Socket s = socket.accept();
                    pool.execute(() -> handle(s));
                } catch (IOException e) {
                    return;
                }
            }
        }, "masjid-web");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = new BufferedOutputStream(sock.getOutputStream());
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] rl = lines[0].split(" ");
            if (rl.length < 2) return;
            Map<String, String> h = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) h.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
            }
            int len;
            try {
                len = Integer.parseInt(h.getOrDefault("content-length", "0"));
            } catch (NumberFormatException e) {
                len = -1;
            }
            if (len < 0 || len > MAX_BODY) {
                send(out, 413, "text/plain", bytes("Terlalu besar"));
                return;
            }
            byte[] body = new byte[len];
            for (int off = 0; off < len; ) {
                int n = in.read(body, off, len - off);
                if (n < 0) return;
                off += n;
            }
            String target = rl[1], path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }
            route(rl[0], path, params(query), h, body, out);
        } catch (Exception ignored) {
            // client went away or sent garbage
        }
    }

    private void route(String method, String path, Map<String, String> q, Map<String, String> h, byte[] body,
                       OutputStream out) throws IOException {
        if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
            send(out, 200, "text/html; charset=utf-8", b.asset("admin.html"));
            return;
        }
        if ("GET".equals(method) && path.startsWith("/poster/")) {
            String name = URLDecoder.decode(path.substring(8), "UTF-8");
            byte[] img = plainName(name) ? b.poster(name) : null;
            if (img == null) send(out, 404, "text/plain", bytes("Tiada"));
            else send(out, 200, mime(name), img, "max-age=86400");
            return;
        }
        if ("GET".equals(method) && path.startsWith("/image/")) {
            String name = URLDecoder.decode(path.substring(7), "UTF-8");
            byte[] img = plainName(name) ? b.image(name) : null;
            if (img == null) send(out, 404, "text/plain", bytes("Tiada"));
            else send(out, 200, mime(name), img, "max-age=86400");
            return;
        }
        if (!path.startsWith("/api/")) {
            send(out, 404, "text/plain", bytes("Tiada"));
            return;
        }
        if (!checkPin(h.get("x-pin"))) {
            boolean locked = System.currentTimeMillis() < lockedUntil;
            send(out, locked ? 429 : 401, "application/json", bytes(locked ? "{\"error\":\"locked\"}" : "{\"error\":\"pin\"}"));
            return;
        }
        String ok = "{\"ok\":true}";
        switch (method + " " + path) {
            case "GET /api/state": {
                String cfg = b.config();
                send(out, 200, "application/json", bytes("{\"config\":" + (cfg.isEmpty() ? "{}" : cfg)
                        + ",\"posters\":" + b.postersJson() + ",\"slide\":\"" + jsonSafe(b.currentSlide()) + "\"}"));
                return;
            }
            case "POST /api/config": {
                String json = new String(body, StandardCharsets.UTF_8).trim();
                if (!json.startsWith("{") || !json.endsWith("}")) {
                    send(out, 400, "text/plain", bytes("Bukan JSON"));
                    return;
                }
                b.saveConfig(json);
                b.runJs("applyConfig()");
                send(out, 200, "application/json", bytes(ok));
                return;
            }
            case "POST /api/poster":
                String name = q.getOrDefault("name", "poster.jpg");
                if (body.length == 0 || !plainName(name) || !b.addPoster(name, body)) {
                    send(out, 400, "text/plain", bytes("Fail tidak sah"));
                    return;
                }
                b.runJs("reloadPosters()");
                send(out, 200, "application/json", bytes(ok));
                return;
            case "POST /api/image": {
                String imgName = q.getOrDefault("name", "gambar.jpg");
                String stored = body.length == 0 || !plainName(imgName) ? null : b.addImage(imgName, body);
                if (stored == null) {
                    send(out, 400, "text/plain", bytes("Fail tidak sah"));
                    return;
                }
                send(out, 200, "application/json", bytes("{\"name\":\"" + jsonSafe(stored) + "\"}"));
                return;
            }
            case "DELETE /api/image":
                if (plainName(q.getOrDefault("name", ""))) b.deleteImage(q.get("name"));
                send(out, 200, "application/json", bytes(ok));
                return;
            case "DELETE /api/poster":
                if (plainName(q.getOrDefault("name", ""))) b.deletePoster(q.get("name"));
                b.runJs("reloadPosters()");
                send(out, 200, "application/json", bytes(ok));
                return;
            case "POST /api/demo":
                b.runJs("demo()");
                send(out, 200, "application/json", bytes(ok));
                return;
            case "POST /api/next":
                b.runJs("stepSlide(1)");
                send(out, 200, "application/json", bytes(ok));
                return;
            case "POST /api/prev":
                b.runJs("stepSlide(-1)");
                send(out, 200, "application/json", bytes(ok));
                return;
            case "POST /api/reload":
                b.runJs("fetchYear(true)");
                send(out, 200, "application/json", bytes(ok));
                return;
            default:
                send(out, 404, "text/plain", bytes("Tiada"));
        }
    }

    /** Ten wrong PINs lock the API for five minutes, so a six-digit PIN cannot be guessed over the Wi-Fi. */
    private synchronized boolean checkPin(String given) {
        long now = System.currentTimeMillis();
        if (now < lockedUntil) return false;
        if (b.pin().equals(given)) {
            fails = 0;
            return true;
        }
        if (++fails >= MAX_FAILS) {
            fails = 0;
            lockedUntil = now + LOCK_MS;
        }
        return false;
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        while (buf.size() < 16 * 1024) {
            int c = in.read();
            if (c < 0) return null;
            buf.write(c);
            // look for \r\n\r\n
            state = (c == '\r') ? (state == 2 ? 3 : 1) : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return buf.toString("ISO-8859-1");
        }
        return null;
    }

    private static Map<String, String> params(String query) {
        Map<String, String> m = new HashMap<>();
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            if (e <= 0) continue;
            try {
                m.put(URLDecoder.decode(kv.substring(0, e), "UTF-8"), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    /** Drops the characters that would need escaping inside a JSON string. */
    static String jsonSafe(String s) {
        return s == null ? "" : s.replaceAll("[\"\\\\\\p{Cntrl}]", "");
    }

    /** A bare file name: no folders, no leading dot. */
    static boolean plainName(String n) {
        return !n.isEmpty() && n.charAt(0) != '.' && n.indexOf('/') < 0 && n.indexOf('\\') < 0;
    }

    static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        send(out, code, type, body, "no-store");
    }

    private static void send(OutputStream out, int code, String type, byte[] body, String cache) throws IOException {
        String reason = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 404 ? "Not Found"
                : code == 413 ? "Payload Too Large" : code == 429 ? "Too Many Requests" : "Bad Request";
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: " + cache + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }
}
