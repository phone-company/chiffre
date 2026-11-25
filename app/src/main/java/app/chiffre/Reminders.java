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
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * Erinnerung an abgelaufene Notizen und Vorwarnung vor dem automatischen Löschen.
 *
 * Gespeichert werden nur Zeitpunkte (Millisekunden) mit einer Art, nie Text oder Titel einer Notiz.
 * Art "e": eine Notiz läuft ab, du entscheidest. Art "w": eine Notiz wird bald automatisch gelöscht.
 * Art "r": eine Notiz, an die du erinnert werden wolltest. Die Meldungen sind immer neutral.
 *
 * Schlummern und Erledigen direkt in der Meldung: Android kennt die Notizen nicht (der Tresor bleibt
 * verschlüsselt). Gemerkt wird nur "Termin t wurde auf s verschoben" (s = 0: erledigt). Die App holt das
 * beim nächsten Öffnen ab und trägt es in die betroffenen Notizen ein.
 *
 * Der Alarm ist genau (setExactAndAllowWhileIdle). Ein ungenauer Alarm darf von Android um bis zu
 * drei Viertel der Wartezeit verspätet werden, bei einem Termin in drei Stunden also um über zwei
 * Stunden. Das genügt für eine Erinnerung nicht.
 */
final class Reminders {

    private static final String PREFS = "chiffre_reminder";
    private static final String KEY_ON = "on";
    private static final String KEY_TIMES = "times";
    /** Zeitpunkte der Erinnerungen, die gerade in der Meldung stehen. */
    private static final String KEY_FIRED = "fired";
    /** Für die App vorgemerkt: "t:s,t:s" (s = neuer Zeitpunkt, 0 = erledigt). */
    private static final String KEY_SNOOZED = "snoozed";
    static final String MODE_15 = "15";
    static final String MODE_MORNING = "morning";
    static final String MODE_DONE = "done";
    private static final long MIN_MS = 60_000L;
    private static final String CHANNEL_ID = "expiry";
    /** Früherer Kanal für Erinnerungen (kurzes Summen). Wird entfernt, seine Einstellungen gelten nicht mehr. */
    private static final String CHANNEL_REMIND_OLD = "remind";
    /** Erinnerungen an Notizen: Banner auf dem Bildschirm, drei lange Vibrationsstöße. */
    static final String CHANNEL_REMIND = "remind2";
    /** Erinnerungen wie ein Wecker: Ton über den Wecker-Kanal (klingelt auch im Vibrationsmodus), wiederholt sich bis zur Reaktion. */
    static final String CHANNEL_ALARM = "remind_alarm";
    private static final String KEY_ALARM = "alarm";
    private static final long[] VIBRATE = {0L, 700L, 300L, 700L, 300L, 700L};
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

    static boolean alarmMode(Context c) {
        return prefs(c).getBoolean(KEY_ALARM, false);
    }

