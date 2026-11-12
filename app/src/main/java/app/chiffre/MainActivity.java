package app.chiffre;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

/**
 * Chiffre: zeigt die mitgelieferte Web-App in einem WebView.
 *
 * - Optional meldet die App neutral, wenn eine Notiz abgelaufen ist (siehe Reminders).
 * - Die App hat keine Internet-Berechtigung. Alle Dateien kommen aus den Assets.
 * - Der Inhalt läuft unter einer festen https-Adresse, damit WebCrypto und localStorage
 *   wie im Browser funktionieren. Die Adresse wird nur lokal bedient, nie über das Netz.
 * - Jede Navigation und jede fremde Anfrage wird blockiert.
 * - Screenshots und die Vorschau in der App-Übersicht sind abgeschaltet (FLAG_SECURE).
 */
public class MainActivity extends Activity {

    /** ".invalid" ist reserviert und wird nie aufgelöst. */
    private static final String HOST = "chiffre.invalid";
    private static final String START_URL = "https://" + HOST + "/index.html";

    private static final int REQ_PICK = 1;
    private static final int REQ_SAVE = 2;
    private static final int REQ_NOTIFICATIONS = 3;

    /** Größte Sicherung, die gespeichert wird (Zeichen). */
    private static final int MAX_SAVE_CHARS = 8 * 1024 * 1024;

    /** So lange darf die App im Hintergrund sein, bevor sie sich sperrt. */
    private static final long LOCK_AFTER_MS = 60_000L;

    private WebView web;
    private ValueCallback<Uri[]> pickCallback;
    private String pendingSave;
    private long stoppedAt = 0L;
    private boolean bioBusy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        applyInsets(root);

