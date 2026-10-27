// Masjid TV — Copyright 2026 Pokka99 — Apache License 2.0 (see LICENSE and NOTICE)
package my.masjid.tv;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.io.IOException;
import java.io.InputStream;

/**
 * Full-screen kiosk showing the mosque display page from assets/index.html, served from
 * https://appassets.androidplatform.net so live-stream embeds (YouTube refuses file:// pages) can play.
 */
public class MainActivity extends Activity {
    private WebView web;
    private WebServer server;
    private Bridge bridge;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);           // settings and the year's prayer times live in localStorage
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setAllowContentAccess(true);          // poster images are content:// URIs from MediaStore
        Store store = new Store(this);
        server = new WebServer(new WebServer.Backend() {
            @Override public byte[] asset(String name) throws IOException {
                try (InputStream in = getAssets().open(name)) {
                    return Store.readAll(in);
                }
            }
            @Override public String pin() { return store.pin(); }
            @Override public String config() { return store.config(); }
            @Override public void saveConfig(String json) throws IOException { store.saveConfig(json); }
            @Override public String postersJson() { return store.postersJson(); }
            @Override public byte[] poster(String name) throws IOException { return store.poster(name); }
            @Override public boolean addPoster(String name, byte[] data) throws IOException { return store.addPoster(name, data); }
            @Override public void deletePoster(String name) { store.deletePoster(name); }
            @Override public byte[] image(String name) throws IOException { return store.image(name); }
            @Override public String addImage(String name, byte[] data) throws IOException { return store.addImage(name, data); }
            @Override public void deleteImage(String name) { store.deleteImage(name); }
            @Override public void runJs(String js) { runOnUiThread(() -> web.evaluateJavascript(js, null)); }
            @Override public String currentSlide() { return bridge.currentSlide(); }
        });
        server.start();
        bridge = new Bridge(this, store, server);
        web.addJavascriptInterface(bridge, "Android");
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/posters/", new WebViewAssetLoader.InternalStoragePathHandler(this, store.posterDir()))
                .addPathHandler("/images/", new WebViewAssetLoader.InternalStoragePathHandler(this, store.imageDir()))
                .build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
        });
        web.setBackgroundColor(0xFF062A20);
        setContentView(web);
        hideBars();
        web.loadUrl(Bridge.ORIGIN + "/assets/index.html");

        String perm = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{perm}, 1);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP) {
            int k = e.getKeyCode();
            if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_SETTINGS || k == KeyEvent.KEYCODE_PROG_RED) {
                web.evaluateJavascript("openSettings()", null);
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    // BACK closes the settings panel; otherwise it is ignored so the display is not left by accident (HOME still exits).
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("onBack()", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        server.stop();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }
}
