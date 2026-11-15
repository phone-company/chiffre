package app.chiffre;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Empfängt den Alarm sowie Neustart und App-Update, bei denen Android Alarme verwirft. */
public class ReminderReceiver extends BroadcastReceiver {

    static final String ACTION_ALARM = "app.chiffre.notes.ALARM";
    static final String ACTION_TEST = "app.chiffre.notes.TEST";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_ALARM.equals(action)) {
            Reminders.onAlarm(context);
        } else if (ACTION_TEST.equals(action)) {
            Reminders.onTest(context);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // Nicht nur den nächsten Alarm stellen: Was während des Ausschaltens oder Updates fällig
            // wurde, wird jetzt noch gemeldet (spät ist besser als nie) und danach der nächste Alarm gestellt.
            Reminders.onAlarm(context);
        }
    }
}
