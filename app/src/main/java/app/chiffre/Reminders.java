package app.chiffre;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Erinnerung an abgelaufene Notizen und Vorwarnung vor dem automatischen Löschen.
 *
 * Gespeichert werden nur Zeitpunkte (Millisekunden) mit einer Art, nie Text oder Titel einer Notiz.
 * Art "e": eine Notiz läuft ab, du entscheidest. Art "w": eine Notiz wird bald automatisch gelöscht.
 * Art "r": eine Notiz, an die du erinnert werden wolltest. Die Meldungen sind immer neutral.
 *
 * Der Alarm ist genau (setExactAndAllowWhileIdle). Ein ungenauer Alarm darf von Android um bis zu
 * drei Viertel der Wartezeit verspätet werden, bei einem Termin in drei Stunden also um über zwei
 * Stunden. Das genügt für eine Erinnerung nicht.
 */
final class Reminders {

    private static final String PREFS = "chiffre_reminder";
    private static final String KEY_ON = "on";
    private static final String KEY_TIMES = "times";
    private static final String CHANNEL_ID = "expiry";
    /** Eigener, lauter Kanal für Erinnerungen an Notizen (Banner auf dem Bildschirm). */
    private static final String CHANNEL_REMIND = "remind";
    private static final int ID_EXPIRED = 1;
    private static final int ID_WARNING = 2;
    private static final int ID_TEST = 3;
    private static final int ID_REMIND = 4;
    private static final long TEST_DELAY_MS = 60_000L;
    private static final int MAX_ENTRIES = 500;

    private Reminders() {}

    /** Ein Zeitpunkt mit Art. */
    static final class Entry implements Comparable<Entry> {
        final long time;
        /** 'e' Ablauf, 'w' Vorwarnung, 'r' Erinnerung. */
        final char kind;

        Entry(long time, char kind) {
            this.time = time;
            this.kind = kind;
        }

        @Override
        public int compareTo(Entry o) {
            return Long.compare(time, o.time);
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(KEY_ON, false);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_ON, on).apply();
        schedule(c);
    }

    /** Eintragsliste aus der Web-App, zum Beispiel "e1731000000000,w1731100000000". */
    static void setTimes(Context c, String csv) {
        prefs(c).edit().putString(KEY_TIMES, join(parse(csv))).apply();
        schedule(c);
    }

    private static List<Entry> load(Context c) {
        return parse(prefs(c).getString(KEY_TIMES, ""));
    }

    /** Ungültiges wird übersprungen. Eine Zahl ohne Buchstaben zählt als "e". */
    static List<Entry> parse(String csv) {
        List<Entry> out = new ArrayList<>();
        if (csv == null || csv.isEmpty()) return out;
        for (String part : csv.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            char kind = 'e';
            if (p.charAt(0) == 'w' || p.charAt(0) == 'e' || p.charAt(0) == 'r') {
                kind = p.charAt(0);
                p = p.substring(1);
            }
            try {
                long t = Long.parseLong(p);
                if (t > 0) out.add(new Entry(t, kind));
            } catch (NumberFormatException ignored) {
                // überspringen
            }
            if (out.size() >= MAX_ENTRIES) break;
        }
        Collections.sort(out);
        return out;
    }