        configureWebView();
        web.loadUrl(START_URL);
    }

    private void applyInsets(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                int types = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
                if (Build.VERSION.SDK_INT >= 35) {
                    types |= WindowInsets.Type.ime();
                }
                android.graphics.Insets i = insets.getInsets(types);
                left = i.left;
                top = i.top;
                right = i.right;
                bottom = i.bottom;
                v.setPadding(left, top, right, bottom);
                return WindowInsets.CONSUMED;
            }
            left = insets.getSystemWindowInsetLeft();
            top = insets.getSystemWindowInsetTop();
            right = insets.getSystemWindowInsetRight();
            bottom = insets.getSystemWindowInsetBottom();
            v.setPadding(left, top, right, bottom);
            return insets.consumeSystemWindowInsets();
        });
    }

    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setGeolocationEnabled(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("https".equals(u.getScheme()) && HOST.equals(u.getHost())
                        && "GET".equalsIgnoreCase(request.getMethod())) {
                    return loadAsset(u.getPath());
                }
                return emptyResponse(403, "Forbidden");
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // keine Navigation, auch nicht zu Links
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pickCallback != null) {
                    pickCallback.onReceiveValue(null);
                }
                pickCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                try {
                    startActivityForResult(intent, REQ_PICK);
                } catch (ActivityNotFoundException e) {
                    pickCallback = null;
                    callback.onReceiveValue(null);
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "ChiffreNative");
    }

    /** Liefert eine Datei aus den Assets, sonst 404. */
    private WebResourceResponse loadAsset(String path) {
        String name = (path == null || path.length() <= 1) ? "index.html" : path.substring(1);
        if (name.contains("..") || name.contains("//") || name.startsWith("/")) {
            return emptyResponse(404, "Not Found");
        }
        try {
            InputStream in = getAssets().open(name);
            return new WebResourceResponse(mimeType(name), encodingFor(name), in);
        } catch (IOException e) {
            return emptyResponse(404, "Not Found");
        }
    }

    private static WebResourceResponse emptyResponse(int status, String reason) {
        return new WebResourceResponse("text/plain", "utf-8", status, reason,
                Collections.<String, String>emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    private static String mimeType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".js")) return "text/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".json") || n.endsWith(".webmanifest")) return "application/manifest+json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private static String encodingFor(String name) {
        return mimeType(name).startsWith("image/") || "application/octet-stream".equals(mimeType(name))
                ? null : "utf-8";
    }

    /** Brücke für die Web-App. Nur Dateien der App selbst laufen im WebView. */
    private final class Bridge {
        @JavascriptInterface
        public void saveFile(String name, String text) {
            if (name == null || text == null || text.length() > MAX_SAVE_CHARS) {
                return;
            }
            final String safeName = name.replaceAll("[^A-Za-z0-9._-]", "_");
            final String content = text;
            runOnUiThread(() -> startSave(safeName, content));
        }

        @JavascriptInterface
        public boolean remindersEnabled() {
            return Reminders.isEnabled(MainActivity.this);
        }

        /** Schaltet die Erinnerung ein oder aus. Beim Einschalten wird ggf. die Erlaubnis erfragt. */
        @JavascriptInterface
        public void setReminders(final boolean on) {
            runOnUiThread(() -> changeReminders(on));
        }

        /** Künftige Zeitpunkte mit Art (e/w), kommagetrennt, in Millisekunden. Nie Text von Notizen. */
        @JavascriptInterface
        public void setExpiries(String csv) {
            if (csv != null && csv.length() > 20_000) {
                return;
            }
            Reminders.setTimes(MainActivity.this, csv);
        }

        /** "an;nächster;anzahl;erlaubt", nur Zeitpunkte und Zahlen, nie Text von Notizen. */
        @JavascriptInterface
        public String reminderStatus() {
            return Reminders.status(MainActivity.this);
        }

        /** Test-Benachrichtigung in einer Minute. 0 = geplant, 1 = Meldungen nicht erlaubt. */
        @JavascriptInterface
        public int testReminder() {
            return Reminders.scheduleTest(MainActivity.this) ? 0 : 1;
        }

        /** 0 = nicht möglich, 1 = kein Fingerabdruck eingerichtet, 2 = bereit. */
        @JavascriptInterface
        public int biometricStatus() {
            return Biometrics.status(MainActivity.this);
        }

        @JavascriptInterface
        public boolean biometricEnabled() {
            return Biometrics.isEnabled(MainActivity.this);
        }

        /** Merkt das Passwort, geschützt durch den Fingerabdruck. Das Passwort bleibt auf dem Gerät. */
        @JavascriptInterface
        public void enableBiometric(String password) {
            if (password == null || password.isEmpty() || password.length() > 1024) {
                return;
            }
            final byte[] secret = password.getBytes(StandardCharsets.UTF_8);
            runOnUiThread(() -> startBioEnable(secret));
        }

        @JavascriptInterface
        public void disableBiometric() {
            runOnUiThread(() -> {
                Biometrics.clear(MainActivity.this);
                tellBio(false, "");
            });
        }

        @JavascriptInterface
        public void unlockBiometric() {
            runOnUiThread(MainActivity.this::startBioUnlock);
        }
    }

    private void startBioEnable(byte[] secret) {
        if (bioBusy) {
            return;
        }
        bioBusy = true;
        Biometrics.enable(this, secret, new Biometrics.Callback() {
            @Override
            public void onSuccess(byte[] data) {
                bioBusy = false;
                tellBio(true, "");
            }

            @Override
            public void onFailure(int kind) {
                bioBusy = false;
                tellBio(false, kind == Biometrics.KIND_CANCELED ? "" : "error");
            }
        });
    }

    private void startBioUnlock() {
        if (bioBusy || web == null) {
            return;
        }
        bioBusy = true;
        Biometrics.unlock(this, new Biometrics.Callback() {
            @Override
            public void onSuccess(byte[] data) {
                bioBusy = false;
                String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
                Arrays.fill(data, (byte) 0);
                if (web != null) {
                    web.evaluateJavascript("window.chiffreBioUnlock && window.chiffreBioUnlock('"
                            + b64 + "');", null);
                }
            }

            @Override
            public void onFailure(int kind) {
                bioBusy = false;
                if (kind == Biometrics.KIND_INVALID) {
                    tellBio(false, "invalid");
                } else if (kind == Biometrics.KIND_ERROR) {
                    tellBio(Biometrics.isEnabled(MainActivity.this), "error");
                }
                // Abbruch durch dich: keine Meldung, das Passwort bleibt möglich.
            }
        });
    }

    private void tellBio(boolean enabled, String code) {
        if (web != null) {
            web.evaluateJavascript("window.chiffreBioState && window.chiffreBioState("
                    + enabled + ",'" + code + "');", null);
        }
    }

    private void changeReminders(boolean on) {
        if (!on) {
            Reminders.setEnabled(this, false);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS },
                    REQ_NOTIFICATIONS);
            return;
        }
        finishEnable();
    }

    private void finishEnable() {
        boolean ok = Reminders.canNotify(this);
        Reminders.setEnabled(this, ok);
        tellWeb(ok, !ok);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) {
            finishEnable();
        }
    }

    private void tellWeb(boolean on, boolean denied) {
        if (web != null) {
            web.evaluateJavascript("window.chiffreReminderState && window.chiffreReminderState("
                    + on + "," + denied + ");", null);
        }
    }

    private void startSave(String name, String text) {
        pendingSave = text;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(intent, REQ_SAVE);
        } catch (ActivityNotFoundException e) {
            pendingSave = null;
            Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SAVE) {
            String text = pendingSave;
            pendingSave = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && text != null) {
                writeText(data.getData(), text);
            }
        } else if (requestCode == REQ_PICK) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[] { data.getData() };
            }
            if (pickCallback != null) {
                pickCallback.onReceiveValue(result);
                pickCallback = null;
            }
        }
    }

    private void writeText(final Uri uri, final String text) {
        new Thread(() -> {
            boolean ok = false;
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out != null) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    ok = true;
                }
            } catch (IOException | RuntimeException e) {
                ok = false;
            }
            final boolean done = ok;
            runOnUiThread(() -> Toast.makeText(this,
                    done ? R.string.backup_saved : R.string.backup_failed,
                    Toast.LENGTH_SHORT).show());
        }).start();
    }

    /** Zurück schließt zuerst die Einstellungen in der Web-App, sonst verhält sich die App wie üblich. */
    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("window.chiffreBack ? window.chiffreBack() : false", value -> {
            if (!"true".equals(value)) {
                MainActivity.super.onBackPressed();
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        stoppedAt = SystemClock.elapsedRealtime();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (stoppedAt != 0L && web != null
                && SystemClock.elapsedRealtime() - stoppedAt >= LOCK_AFTER_MS) {
            web.evaluateJavascript("window.chiffreLock && window.chiffreLock();", null);
        }
        stoppedAt = 0L;
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
