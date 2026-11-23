package app.chiffre;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.StatFs;
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
import java.io.File;
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
    private static final int REQ_CAPTURE = 4;
    private static final int REQ_TREE = 5;
    private static final String PREFS_BK = "chiffre_bk";
    private static final String BK_FILE = "chiffre-sicherung.json";
    private static final String BK_TMP = "chiffre-sicherung-neu.json";

    /** Größte Sicherung, die gespeichert wird (Zeichen). */
    private static final int MAX_SAVE_CHARS = 32 * 1024 * 1024;

    /** Standard: So lange darf die App im Hintergrund sein, bevor sie sich sperrt. */
    private static final int DEFAULT_LOCK_SECONDS = 60;
    private static final String PREFS_LOCK = "chiffre_lock";

    private WebView web;
    private ValueCallback<Uri[]> pickCallback;
    private Uri captureUri;
    private String pendingSave;
    private boolean pendingExport;
    private long stoppedAt = 0L;
    /** Gilt nur für einen Rückweg: Dateiauswahl oder Speichern des Systems soll nicht sperren. */
    private boolean skipLockOnce = false;

    /** Kopierte Notiztexte werden nach dieser Zeit wieder aus der Zwischenablage entfernt. */
    private static final long CLIP_CLEAR_MS = 30_000L;
    private final Handler clipHandler = new Handler(Looper.getMainLooper());
    private int clipToken = 0;
    private long ownClipAt = 0L;
    private boolean clipListening = false;
    private boolean bioBusy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CaptureProvider.cleanAll(this);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        // Letzte Hintergrundfarbe der Seite sofort malen, damit beim Start nichts aufblitzt
        applyStoredLook();
        // Notizinhalt gehört nicht dem Autofill-Dienst des Systems; das spart außerdem den Aufbau beim ersten Fokus im Textfeld
        if (Build.VERSION.SDK_INT >= 26) {
            root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            web.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
        applyInsets(root);

        configureWebView();
        web.loadUrl(START_URL);
    }

    private static final String LOOK_PREFS = "chiffre_look";

    private boolean nightNow() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Malt beim Start die zuletzt gemeldete Farbe der Seite als Fensterhintergrund und für die Leisten. */
    @SuppressWarnings("deprecation")
    private void applyStoredLook() {
        try {
            SharedPreferences p = getSharedPreferences(LOOK_PREFS, MODE_PRIVATE);
            if (!p.contains("color")) {
                return;
            }
            boolean explicit = p.getBoolean("explicit", false);
            if (!explicit && p.getBoolean("sysnight", false) != nightNow()) {
                return; // Das System ist inzwischen zwischen Hell und Dunkel gewechselt
            }
            int color = p.getInt("color", 0);
            boolean dark = p.getBoolean("dark", false);
            getWindow().setBackgroundDrawable(new ColorDrawable(color));
            getWindow().setStatusBarColor(color);
            getWindow().setNavigationBarColor(color);
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) mask |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(dark ? (flags & ~mask) : (flags | mask));
        } catch (RuntimeException e) {
            // Optik ist nicht wichtig genug, um den Start zu stören
        }
    }

    /** Ab Android 13: Der Startbildschirm des Systems wird beim nächsten Start schwarz, wenn echtes Schwarz an ist. */
    @SuppressLint("NewApi")
    private void rememberSplash(boolean black) {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        try {
            getSplashScreen().setSplashScreenTheme(black ? R.style.Theme_Chiffre_SplashBlack : R.style.Theme_Chiffre);
        } catch (RuntimeException e) {
            // ohne Folgen
        }
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
                String[] types = params.getAcceptTypes();
                boolean images = types != null && types.length > 0 && types[0] != null
                        && types[0].startsWith("image/");
                Intent intent = null;
                int req = REQ_PICK;
                if (images && params.isCaptureEnabled()) {
                    try {
                        File file = CaptureProvider.newFile(MainActivity.this);
                        captureUri = CaptureProvider.uriFor(MainActivity.this, file);
                        intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        intent.putExtra(MediaStore.EXTRA_OUTPUT, captureUri);
                        intent.setClipData(ClipData.newRawUri("", captureUri));
                        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        req = REQ_CAPTURE;
                    } catch (RuntimeException e) {
                        intent = null;
                        captureUri = null;
                        req = REQ_PICK;
                    }
                }
                if (intent == null) {
                    intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(images ? "image/*" : "*/*");
                    if (images && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }
                }
                try {
                    skipLockOnce = true;
                    startActivityForResult(intent, req);
                } catch (ActivityNotFoundException e) {
                    skipLockOnce = false;
                    pickCallback = null;
                    captureUri = null;
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
            runOnUiThread(() -> startSave(safeName, content, false));
        }

        /** Klartext-Export als Markdown-Datei. Zählt nicht als Sicherung. */
        @JavascriptInterface
        public void saveTextFile(String name, String text) {
            if (name == null || text == null || text.length() > MAX_SAVE_CHARS) {
                return;
            }
            final String safeName = name.replaceAll("[^A-Za-z0-9._-]", "_");
            final String content = text;
            runOnUiThread(() -> startSave(safeName, content, true));
        }

        /** Wie lange die App im Hintergrund bleiben darf, bevor sie sich sperrt: 0, 60 oder 300 Sekunden. */
        @JavascriptInterface
        public void setLockDelay(int seconds) {
            int s = seconds <= 0 ? 0 : (seconds <= 60 ? 60 : 300);
            getSharedPreferences(PREFS_LOCK, MODE_PRIVATE).edit().putInt("seconds", s).apply();
        }

        @JavascriptInterface
        public int getLockDelay() {
            return lockDelaySeconds();
        }

        /** Freier Speicher im internen Speicher der App in MB, oder -1, wenn er sich nicht ermitteln lässt. */
        @JavascriptInterface
        public int freeSpaceMb() {
            try {
                return (int) Math.min(Integer.MAX_VALUE, new StatFs(getFilesDir().getPath()).getAvailableBytes() / (1024L * 1024L));
            } catch (RuntimeException e) {
                return -1;
            }
        }

        /** Sicherungsordner: öffnet die Ordnerauswahl von Android (die App sieht nur den gewählten Ordner). */
        @JavascriptInterface
        public void pickBackupDir() {
            runOnUiThread(() -> startPickTree());
        }

        /** Stand des Sicherungsordners als JSON: {set, name, state} mit state ok, away (nicht erreichbar) oder perm (Freigabe verloren). */
        @JavascriptInterface
        public String backupDirInfo() {
            return bkInfo();
        }

        @JavascriptInterface
        public void clearBackupDir() {
            bkClear();
        }

        /** Schreibt die Sicherung in den Ordner. Antwort: ok, none, perm, away oder err. */
        @JavascriptInterface
        public String writeBackupFile(String text) {
            if (text == null || text.length() > MAX_SAVE_CHARS) {
                return "err";
            }
            return bkWrite(text);
        }

        /** Kopiert einen Notiztext. Android blendet ihn nicht in der Vorschau ein und er wird nach 30 s geleert. */
        @JavascriptInterface
        public void copyText(final String text) {
            if (text == null || text.length() > 200_000) {
                return;
            }
            runOnUiThread(() -> copyToClipboard(text));
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

        /** Künftige Zeitpunkte mit Art (e/w/r), kommagetrennt, in Millisekunden. Nie Text von Notizen. */
        @JavascriptInterface
        public void setExpiries(String csv) {
            if (csv != null && csv.length() > 20_000) {
                return;
            }
            Reminders.setTimes(MainActivity.this, csv);
        }

        /**
         * Systemfarben (Material You) als JSON mit vier Farbreihen (Akzent 1, Akzent 2, neutral 1, neutral 2),
         * je 13 Töne. Leer vor Android 12. Nur Farbwerte, nichts aus dem Tresor.
         */
        /**
         * Darstellung (0 = System, 1 = Hell, 2 = Dunkel): Statusleiste und Navigationsleiste
         * passen sich der gewählten Darstellung an, sonst blieben sie in der Farbe des Systems.
         */
        /** Merkt die Farbe der Seite für den nächsten Start (leer = nichts merken, Standard gilt). */
        @JavascriptInterface
        public void rememberLook(final String hex, final boolean dark, final boolean explicit) {
            runOnUiThread(() -> {
                SharedPreferences.Editor e = getSharedPreferences(LOOK_PREFS, MODE_PRIVATE).edit();
                int color = 0;
                boolean ok;
                try {
                    color = Color.parseColor(hex);
                    ok = true;
                } catch (RuntimeException ex) {
                    ok = false;
                }
                if (ok) {
                    e.putInt("color", color).putBoolean("dark", dark).putBoolean("explicit", explicit)
                            .putBoolean("sysnight", nightNow());
                } else {
                    e.clear();
                }
                e.apply();
                rememberSplash(ok && color == 0xFF000000);
            });
        }

        @JavascriptInterface
        @SuppressWarnings("deprecation")
        public void setBars(final int mode, final String hex) {
            runOnUiThread(() -> {
                int color;
                boolean light;
                if (mode == 0) {
                    color = getColor(R.color.window_bg);
                    int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
                    light = night != Configuration.UI_MODE_NIGHT_YES;
                } else {
                    try {
                        color = Color.parseColor(hex);
                    } catch (RuntimeException e) {
                        return;
                    }
                    light = mode == 1;
                }
                getWindow().setStatusBarColor(color);
                getWindow().setNavigationBarColor(color);
                View decor = getWindow().getDecorView();
                int flags = decor.getSystemUiVisibility();
                int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) mask |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                decor.setSystemUiVisibility(light ? (flags | mask) : (flags & ~mask));
            });
        }

        @JavascriptInterface
        @SuppressLint("NewApi")
        public String systemColors() {
            if (Build.VERSION.SDK_INT < 31) {
                return "";
            }
            int[][] ids = {
                {android.R.color.system_accent1_0, android.R.color.system_accent1_10, android.R.color.system_accent1_50,
                 android.R.color.system_accent1_100, android.R.color.system_accent1_200, android.R.color.system_accent1_300,
                 android.R.color.system_accent1_400, android.R.color.system_accent1_500, android.R.color.system_accent1_600,
                 android.R.color.system_accent1_700, android.R.color.system_accent1_800, android.R.color.system_accent1_900,
                 android.R.color.system_accent1_1000},
                {android.R.color.system_accent2_0, android.R.color.system_accent2_10, android.R.color.system_accent2_50,
                 android.R.color.system_accent2_100, android.R.color.system_accent2_200, android.R.color.system_accent2_300,
                 android.R.color.system_accent2_400, android.R.color.system_accent2_500, android.R.color.system_accent2_600,
                 android.R.color.system_accent2_700, android.R.color.system_accent2_800, android.R.color.system_accent2_900,
                 android.R.color.system_accent2_1000},
                {android.R.color.system_neutral1_0, android.R.color.system_neutral1_10, android.R.color.system_neutral1_50,
                 android.R.color.system_neutral1_100, android.R.color.system_neutral1_200, android.R.color.system_neutral1_300,
                 android.R.color.system_neutral1_400, android.R.color.system_neutral1_500, android.R.color.system_neutral1_600,
                 android.R.color.system_neutral1_700, android.R.color.system_neutral1_800, android.R.color.system_neutral1_900,
                 android.R.color.system_neutral1_1000},
                {android.R.color.system_neutral2_0, android.R.color.system_neutral2_10, android.R.color.system_neutral2_50,
                 android.R.color.system_neutral2_100, android.R.color.system_neutral2_200, android.R.color.system_neutral2_300,
                 android.R.color.system_neutral2_400, android.R.color.system_neutral2_500, android.R.color.system_neutral2_600,
                 android.R.color.system_neutral2_700, android.R.color.system_neutral2_800, android.R.color.system_neutral2_900,
                 android.R.color.system_neutral2_1000}
            };
            String[] names = {"a1", "a2", "n1", "n2"};
            try {
                StringBuilder sb = new StringBuilder("{");
                for (int k = 0; k < ids.length; k++) {
                    if (k > 0) sb.append(',');
                    sb.append('"').append(names[k]).append("\":[");
                    for (int t = 0; t < ids[k].length; t++) {
                        if (t > 0) sb.append(',');
                        sb.append("\"#").append(String.format(Locale.ROOT, "%06x", MainActivity.this.getColor(ids[k][t]) & 0xFFFFFF)).append('"');
                    }
                    sb.append(']');
                }
                return sb.append('}').toString();
            } catch (RuntimeException e) {
                return "";
            }
        }

        /** "an;nächster;anzahl;erlaubt;genau", nur Zeitpunkte und Zahlen, nie Text von Notizen. */
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

        /**
         * Zerstörungscode, letzte Stufe: Android löscht alle Daten der App, wie "Speicher löschen" in den
         * Android-Einstellungen. Die App beendet sich dabei. Vorher wird alles Wichtige einzeln entfernt,
         * damit auch ein Fehlschlag dieses Aufrufs nichts übrig lässt.
         */
        @JavascriptInterface
        public void wipeAppData() {
            runOnUiThread(() -> {
                try {
                    Reminders.setTimes(MainActivity.this, "");
                } catch (RuntimeException e) {
                    // weiter
                }
                try {
                    Biometrics.clear(MainActivity.this);
                } catch (RuntimeException e) {
                    // weiter
                }
                try {
                    bkClear();
                } catch (RuntimeException e) {
                    // weiter
                }
                try {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.cancelAll();
                    }
                } catch (RuntimeException e) {
                    // weiter
                }
                try {
                    ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                    if (am != null) {
                        am.clearApplicationUserData();
                    }
                } catch (RuntimeException e) {
                    // die einzelnen Schritte oben sind schon erledigt
                }
            });
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
            public void onFailure(int kind, String detail) {
                bioBusy = false;
                if (kind == Biometrics.KIND_CANCELED) {
                    tellBio(false, "", "");
                } else if (kind == Biometrics.KIND_LOCKOUT) {
                    tellBio(false, "lockout", detail);
                } else {
                    tellBio(false, "error", detail);
                }
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
            public void onFailure(int kind, String detail) {
                bioBusy = false;
                if (kind == Biometrics.KIND_INVALID) {
                    tellBio(false, "invalid", detail);
                } else if (kind == Biometrics.KIND_LOCKOUT) {
                    tellBio(Biometrics.isEnabled(MainActivity.this), "lockout", detail);
                } else if (kind == Biometrics.KIND_ERROR) {
                    tellBio(Biometrics.isEnabled(MainActivity.this), "error", detail);
                }
                // Abbruch durch dich oder gesperrtes Gerät: keine Meldung, das Passwort bleibt möglich.
            }
        });
    }

    private void tellBio(boolean enabled, String code) {
        tellBio(enabled, code, "");
    }

    private void tellBio(boolean enabled, String code, String detail) {
        if (web != null) {
            // detail wird in ein JavaScript-Textstück gesetzt: nur harmlose Zeichen durchlassen
            String d = detail == null ? "" : detail.replaceAll("[^A-Za-z0-9:._/-]", "");
            if (d.length() > 60) {
                d = d.substring(0, 60);
            }
            web.evaluateJavascript("window.chiffreBioState && window.chiffreBioState("
                    + enabled + ",'" + code + "','" + d + "');", null);
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

    private void copyToClipboard(String text) {
        final ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        if (!clipListening) {
            clipListening = true;
            // Kopiert die Person danach selbst etwas anderes, wird das nicht mehr gelöscht.
            cm.addPrimaryClipChangedListener(() -> {
                if (SystemClock.elapsedRealtime() - ownClipAt > 1500L) {
                    clipToken++;
                }
            });
        }
        ClipData clip = ClipData.newPlainText("Chiffre", text);
        if (Build.VERSION.SDK_INT >= 33) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
        }
        ownClipAt = SystemClock.elapsedRealtime();
        cm.setPrimaryClip(clip);
        final int token = ++clipToken;
        clipHandler.postDelayed(() -> {
            if (token != clipToken) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 28) {
                cm.clearPrimaryClip();
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("", ""));
            }
        }, CLIP_CLEAR_MS);
    }

    private int lockDelaySeconds() {
        return getSharedPreferences(PREFS_LOCK, MODE_PRIVATE).getInt("seconds", DEFAULT_LOCK_SECONDS);
    }

    // ---- Sicherungsordner: nur der gewählte Ordner, nur über die Android-Ordnerauswahl, ohne Internet ----
    private Uri bkTree() {
        String raw = getSharedPreferences(PREFS_BK, MODE_PRIVATE).getString("tree", null);
        return raw == null ? null : Uri.parse(raw);
    }

    private boolean bkHasPermission(Uri tree) {
        try {
            for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().equals(tree) && p.isWritePermission()) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    private Uri bkDirUri(Uri tree) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    }

    /** Anzeigename des Ordners, oder null, wenn er gerade nicht erreichbar ist (zum Beispiel Stick nicht gesteckt). */
    private String bkDirName(Uri tree) {
        try (Cursor c = getContentResolver().query(bkDirUri(tree), new String[] {
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_FLAGS }, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int flags = c.getInt(1);
                if ((flags & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) == 0) {
                    return null;
                }
                String n = c.getString(0);
                return n == null ? "" : n;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private String bkInfo() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            Uri tree = bkTree();
            if (tree == null) {
                o.put("set", false);
                return o.toString();
            }
            o.put("set", true);
            if (!bkHasPermission(tree)) {
                o.put("name", "");
                o.put("state", "perm");
                return o.toString();
            }
            String name = bkDirName(tree);
            o.put("name", name == null ? getSharedPreferences(PREFS_BK, MODE_PRIVATE).getString("name", "") : name);
            o.put("state", name == null ? "away" : "ok");
            return o.toString();
        } catch (org.json.JSONException | RuntimeException e) {
            return "{\"set\":false}";
        }
    }

    private void bkClear() {
        Uri tree = bkTree();
        if (tree != null) {
            try {
                getContentResolver().releasePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (RuntimeException e) {
                // schon weg
            }
        }
        getSharedPreferences(PREFS_BK, MODE_PRIVATE).edit().clear().apply();
    }

    private Uri bkFind(Uri tree, String name) {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor c = getContentResolver().query(kids, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (name.equals(c.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private void bkDelete(Uri doc) {
        if (doc == null) {
            return;
        }
        try {
            DocumentsContract.deleteDocument(getContentResolver(), doc);
        } catch (Exception e) {
            // bleibt stehen; der nächste Lauf räumt auf
        }
    }

    /** Schreibt zuerst eine neue Datei und ersetzt die alte erst danach: bricht das Schreiben ab, bleibt die letzte Sicherung heil. */
    private synchronized String bkWrite(String text) {
        Uri tree = bkTree();
        if (tree == null) {
            return "none";
        }
        if (!bkHasPermission(tree)) {
            return "perm";
        }
        if (bkDirName(tree) == null) {
            return "away";
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Uri tmp = null;
        try {
            bkDelete(bkFind(tree, BK_TMP));
            tmp = DocumentsContract.createDocument(getContentResolver(), bkDirUri(tree), "application/json", BK_TMP);
            if (tmp == null) {
                return "away";
            }
            try (OutputStream out = getContentResolver().openOutputStream(tmp, "wt")) {
                if (out == null) {
                    bkDelete(tmp);
                    return "away";
                }
                out.write(bytes);
                out.flush();
            }
            try (Cursor c = getContentResolver().query(tmp, new String[] { DocumentsContract.Document.COLUMN_SIZE }, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0) && c.getLong(0) != bytes.length) {
                    bkDelete(tmp);
                    return "err";
                }
            }
            bkDelete(bkFind(tree, BK_FILE));
            try {
                DocumentsContract.renameDocument(getContentResolver(), tmp, BK_FILE);
            } catch (Exception e) {
                // Umbenennen geht nicht überall; die neue Datei ist trotzdem eine vollständige Sicherung
            }
            return "ok";
        } catch (SecurityException e) {
            return "perm";
        } catch (IOException | RuntimeException e) {
            bkDelete(tmp);
            return "away";
        }
    }

    private void startPickTree() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            skipLockOnce = true;
            startActivityForResult(intent, REQ_TREE);
        } catch (ActivityNotFoundException e) {
            skipLockOnce = false;
            Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void startSave(String name, String text, boolean export) {
        pendingSave = text;
        pendingExport = export;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(export ? "text/markdown" : "application/json");
        intent.putExtra(Intent.EXTRA_TITLE, name);
        try {
            skipLockOnce = true;
            startActivityForResult(intent, REQ_SAVE);
        } catch (ActivityNotFoundException e) {
            skipLockOnce = false;
            pendingSave = null;
            Toast.makeText(this, export ? R.string.export_failed : R.string.backup_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SAVE) {
            String text = pendingSave;
            boolean export = pendingExport;
            pendingSave = null;
            pendingExport = false;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && text != null) {
                writeText(data.getData(), text, export);
            }
        } else if (requestCode == REQ_TREE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    Uri old = bkTree();
                    if (old != null && !old.equals(tree)) {
                        try {
                            getContentResolver().releasePersistableUriPermission(old,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        } catch (RuntimeException e) {
                            // schon weg
                        }
                    }
                    String name = bkDirName(tree);
                    getSharedPreferences(PREFS_BK, MODE_PRIVATE).edit()
                            .putString("tree", tree.toString()).putString("name", name == null ? "" : name).apply();
                    if (web != null) {
                        web.evaluateJavascript("window.chiffreBackupDir && window.chiffreBackupDir();", null);
                    }
                } catch (SecurityException e) {
                    Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_LONG).show();
                }
            }
        } else if (requestCode == REQ_PICK) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null && clip.getItemCount() > 0) {
                    result = new Uri[clip.getItemCount()];
                    for (int i = 0; i < result.length; i++) {
                        result[i] = clip.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    result = new Uri[] { data.getData() };
                }
            }
            if (pickCallback != null) {
                pickCallback.onReceiveValue(result);
                pickCallback = null;
            }
        } else if (requestCode == REQ_CAPTURE) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && captureUri != null) {
                result = new Uri[] { captureUri };
            }
            captureUri = null;
            if (pickCallback != null) {
                pickCallback.onReceiveValue(result);
                pickCallback = null;
            }
            // Das Foto liegt nur kurz im Cache der App: Chiffre liest es sofort und verkleinert es.
            clipHandler.postDelayed(() -> CaptureProvider.cleanAll(MainActivity.this), 120_000L);
        }
    }

    private void writeText(final Uri uri, final String text, final boolean export) {
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
            runOnUiThread(() -> {
                Toast.makeText(this,
                        export ? (done ? R.string.export_saved : R.string.export_failed)
                                : (done ? R.string.backup_saved : R.string.backup_failed),
                        Toast.LENGTH_SHORT).show();
                if (done && !export && web != null) {
                    web.evaluateJavascript("window.chiffreSaved && window.chiffreSaved();", null);
                }
            });
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
        if (stoppedAt != 0L && web != null && !skipLockOnce
                && SystemClock.elapsedRealtime() - stoppedAt >= lockDelaySeconds() * 1000L) {
            web.evaluateJavascript("window.chiffreLock && window.chiffreLock();", null);
        }
        skipLockOnce = false;
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