    static void setAlarmMode(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_ALARM, on).apply();
        ensureChannels(c);
    }

    /** Kanal, dessen Ton und Vibration gerade gilt (für die Android-Einstellungsseite). */
    static String remindChannel(Context c) {
        return alarmMode(c) ? CHANNEL_ALARM : CHANNEL_REMIND;
    }

    /** Legt die Kanäle an (Android übernimmt Ton und Vibration nur beim ersten Anlegen) und räumt den alten Kanal weg. */
    static void ensureChannels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                c.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT));
        NotificationChannel r = new NotificationChannel(CHANNEL_REMIND,
                c.getString(R.string.notif_channel_remind), NotificationManager.IMPORTANCE_HIGH);
        r.enableVibration(true);
        r.setVibrationPattern(VIBRATE);
        nm.createNotificationChannel(r);
        if (alarmMode(c)) {
            NotificationChannel a = new NotificationChannel(CHANNEL_ALARM,
                    c.getString(R.string.notif_channel_alarm), NotificationManager.IMPORTANCE_HIGH);
            a.enableVibration(true);
            a.setVibrationPattern(VIBRATE);
            Uri tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (tone == null) tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            a.setSound(tone, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            nm.createNotificationChannel(a);
        }
        try {
            nm.deleteNotificationChannel(CHANNEL_REMIND_OLD);
        } catch (RuntimeException ignored) {
            // gibt es nicht: nichts zu tun
        }
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
        List<Entry> list = parse(csv);
        // Noch nicht abgeholtes Schlummern: der neue Termin bleibt gestellt, auch wenn die App noch den alten kennt
        long now = System.currentTimeMillis();
        for (long[] p : parsePairs(prefs(c).getString(KEY_SNOOZED, ""))) {
            if (p[1] > now) list.add(new Entry(p[1], 'r'));
        }
        Collections.sort(list);
        prefs(c).edit().putString(KEY_TIMES, join(list)).apply();
        schedule(c);
    }

    /** "t:s,t:s" in Paare; Ungültiges wird übersprungen. */
    static List<long[]> parsePairs(String csv) {
        List<long[]> out = new ArrayList<>();
        if (csv == null || csv.isEmpty()) return out;
        for (String part : csv.split(",")) {
            int i = part.indexOf(':');
            if (i <= 0) continue;
            try {
                long t = Long.parseLong(part.substring(0, i).trim());
                long v = Long.parseLong(part.substring(i + 1).trim());
                if (t > 0 && v >= 0) out.add(new long[] {t, v});
            } catch (NumberFormatException ignored) {
                // überspringen
            }
            if (out.size() >= MAX_ENTRIES) break;
        }
        return out;
    }

    private static List<Long> parseLongs(String csv) {
        List<Long> out = new ArrayList<>();
        if (csv == null || csv.isEmpty()) return out;
        for (String part : csv.split(",")) {
            try {
                long t = Long.parseLong(part.trim());
                if (t > 0 && !out.contains(t)) out.add(t);
            } catch (NumberFormatException ignored) {
                // überspringen
            }
            if (out.size() >= MAX_ENTRIES) break;
        }
        return out;
    }

    private static String joinLongs(List<Long> list) {
        StringBuilder sb = new StringBuilder();
        for (Long t : list) {
            if (sb.length() > 0) sb.append(',');
            sb.append(t);
        }
        return sb.toString();
    }

    /** Neuer Zeitpunkt für einen Knopf der Meldung: in 15 Minuten oder morgen um 8 Uhr; 0 = erledigt. */
    static long targetFor(String mode, long now) {
        if (MODE_15.equals(mode)) return now + 15 * MIN_MS;
        if (MODE_MORNING.equals(mode)) {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(now);
            cal.add(Calendar.DAY_OF_MONTH, 1);
            cal.set(Calendar.HOUR_OF_DAY, 8);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        }
        return 0L;
    }

    /** Knopf in der Meldung: alle Erinnerungen, die gerade darin stehen, verschieben oder erledigen. */
    static void onAction(Context c, String mode) {
        SharedPreferences p = prefs(c);
        List<Long> fired = parseLongs(p.getString(KEY_FIRED, ""));
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID_REMIND);
        if (fired.isEmpty()) return;
        long target = targetFor(mode, System.currentTimeMillis());
        StringBuilder sn = new StringBuilder(p.getString(KEY_SNOOZED, ""));
        List<Entry> times = load(c);
        for (Long t : fired) {
            if (sn.length() > 0) sn.append(',');
            sn.append(t).append(':').append(target);
            if (target > 0) times.add(new Entry(target, 'r'));
        }
        Collections.sort(times);
        p.edit().putString(KEY_SNOOZED, sn.toString()).putString(KEY_FIRED, "")
                .putString(KEY_TIMES, join(times)).apply();
        schedule(c);
    }

    /** Meldung weggewischt: nichts verschieben, nur vergessen, was darin stand. */
    static void onDismiss(Context c) {
        prefs(c).edit().putString(KEY_FIRED, "").apply();
    }

    /** Die App hat keine fällige Erinnerung mehr (in der App erledigt): die stehende Meldung verschwindet. */
    static void clearNotice(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getString(KEY_FIRED, "").isEmpty()) return;
        p.edit().putString(KEY_FIRED, "").apply();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID_REMIND);
    }

    /** Für die App: vorgemerktes Schlummern und Erledigen abholen (danach leer). */
    static String takeSnoozed(Context c) {
        SharedPreferences p = prefs(c);
        String s = p.getString(KEY_SNOOZED, "");
        if (!s.isEmpty()) p.edit().putString(KEY_SNOOZED, "").apply();
        return s;
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
        List<Long> fired = parseLongs(prefs(c).getString(KEY_FIRED, ""));
        for (Entry e : load(c)) {
            if (e.time <= now) {
                if (e.kind == 'w') warned++;
                else if (e.kind == 'r') {
                    reminded++;
                    if (!fired.contains(e.time)) fired.add(e.time);
                }
                else expired++;
            } else {
                rest.add(e);
            }
        }
        while (fired.size() > MAX_ENTRIES) fired.remove(0);
        prefs(c).edit().putString(KEY_TIMES, join(rest)).putString(KEY_FIRED, joinLongs(fired)).apply();
        if ((expired > 0 || warned > 0 || reminded > 0) && isEnabled(c) && canNotify(c)) {
            if (reminded > 0) {
                notifyText(c, ID_REMIND, true,
                        fired.size() > 1 ? R.string.notif_remind_many : R.string.notif_remind_one);
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
        ensureChannels(c);
        boolean alarm = remind && alarmMode(c);

        Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        PendingIntent tap = open == null ? null : PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(c, remind ? (alarm ? CHANNEL_ALARM : CHANNEL_REMIND) : CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_chiffre)
                .setContentTitle(c.getString(R.string.app_name))
                .setContentText(c.getString(textRes))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true);
        // Als Erinnerung gekennzeichnet darf sie auch durch "Nicht stören" mit der Ausnahme "Erinnerungen".
        if (remind) {
            b.setCategory(Notification.CATEGORY_REMINDER);
            // Bleibt stehen, bis du reagierst: später erinnern, erledigt, oder die App öffnen
            b.setOngoing(true);
            b.addAction(action(c, MODE_15, R.string.notif_act_15, 11));
            b.addAction(action(c, MODE_MORNING, R.string.notif_act_morning, 12));
            b.addAction(action(c, MODE_DONE, R.string.notif_act_done, 13));
            Intent gone = new Intent(c, ReminderReceiver.class).setAction(ReminderReceiver.ACTION_DISMISS);
            b.setDeleteIntent(PendingIntent.getBroadcast(c, 14, gone,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        if (tap != null) b.setContentIntent(tap);
        Notification n = b.build();
        // Wecker: Ton und Vibration wiederholen sich, bis du die Meldung antippst, einen Knopf wählst oder sie wegwischst
        if (alarm) n.flags |= Notification.FLAG_INSISTENT;
        nm.notify(id, n);
    }

    private static Notification.Action action(Context c, String mode, int label, int req) {
        Intent i = new Intent(c, ReminderReceiver.class).setAction(ReminderReceiver.ACTION_SNOOZE)
                .putExtra(ReminderReceiver.EXTRA_MODE, mode);
        PendingIntent pi = PendingIntent.getBroadcast(c, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(null, c.getString(label), pi).build();
    }
}
