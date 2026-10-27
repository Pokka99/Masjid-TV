// Masjid TV — Copyright 2026 Pokka99 — Apache License 2.0 (see LICENSE and NOTICE)
package my.masjid.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/** Settings, PIN and phone-uploaded posters, kept in the app's private storage. */
class Store {
    // Starts with a letter or digit and has no slash, so a name can never leave the posters folder.
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,120}\\.(jpe?g|png|webp|gif)",
            Pattern.CASE_INSENSITIVE);

    private final Context ctx;

    Store(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences("masjid", Context.MODE_PRIVATE);
    }

    synchronized String pin() {
        String p = prefs().getString("pin", null);
        if (p == null) {
            p = String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1_000_000));
            prefs().edit().putString("pin", p).apply();
        }
        return p;
    }

    synchronized String config() {
        File f = new File(ctx.getFilesDir(), "config.json");
        try {
            return f.isFile() ? new String(read(f), StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    synchronized void saveConfig(String json) throws IOException {
        write(new File(ctx.getFilesDir(), "config.json"), json.getBytes(StandardCharsets.UTF_8));
    }

    File posterDir() {
        File d = new File(ctx.getFilesDir(), "posters");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** Names in upload order (each starts with its upload time). */
    String[] posterNames() {
        String[] names = posterDir().list((dir, n) -> SAFE.matcher(n).matches());
        if (names == null) return new String[0];
        Arrays.sort(names);
        return names;
    }

    String postersJson() {
        return new JSONArray(Arrays.asList(posterNames())).toString();
    }

    byte[] poster(String name) throws IOException {
        if (!SAFE.matcher(name).matches()) return null;
        File f = new File(posterDir(), name);
        return f.isFile() ? read(f) : null;
    }

    boolean addPoster(String name, byte[] data) throws IOException {
        String clean = name.replaceAll("[^A-Za-z0-9._-]", "_");
        String file = System.currentTimeMillis() + "_" + clean;
        if (!SAFE.matcher(file).matches()) return false;
        write(new File(posterDir(), file), data);
        return true;
    }

    void deletePoster(String name) {
        if (SAFE.matcher(name).matches()) new File(posterDir(), name).delete();
    }

    /** Logo and ustaz photos, kept apart from posters so they never show up as poster slides. */
    File imageDir() {
        File d = new File(ctx.getFilesDir(), "images");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    byte[] image(String name) throws IOException {
        if (!SAFE.matcher(name).matches()) return null;
        File f = new File(imageDir(), name);
        return f.isFile() ? read(f) : null;
    }

    /** Saves under a new unique name and returns it, or null when the name is not allowed. */
    String addImage(String name, byte[] data) throws IOException {
        String file = System.currentTimeMillis() + "_" + name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!SAFE.matcher(file).matches()) return null;
        write(new File(imageDir(), file), data);
        return file;
    }

    void deleteImage(String name) {
        if (SAFE.matcher(name).matches()) new File(imageDir(), name).delete();
    }

    private static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void write(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
    }
}
