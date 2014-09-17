package com.brainydroid.daydreaming.background;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.v4.app.NotificationCompat;

import com.brainydroid.daydreaming.R;
import com.brainydroid.daydreaming.db.ConsistencyException;
import com.brainydroid.daydreaming.db.Json;
import com.brainydroid.daydreaming.db.ParametersStorage;
import com.brainydroid.daydreaming.db.SequencesStorage;
import com.brainydroid.daydreaming.network.SntpClient;
import com.brainydroid.daydreaming.network.SntpClientCallback;
import com.brainydroid.daydreaming.sequence.Sequence;
import com.brainydroid.daydreaming.sequence.SequenceBuilder;
import com.brainydroid.daydreaming.ui.sequences.PageActivity;
import com.google.inject.Inject;

import java.util.ArrayList;
import java.util.Calendar;

import roboguice.service.RoboService;

/**
 * Create and populate a {@link Sequence}, then notify it to the user.
 *
 * @author Sébastien Lerique
 * @author Vincent Adam
 * @see Sequence
 * @see SchedulerService
 * @see SyncService
 */
public class ProbeService extends RoboService {

    private static String TAG = "ProbeService";

    public static String CANCEL_PENDING_PROBES = "cancelPendingProbes";
    public static String EXPIRE_PROBE = "expireProbe";
    public static String DISMISS_PROBE = "dismissProbe";
    public static String PROBE_ID = "probeId";

    public static int REQUEST_CODE_CREATION = 1;
    public static int REQUEST_CODE_DISMISSAL = 2;
    public static int REQUEST_CODE_EXPIRY = 3;

    @Inject NotificationManager notificationManager;
    @Inject SequencesStorage sequencesStorage;
    @Inject SequenceBuilder sequenceBuilder;
    @Inject SharedPreferences sharedPreferences;
    @Inject SntpClient sntpClient;
    @Inject StatusManager statusManager;
    @Inject ErrorHandler errorHandler;
    @Inject Json json;
    @Inject ParametersStorage parametersStorage;
    @Inject AlarmManager alarmManager;

    @Override
    public synchronized void onDestroy() {
        Logger.v(TAG, "Destroying");
    }

    @Override
    public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        Logger.d(TAG, "ProbeService started");

        Logger.v(TAG, "StartId: {}", startId);
        super.onStartCommand(intent, flags, startId);

        if (intent.getBooleanExtra(CANCEL_PENDING_PROBES, false)) {
            Logger.v(TAG, "Started to cancel pending probes");
            cancelPendingProbes();
            flushRecentlyMarkedProbes();
        } else if (intent.getBooleanExtra(EXPIRE_PROBE, false)) {
            Logger.v(TAG, "Started to expire pending probes");
            int probeId = intent.getIntExtra(PROBE_ID, -1);
            if (probeId == -1) {
                // We have a problem
                errorHandler.logError("ProbeService started to expire a probe, " +
                        "but not probe id given", new ConsistencyException());
                stopSelf();
                return START_REDELIVER_INTENT;
            }

            if (statusManager.isNotificationExpiryExplained()) {
                expireProbe(probeId);
            }
        } else if (intent.getBooleanExtra(DISMISS_PROBE, false)) {
            Logger.v(TAG, "Started to dismiss probe");
            int probeId = intent.getIntExtra(PROBE_ID, -1);
            if (probeId == -1) {
                // We have a problem
                errorHandler.logError("ProbeService started to dismiss a probe, " +
                        "but not probe id given", new ConsistencyException());
                stopSelf();
                return START_REDELIVER_INTENT;
            }

            dismissProbe(probeId);
        } else {
            Logger.v(TAG, "Started to create and notify a probe");

            if (statusManager.areParametersUpdated() && statusManager.wereBEQAnsweredOnTime()) {
                // If there's a probe running, do nothing.
                if (isProbeRunning()) {
                    Logger.v(TAG, "Probe is running, do nothing");
                    stopSelf();
                    return START_REDELIVER_INTENT;
                }

                // If Dashboard is running, reschedule (so as not to flush recently* during dashboard)
                if (statusManager.isDashboardRunning()) {
                    Logger.v(TAG, "Dashboard is running, rescheduling");
                    reschedule();
                    stopSelf();
                    return START_REDELIVER_INTENT;
                }

                // Flush recently* marked probes
                flushRecentlyMarkedProbes();

                // Populate and notify the probe
                Sequence probe = populateProbe();
                notifyProbe(probe);

                // Schedule expiry
                scheduleExpiry(probe);
            } else {
                reschedule();
            }
        }

