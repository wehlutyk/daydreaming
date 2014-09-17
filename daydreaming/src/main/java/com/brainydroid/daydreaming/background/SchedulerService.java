package com.brainydroid.daydreaming.background;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.v4.app.NotificationCompat;

import com.brainydroid.daydreaming.R;
import com.brainydroid.daydreaming.db.ParametersStorage;
import com.brainydroid.daydreaming.db.Util;
import com.brainydroid.daydreaming.ui.dashboard.ResultsActivity;
import com.google.inject.Inject;

import java.util.Calendar;
import java.util.Random;

import roboguice.service.RoboService;

/**
 * Schedule a {@link com.brainydroid.daydreaming.sequence.Sequence} to be created and
 * notified later on. The delay before creation-notification of the {@link
 * com.brainydroid.daydreaming.sequence.Sequence} is both well randomized (a Poisson
 * process) and respectful of the user's notification settings.
 *
 * @author Sébastien Lerique
 * @author Vincent Adam
 * @see SyncService
 * @see ProbeService
 */

// TODO: database cleaning. If running since long time ago, or null status: flush.
// TODO: check schedulerService doesn't die because not rescheduled.
public class SchedulerService extends RoboService {

    private static String TAG = "SchedulerService";

    public static long QUESTIONNAIRE_DELAY_AFTER_WINDOW_START = 30 * 60 * 1000; // 30 minutes

    // Handy object that will be holding the 'now' time
    private Calendar now;
    private long nowUpTime;

    // Useful data about the user's settings
    private int startAllowedHour;
    private int startAllowedMinute;
    private int allowedSpan;
    private int forbiddenSpan;

    @Inject SharedPreferences sharedPreferences;
    @Inject StatusManager statusManager;
    @Inject ParametersStorage parametersStorage;
    @Inject Random random;
    @Inject AlarmManager alarmManager;
    @Inject NotificationManager notificationManager;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.d(TAG, "SchedulerService started");

        super.onStartCommand(intent, flags, startId);

        // Record last time we ran
        statusManager.setLatestSchedulerServiceSystemTimestampToNow();

        // Check LocationPointService hasn't died
        statusManager.checkLatestLocationPointServiceWasAgesAgo();

        // Notify results if they're available
        notifyResultsIfAvailable();

        // Synchronise answers and get parameters if we don't have them. If parameters
        // happen to be updated, the SchedulerService will be run again.
        startSyncService();

        if (!statusManager.areParametersUpdated()) {
            Logger.d(TAG, "Parameters not updated yet. aborting scheduling.");
            return START_REDELIVER_INTENT;
        }

        // Schedule a probe
        scheduleProbe();

        // Schedule begin questionnaire
        if (!statusManager.areBeginQuestionnairesCompleted()) {
            scheduleBeginQuestionnaire();
        } else {
            Logger.d(TAG, "BeginQuestionnaire all answered . no need to schedule notif.");
        }

        stopSelf();