    static String join(List<Entry> list) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : list) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.kind).append(e.time);
        }
        return sb.toString();
    }

    private static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, ReminderReceiver.class).setAction(ReminderReceiver.ACTION_ALARM);
        return PendingIntent.getBroadcast(c, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Genau, wenn Android es erlaubt. Sonst ungenau, damit wenigstens irgendwann gemeldet wird. */
    private static void setAlarm(AlarmManager am, long at, PendingIntent pi) {
        if (canExact(am)) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                return;
            } catch (SecurityException ignored) {
                // Erlaubnis fehlt doch: unten ungenau weiter
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    private static boolean canExact(AlarmManager am) {
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
    }

    /**
     * Zustand für die Anzeige im Menü: "an;nächster;anzahl;erlaubt;genau". "nächster" ist Art und
     * Zeitpunkt des nächsten künftigen Termins (zum Beispiel "w1731000000000") oder leer.
     */
    static String status(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        long now = System.currentTimeMillis();
        Entry next = null;
        int future = 0;
        for (Entry e : load(c)) {
            if (e.time > now) {
                if (next == null) next = e;
                future++;
            }
        }
        return (isEnabled(c) ? "1" : "0") + ";"
                + (next == null ? "" : String.valueOf(next.kind) + next.time) + ";"
                + future + ";" + (canNotify(c) ? "1" : "0") + ";"
                + (am != null && canExact(am) ? "1" : "0");
    }

    private static PendingIntent testIntent(Context c) {
        Intent i = new Intent(c, ReminderReceiver.class).setAction(ReminderReceiver.ACTION_TEST);
        return PendingIntent.getBroadcast(c, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Prüft den ganzen Weg: In einer Minute löst derselbe Alarmtyp wie bei den echten Terminen
     * eine neutrale Test-Benachrichtigung aus. Gibt false zurück, wenn Meldungen nicht erlaubt sind.
     */
    static boolean scheduleTest(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null || !canNotify(c)) return false;
        setAlarm(am, System.currentTimeMillis() + TEST_DELAY_MS, testIntent(c));
        return true;
    }

    /** Wird vom Test-Alarm aufgerufen. */
    static void onTest(Context c) {
        if (canNotify(c)) notifyText(c, ID_TEST, false, R.string.notif_test);
    }

    /** Stellt den Alarm auf den nächsten künftigen Zeitpunkt, oder löscht ihn. */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = alarmIntent(c);
        long now = System.currentTimeMillis();
        long next = 0L;
        if (isEnabled(c)) {
            for (Entry e : load(c)) {
                if (e.time > now) {
                    next = e.time;
                    break;
                }
            }
        }
        if (next == 0L) {
            am.cancel(pi);
            return;
        }
        setAlarm(am, next, pi);
    }

    static boolean canNotify(Context c) {
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.areNotificationsEnabled();
    }

    /** Wird vom Alarm aufgerufen: meldet fällige Zeitpunkte und stellt den nächsten Alarm. */
    static void onAlarm(Context c) {
        long now = System.currentTimeMillis();
        int expired = 0;
        int warned = 0;
        int reminded = 0;
        List<Entry> rest = new ArrayList<>();
        for (Entry e : load(c)) {
            if (e.time <= now) {
                if (e.kind == 'w') warned++;
                else if (e.kind == 'r') reminded++;
                else expired++;
            } else {
                rest.add(e);
            }
        }
        prefs(c).edit().putString(KEY_TIMES, join(rest)).apply();
        if ((expired > 0 || warned > 0 || reminded > 0) && isEnabled(c) && canNotify(c)) {
            if (reminded > 0) {
                notifyText(c, ID_REMIND, true,
                        reminded > 1 ? R.string.notif_remind_many : R.string.notif_remind_one);
            }
            if (expired > 0) {
                notifyText(c, ID_EXPIRED, false,
                        expired > 1 ? R.string.notif_many : R.string.notif_one);
            }
            if (warned > 0) {
                notifyText(c, ID_WARNING, false,
                        warned > 1 ? R.string.notif_warn_many : R.string.notif_warn_one);
            }
        }
        schedule(c);
    }

    private static void notifyText(Context c, int id, boolean remind, int textRes) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                c.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT));
        if (remind) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_REMIND,
                    c.getString(R.string.notif_channel_remind), NotificationManager.IMPORTANCE_HIGH));
        }

        Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        PendingIntent tap = open == null ? null : PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(c, remind ? CHANNEL_REMIND : CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_chiffre)
                .setContentTitle(c.getString(R.string.app_name))
                .setContentText(c.getString(textRes))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true);
        // Als Erinnerung gekennzeichnet darf sie auch durch "Nicht stören" mit der Ausnahme "Erinnerungen".
        if (remind) b.setCategory(Notification.CATEGORY_REMINDER);
        if (tap != null) b.setContentIntent(tap);
        nm.notify(id, b.build());
    }
}