        stopSelf();
        return START_REDELIVER_INTENT;
    }

    @Override
    public synchronized IBinder onBind(Intent intent) {
        // Don't allow binding
        return null;
    }

    /**
     * Create the {@link PageActivity} {@link Intent}.
     *
     * @return An {@link Intent} to launch our {@link Sequence}
     */
    private synchronized Intent createProbeIntent(Sequence probe) {
        Logger.d(TAG, "Creating probe Intent");

        Intent intent = new Intent(this, PageActivity.class);

        // Set the id of the probe to start
        intent.putExtra(PageActivity.EXTRA_SEQUENCE_ID, probe.getId());

        // Create a new task. The rest is defined in the App manifest.
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private synchronized void reschedule() {
        Intent schedulerIntent = new Intent(this, SchedulerService.class);
        startService(schedulerIntent);
    }

    private synchronized void dismissProbe(int probeId) {
        Logger.v(TAG, "Dismissing probe");
        Sequence probe = sequencesStorage.get(probeId);
        probe.setStatus(Sequence.STATUS_RECENTLY_DISMISSED);
        // Notification was already removed by the user.
    }

    private synchronized void expireProbe(int probeId) {
        Logger.v(TAG, "Expiring probe");
        Sequence probe = sequencesStorage.get(probeId);
        String status = probe.getStatus();
        if (status.equals(Sequence.STATUS_PENDING)) {
            probe.setStatus(Sequence.STATUS_RECENTLY_MISSED);
            notificationManager.cancel(probeId);
        } else {
            Logger.v(TAG, "Probe {0} was not pending any more, but {1}. Not expiring.", status);
        }
    }

    private synchronized void scheduleExpiry(Sequence probe) {
        // Create and schedule the PendingIntent for ProbeService
        Intent intent = new Intent(this, ProbeService.class);
        intent.putExtra(EXPIRE_PROBE, true);
        intent.putExtra(PROBE_ID, probe.getId());

        long scheduledTime = SystemClock.elapsedRealtime() + Sequence.EXPIRY_DELAY;
        PendingIntent pendingIntent = PendingIntent.getService(this, REQUEST_CODE_EXPIRY,
                intent, 0);
        alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                scheduledTime, pendingIntent);
    }

    private synchronized void flushRecentlyMarkedProbes() {
        ArrayList<Sequence> recentProbes = sequencesStorage.getRecentlyMarkedSequences(
                Sequence.TYPE_PROBE);
        if (recentProbes != null && recentProbes.size() > 0) {
            if (recentProbes.size() > 1) {
                Logger.e(TAG, "Found more than one recently marked probe. Offending probes:");
                Logger.eRaw(TAG, json.toJsonInternal(recentProbes));
                errorHandler.logError("Found more than one recently marked probe",
                        new ConsistencyException());
            }

            // One or many, flush them all
            for (Sequence probe : recentProbes) {
                notificationManager.cancel(probe.getId());
                sequencesStorage.remove(probe.getId());
            }
        }
    }

    private synchronized boolean isProbeRunning() {
        ArrayList<Sequence> runningProbes = sequencesStorage.getRunningSequences(Sequence.TYPE_PROBE);
        if (runningProbes != null && runningProbes.size() > 0) {
            if (runningProbes.size() > 1) {
                // We have a problem
                Logger.e(TAG, "Found more than one running probe. Offending probes:");
                Logger.eRaw(TAG, json.toJsonInternal(runningProbes));
                errorHandler.logError("Found more than one running probe",
                        new ConsistencyException());
            }

            return true;
        }

        return false;
    }

    /**
     * Notify our probe to the user.
     */
    private synchronized void notifyProbe(Sequence probe) {
        Logger.d(TAG, "Notifying probe");

        // Create the PendingIntent
        Intent intent = createProbeIntent(probe);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                intent, PendingIntent.FLAG_CANCEL_CURRENT |
                PendingIntent.FLAG_ONE_SHOT);

        int flags = 0;

        // Should we flash the LED?
        if (sharedPreferences.getBoolean("notification_blink_key", true)) {
            Logger.v(TAG, "Activating lights");
            flags |= Notification.DEFAULT_LIGHTS;
        }

        // Should we vibrate?
        if (sharedPreferences.getBoolean("notification_vibrator_key", true)) {
            Logger.v(TAG, "Activating vibration");
            flags |= Notification.DEFAULT_VIBRATE;
        }

        // Should we beep?
        if (sharedPreferences.getBoolean("notification_sound_key", true)) {
            Logger.v(TAG, "Activating sound");
            flags |= Notification.DEFAULT_SOUND;
        }

        // Create dismissal intent
        Intent dismissalIntent = new Intent(this, ProbeService.class);
        dismissalIntent.putExtra(DISMISS_PROBE, true);
        dismissalIntent.putExtra(PROBE_ID, probe.getId());
        PendingIntent pendingDismissal = PendingIntent.getService(this, REQUEST_CODE_DISMISSAL,
                dismissalIntent, 0);

        // Create our notification
        Notification notification = new NotificationCompat.Builder(this)
        .setTicker(getString(R.string.probeNotification_ticker))
        .setContentTitle(getString(R.string.probeNotification_title))
        .setContentText(getString(R.string.probeNotification_text))
        .setContentIntent(contentIntent)
        .setSmallIcon(R.drawable.ic_stat_notify_small_daydreaming)
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .setDefaults(flags)
        .setDeleteIntent(pendingDismissal)
        .build();

        // How to beep?
        if (sharedPreferences.getBoolean("notification_sound_key", true)) {
            Logger.v(TAG, "Adding custom sound");
            notification.sound = Uri.parse("android.resource://" + "com.brainydroid.daydreaming" + "/" + R.raw.notification);
        }

        // And send it to the system
        notificationManager.cancel(probe.getId());
        notificationManager.notify(probe.getId(), notification);
    }

    /**
     * Fill our {@link Sequence} with questions.
     */
    private synchronized Sequence populateProbe() {
        Logger.d(TAG, "Populating probe with sequence");

        final Sequence probe;

        // Pick from already created probes that were never shown to the
        // user, if there are any
        ArrayList<Sequence> pendingProbes = sequencesStorage.getPendingSequences(
                Sequence.TYPE_PROBE);

        if (pendingProbes != null && pendingProbes.size() > 0) {
            Logger.d(TAG, "Reusing previously pending probe");
            probe = pendingProbes.get(0);
            // Cancelling the notification is done in notifyProbe()

            // Check that these pending probes are not an error.
            Logger.w(TAG, "Found pending probes, this is highly unlikely.");
            if (Sequence.EXPIRY_DELAY <= parametersStorage.getSchedulingMinDelay()) {
                Logger.e(TAG, "Found pending probes when EXPIRY_DELAY <= SCHEDULING_MIN_DELAY");
                Logger.e(TAG, "The only possibility is that the phone rebooted before expiry of a probe, " +
                        "and notification was recreated.");
                if (pendingProbes.size() > 1) {
                    Logger.e(TAG, "There are even several pending probes, which is really wrong");
                }
                Logger.e(TAG, "Offending probes:");
                Logger.eRaw(TAG, json.toJsonInternal(pendingProbes));
                errorHandler.logError("Found pending probe(s) in unlikely situation.",
                        new ConsistencyException());
            }
        } else {
            Logger.d(TAG, "Creating new probe");
            probe = sequenceBuilder.buildSave(Sequence.TYPE_PROBE);
        }

        // Update the probe's status
        Logger.d(TAG, "Setting probe status and timestamp, and saving");
        probe.retainSaves();
        probe.setNotificationSystemTimestamp(
                Calendar.getInstance().getTimeInMillis());
        probe.setStatus(Sequence.STATUS_PENDING);
        probe.flushSaves();

        // Get a timestamp for the probe
        SntpClientCallback sntpCallback = new SntpClientCallback() {

            private final String TAG = "SntpClientCallback";

            @Override
            public void onTimeReceived(SntpClient sntpClient) {
                if (sntpClient != null) {
                    probe.setNotificationNtpTimestamp(sntpClient.getNow());
                    Logger.i(TAG, "Received and saved NTP time for " +
                            "probe notification");
                } else {
                    Logger.e(TAG, "Received successful NTP request but " +
                            "sntpClient is null");
                }
            }

        };

        Logger.i(TAG, "Launching NTP request");
        sntpClient.asyncRequestTime(sntpCallback);

        return probe;
    }

    /**
     * Cancel any pending {@link Sequence}s already notified.
     */
    private synchronized void cancelPendingProbes() {
        Logger.d(TAG, "Cancelling pending probes");
        ArrayList<Sequence> pendingProbes = sequencesStorage.getPendingSequences(
                Sequence.TYPE_PROBE);
        if (pendingProbes != null && pendingProbes.size() > 0) {
            for (Sequence probe : pendingProbes) {
                notificationManager.cancel(probe.getId());
                sequencesStorage.remove(probe.getId());
            }
        } else {
            Logger.v(TAG, "No pending probes to cancel");
        }
    }

}