        return START_REDELIVER_INTENT;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Don't allow binding
        return null;
    }

    private synchronized void notifyResultsIfAvailable() {
        if (statusManager.areResultsAvailable() && !statusManager.areResultsNotified()) {
            Intent intent = new Intent(this, ResultsActivity.class);
            PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                    intent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT);
            Notification notification = new NotificationCompat.Builder(this)
                    .setTicker(getString(R.string.results_notification_ticker))
                    .setContentTitle(getString(R.string.results_notification_title))
                    .setContentText(getString(R.string.results_notification_content))
                    .setContentIntent(contentIntent)
                    .setSmallIcon(R.drawable.ic_stat_notify_small_daydreaming)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setDefaults(Notification.DEFAULT_LIGHTS | Notification.DEFAULT_VIBRATE
                            | Notification.DEFAULT_SOUND)
                    .build();

            notificationManager.notify(-1, notification);

            // Remember we did all this
            statusManager.setResultsNotified();
        }
    }

    /**
     * Schedule a {@link com.brainydroid.daydreaming.sequence.Sequence} to be created
     * and notified by {@link ProbeService} later on.
     *
     */
    private synchronized void scheduleProbe() {
        Logger.d(TAG, "Scheduling new probe");

        // Generate the time at which the probe will appear
        long scheduledTime = generateTime();

        // Create and schedule the PendingIntent for ProbeService
        Intent intent = new Intent(this, ProbeService.class);
        PendingIntent pendingIntent = PendingIntent.getService(this,
                ProbeService.REQUEST_CODE_CREATION, intent, PendingIntent.FLAG_CANCEL_CURRENT);
        alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                scheduledTime, pendingIntent);
    }

    /**
     * Schedule a {@link com.brainydroid.daydreaming.ui.dashboard.BeginQuestionnairesActivity} to be created
     * and notified by {@link com.brainydroid.daydreaming.background.BeginQuestionnaireService} later on.
     *
     */
    private synchronized void scheduleBeginQuestionnaire() {
        Logger.d(TAG, "Scheduling new BeginQuestionnaire");

        // Generate the time at which the probe will appear
        long scheduledTime = generateBeginQuestionnaireTime();

        // Create and schedule the PendingIntent for ProbeService
        Intent intent = new Intent(this, BeginQuestionnaireService.class);
        PendingIntent pendingIntent = PendingIntent.getService(this, 0,
                intent, PendingIntent.FLAG_CANCEL_CURRENT);
        alarmManager.set(AlarmManager.ELAPSED_REALTIME,
                scheduledTime, pendingIntent);
    }

    private synchronized long generateBeginQuestionnaireTime() {

        // TODO: this schedules in the past very often. Add a day?

        fixNowAndGetAllowedWindow();
        Logger.d(TAG, "Calculating time for new BeginQuestionnaire notification");

        Calendar start = (Calendar)now.clone();
        start.set(Calendar.HOUR_OF_DAY, startAllowedHour);
        start.set(Calendar.MINUTE, startAllowedMinute);
        Calendar nextStart = (Calendar)start.clone();
        if (now.after(nextStart)) {
            nextStart.add(Calendar.DAY_OF_YEAR, 1);
        }

        long delay;
        // BeginQuestionnaires are notified 30 min after begin of bother window everyday
        delay = now.getTimeInMillis() - nextStart.getTimeInMillis()
                + QUESTIONNAIRE_DELAY_AFTER_WINDOW_START;

        // Log the delay
        long milliseconds = delay;
        long hours = milliseconds / (60 * 60 * 1000);
        milliseconds %= 60 * 60 * 1000;
        long minutes = milliseconds / (60 * 1000);
        milliseconds %= 60 * 1000;
        long seconds = milliseconds / 1000;

        Logger.i(TAG, "BeginQuestionnaireService scheduled in {0} hours, {1} minutes, " +
                        "and {2} seconds",
                hours, minutes, seconds);

        return nowUpTime + delay;
    }

    private synchronized void fixNowAndGetAllowedWindow() {
        Logger.d(TAG, "Fixing now and obtaining allowed time window");

        now = Calendar.getInstance();
        nowUpTime = SystemClock.elapsedRealtime();

        // Get the user's allowed time window
        String startAllowedString = sharedPreferences.getString(
                "time_window_lb_key",
                getString(R.string.settings_time_window_lb_default));
        String endAllowedString = sharedPreferences.getString(
                "time_window_ub_key",
                getString(R.string.settings_time_window_ub_default));

        startAllowedHour = Util.getHour(startAllowedString);
        startAllowedMinute = Util.getMinute(startAllowedString);
        int endAllowedHour = Util.getHour(endAllowedString);
        int endAllowedMinute = Util.getMinute(endAllowedString);

        Logger.d(TAG, "Allowed start: {0}:{1}",
                startAllowedHour, startAllowedMinute);
        Logger.d(TAG, "Allowed end: {0}:{1}",
                endAllowedHour, endAllowedMinute);

        // Convert those to a usable format
        Calendar start = (Calendar)now.clone();
        start.set(Calendar.HOUR_OF_DAY, startAllowedHour);
        start.set(Calendar.MINUTE, startAllowedMinute);

        Calendar end = (Calendar)now.clone();
        end.set(Calendar.HOUR_OF_DAY, endAllowedHour);
        end.set(Calendar.MINUTE, endAllowedMinute);
        if (endAllowedHour * 60 + endAllowedMinute <
                startAllowedHour * 60 + startAllowedMinute) {
            // The time window goes through midnight. Account for this in
            // our end Calendar object.
            end.add(Calendar.DAY_OF_YEAR, 1);
        }

        // Compute the span of our allowed and forbidden time windows
        allowedSpan = (int)end.getTimeInMillis() -
                (int)start.getTimeInMillis();
        forbiddenSpan = 24 * 60 * 60 * 1000 - allowedSpan;
    }

    /**
     * Sample a moment at which the {@link
     * com.brainydroid.daydreaming.sequence.Sequence} should appear.
     * <p/>
     * A delay is sampled from an exponential distribution with parameter
     * {@link 1 / meanDelay}, and is then prolonged to observe the user's
     * preferences in notification time window. This is done by
     * "compactifying" each prohibited time window to one point: imagine a
     * time line where the beginning of a forbidden time window is the same
     * moment as the end of that forbidden time window,
     * and do the scheduling on that time line ; the result of this method
     * is the same (see {@link #makeRespectfulDelay} and {@link
     * #makeRespectfulExpansion} for details).
     *
     * @return Scheduled (and shifted) moment for the probe to appear,
     *         in milliseconds from epoch
     */
    private synchronized long generateTime() {
        Logger.d(TAG, "Generating a time for schedule");

        // Fix what 'now' means, and retrieve the allowed time window
        fixNowAndGetAllowedWindow();

        // Build a delay that respects the user's settings.
        long respectfulDelay;
        // Sample a delay and prolong it as necessary to respect the
        // user's settings.
        Logger.d(TAG, "Using random time-window-respectful delay");
        respectfulDelay = makeRespectfulDelay(sampleDelay());

        // Get the scheduled time into a palatable object.
        Calendar scheduledCalendar = (Calendar)now.clone();
        scheduledCalendar.add(Calendar.MILLISECOND, (int)respectfulDelay);

        // Now log what's scheduled. This is important to make sure we
        // obverse the user's settings.         Intent schedulerIntent =

        // Compute waiting hour, minute, and second values
        long milliseconds = respectfulDelay;
        long hours = milliseconds / (60 * 60 * 1000);
        milliseconds %= 60 * 60 * 1000;
        long minutes = milliseconds / (60 * 1000);
        milliseconds %= 60 * 1000;
        long seconds = milliseconds / 1000;

        String scheduledString = Util.formatDate(scheduledCalendar.getTime());
        Logger.i(TAG, "Probe scheduled in {0} hours, {1} minutes, " +
                "and {2} seconds (i.e. {3})",
                hours, minutes, seconds, scheduledString);
        Logger.td(this, "New probe scheduled at {0}", scheduledString);

        // The scheduled time is returned in milliseconds
        return nowUpTime + respectfulDelay;
    }

    /**
     * Sample a delay following an exponential distribution with parameter
     * {@link 1 / meanDelay}.
     *
     * @return Sampled delay in milliseconds
     */
    private synchronized long sampleDelay() {
        Logger.d(TAG, "Sampling delay");
        // Delays are given in seconds by the parameters
        int minDelayMilli = 1000 * parametersStorage.getSchedulingMinDelay();
        int meanDelayMilli = 1000 * parametersStorage.getSchedulingMeanDelay();
        return (long)(minDelayMilli -
                Math.log(random.nextDouble()) * (meanDelayMilli - minDelayMilli));
    }

    /**
     * Prolong the waiting delay to respect the user's
     * preferences_appSettings in notification time window.
     * <p/>
     * If the suggested scheduled time falls in the user's forbidden time
     * window, we need to adapt to make sure no probe will get notified in
     * that time window. We need to do so in a random-respectful way,
     * i.e. so that we don't have half the probes appearing right at the
     * beginning of the allowed time window (which would be the result of a
     * simple policy here).
     * <p/>
     * To do this, we look at each forbidden time window as if it were a
     * unique instant (we "compactify" them). This logic is implemented in
     * a recursive call to {@link #makeRespectfulExpansion}.
     *
     * @param delay Initial delay to make respectful of user's settings
     * @return Resulting respectful delay
     */
    private synchronized long makeRespectfulDelay(long delay) {
        Logger.d(TAG, "Expanding delay to respect user's time window");

        long expansion = makeRespectfulExpansion(now, delay);

        long milliseconds = expansion;
        long hours = milliseconds / (60 * 60 * 1000);
        milliseconds %= 60 * 60 * 1000;
        long minutes = milliseconds / (60 * 1000);
        milliseconds %= 60 * 1000;
        long seconds = milliseconds / 1000;
        Logger.d(TAG, "Delay expansion: {0}:{1}:{2}",
                hours, minutes, seconds);

        return delay + expansion;
    }

    /**
     * Compute a expansion value that, added to {@code delay},
     * will respect the user's settings if now is {@code hypothesizedNow}.
     * <p/>
     * The expansion is computed by successively removing from {@code delay}
     * all the allowed waiting time that consumes it,
     * at the same time considering forbidden time windows to be
     * compactified, and thus until {@code delay} is short enough and
     * {@code hypothesizedNow} has advanced enough to make {@code
     * hypothesizedNow + delay} fall in an allowed time window without
     * further expansion. You should really read the source to understand the
     * above sentence.
     *
     * @param hypothesizedNow Time we should consider to be 'now'
     * @param delay Suggested waiting delay in milliseconds
     * @return Prolonged waiting delay respecting the user's
     * preferences_appSettings
     */
    private synchronized long makeRespectfulExpansion(Calendar hypothesizedNow,
                                         long delay) {
        Logger.d(TAG, "Recursively building expansion value");

        // Convert our start allowed time, end allowed time,
        // next start allowed time, and suggested time, to usable objects.

        Calendar start = (Calendar)hypothesizedNow.clone();
        start.set(Calendar.HOUR_OF_DAY, startAllowedHour);
        start.set(Calendar.MINUTE, startAllowedMinute);

        Calendar end = (Calendar)start.clone();
        end.add(Calendar.MILLISECOND, allowedSpan);

        // This will always be after hypothesizedNow (because on the next
        // day)
        Calendar nextStart = (Calendar)start.clone();
        nextStart.add(Calendar.DAY_OF_YEAR, 1);

        Calendar suggested = (Calendar)hypothesizedNow.clone();
        suggested.add(Calendar.MILLISECOND, (int)delay);

        Logger.d(TAG, "Hypothesized now is: {0}",
                Util.formatDate(hypothesizedNow.getTime()));
        Logger.d(TAG, "Suggested is: {0}",
                Util.formatDate(suggested.getTime()));
        Logger.d(TAG, "Allowed start is: {0}",
                Util.formatDate(start.getTime()));
        Logger.d(TAG, "Allowed end is: {0}", Util.formatDate(end.getTime()));
        Logger.d(TAG, "Next allowed start is: {0}",
                Util.formatDate(nextStart.getTime()));

        if (hypothesizedNow.before(start)) {
            // hypothesizedNow is before the allowed time window. So we
            // shift to have the delay begin at the start allowed time,
            // independently of where the suggested time fell (this is
            // compactification of the forbidden time window).

            Logger.d(TAG, "hypothesizedNow < start");
            long delayToStart = start.getTimeInMillis() -
                    hypothesizedNow.getTimeInMillis();

            // Since Calendar.before() is strict (i.e. start.before(start)
            // returns false), we know we're not falling back into this
            // same case in the recursive call.
            return delayToStart + makeRespectfulExpansion(start, delay);
        } else if (hypothesizedNow.before(end)) {
            // hypothesizedNow is in the allowed time window. This includes
            // hypothesizedNow == start.

            if (suggested.before(end)) {
                // Suggested schedule falls in today's allowed time window.
                // Accept that.

                // Recursion will eventually fall into this case because
                // delay is reduced of a lower bounded strictly positive
                // value at each recursive call (because the allowedSpan is
                // at least X hours).

                Logger.d(TAG, "start < hypothesizedNow, suggested < end");
                return 0;
            } else {
                // Suggested schedule falls outside the allowed time window.
                // Shorten delay of what's left from hypothesizedNow to the
                // end of the allowed time window, and compute a new shift
                // starting from the nextStart time (again, this is
                // compactification of the forbidden time window).

                Logger.d(TAG, "start < hypothesizedNow < end < suggested");
                long delayToEnd = end.getTimeInMillis() -
                        hypothesizedNow.getTimeInMillis();
                long newDelay = delay - delayToEnd;
                return delayToEnd + forbiddenSpan +
                        makeRespectfulExpansion(nextStart, newDelay);
            }
        } else {
            // hypothesizedNow is after the end of the allowed time window.
            // So we shift to have the delay begin at the next allowed start
            // time, independently of where the suggested time fell (this
            // is compactification of the forbidden time window).

            Logger.d(TAG, "end < hypothesizedNow");
            long delayToNextStart = nextStart.getTimeInMillis() -
                    hypothesizedNow.getTimeInMillis();

            // Again, since Calendar.before() is strict (i.e.
            // nextStart.before(nextStart) returns false),
            // we know we're not falling back into the top case in the
            // recursive call.
            return delayToNextStart + makeRespectfulExpansion(nextStart, delay);
        }
    }

    /**
     * Start {@link SyncService} to synchronize answers.
     */
    private synchronized void startSyncService() {
        Logger.d(TAG, "Starting SyncService");
        Intent syncIntent = new Intent(this, SyncService.class);
        startService(syncIntent);
    }

}
