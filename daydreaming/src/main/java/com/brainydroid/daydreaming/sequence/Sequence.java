package com.brainydroid.daydreaming.sequence;

import com.brainydroid.daydreaming.background.Logger;
import com.brainydroid.daydreaming.db.SequenceDescription;
import com.brainydroid.daydreaming.db.SequenceJsonFactory;
import com.brainydroid.daydreaming.db.SequencesStorage;
import com.brainydroid.daydreaming.db.TypedStatusModel;
import com.brainydroid.daydreaming.db.Views;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.inject.Inject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

public class Sequence extends TypedStatusModel<Sequence,SequencesStorage,SequenceJsonFactory>
        implements ISequence {

    private static String TAG = "Sequence";

    public static String TYPE_PROBE = "probe";
    public static String TYPE_BEGIN_QUESTIONNAIRE = "beginQuestionnaire";

    public static String[] AVAILABLE_TYPES = new String[] {TYPE_PROBE, TYPE_BEGIN_QUESTIONNAIRE};

    /* Probes/Questionnaires: notification has appeared */
    public static final String STATUS_PENDING = "pending";
    /* Probes: notification was dismissed, and probe not yet re-suggested nor dropped */
    public static final String STATUS_RECENTLY_DISMISSED = "recentlyDismissed";
    /* Probes: notification died away, and probe not yet re-suggested nor dropped */
    public static final String STATUS_RECENTLY_MISSED = "recentlyMissed";
    /* Probes/Questionnaires: activity is running */
    public static final String STATUS_RUNNING = "running";
    /**
     * Probes/Questionnaires: Activity was stopped in the middle of the sequence,
     * and probe not yet re-suggested nor dropped
     */
    public static final String STATUS_RECENTLY_PARTIALLY_COMPLETED = "recentlyPartiallyCompleted";
    /**
     * Probes: was re-suggested after miss/dismiss/partialCompletion, and was refused.
     * Or, dropped after too long missed/dismissed/partialCompletion.
     */
    public static final String STATUS_MISSED_OR_DISMISSED_OR_INCOMPLETE = "missedOrDismissedOrIncomplete";
    /* Probes/Questionnaires: sequence completely answered */
    public static final String STATUS_COMPLETED = "completed";
    /* Questionnaires: sequence completely answered, uploaded, and must be kept locally */
    public static final String STATUS_UPLOADED_AND_KEEP = "uploadedAndKeep";

    public static String[] AVAILABLE_STATUSES = new String[] {
            STATUS_PENDING,
            STATUS_RECENTLY_DISMISSED,
            STATUS_RECENTLY_MISSED,
            STATUS_RUNNING,
            STATUS_RECENTLY_PARTIALLY_COMPLETED,
            STATUS_MISSED_OR_DISMISSED_OR_INCOMPLETE,
            STATUS_COMPLETED,
            STATUS_UPLOADED_AND_KEEP};

    public static HashSet<String> PAUSED_STATUSES =
            new HashSet<String>(Arrays.asList(new String[] {
            STATUS_RECENTLY_DISMISSED,
            STATUS_RECENTLY_MISSED,
            STATUS_RECENTLY_PARTIALLY_COMPLETED}));

    @JsonView(Views.Public.class)
    private String name = null;
    @JsonView(Views.Public.class)
    private long notificationNtpTimestamp = -1;
    @JsonView(Views.Public.class)
    private long notificationSystemTimestamp = -1;
    @JsonView(Views.Public.class)
    private ArrayList<PageGroup> pageGroups = null;

    @JsonView(Views.Internal.class)
    private String intro = null;
    @JsonView(Views.Internal.class)
    private boolean skipBonuses = true;
    @JsonView(Views.Internal.class)
    private boolean skipBonusesAsked = false;
    @JsonView(Views.Public.class)
    private boolean selfInitiated = false;
    @JsonView(Views.Public.class)
    private boolean wasMissedOrDismissedOrPaused = false;

    @Inject private SequencesStorage sequencesStorage;

    public synchronized String getIntro() {
        return intro;
    }

    public synchronized String getName() {
        return name;
    }

    private synchronized void setName(String name) {
        this.name = name;
        saveIfSync();
    }

    private synchronized void setIntro(String intro) {
        this.intro = intro;
        saveIfSync();
    }

    public synchronized void importFromSequenceDescription(SequenceDescription description) {
        setName(description.getName());
        setType(description.getType());
        setIntro(description.getIntro());
    }

    public synchronized void setPageGroups(ArrayList<PageGroup> pageGroups) {
        Logger.v(TAG, "Setting pageGroups");
        this.pageGroups = pageGroups;
        saveIfSync();
    }

    public synchronized ArrayList<PageGroup> getPageGroups() {
        return pageGroups;
    }

    public synchronized void setNotificationNtpTimestamp(
            long notificationNtpTimestamp) {
        Logger.v(TAG, "Setting notification ntpTimestamp");
        this.notificationNtpTimestamp = notificationNtpTimestamp;
        saveIfSync();
    }

    public synchronized void setNotificationSystemTimestamp(
            long notificationSystemTimestamp) {
        Logger.v(TAG, "Setting notification systemTimestamp");
        this.notificationSystemTimestamp = notificationSystemTimestamp;
        saveIfSync();
    }

    public synchronized long getNotificationSystemTimestamp() {
        return notificationSystemTimestamp;
    }

    public synchronized boolean isSelfInitiated() {
        return selfInitiated;
    }

    public synchronized void setSelfInitiated(boolean selfInitiated) {
        this.selfInitiated = selfInitiated;
    }

    public synchronized void setSkipBonuses(boolean skip) {
        Logger.v(TAG, "Setting skipBonuses to {}", skip);
        skipBonuses = skip;
        skipBonusesAsked = true;
        saveIfSync();
    }

    public synchronized boolean isSkipBonuses() {
        return skipBonuses;
    }

    public synchronized boolean isSkipBonusesAsked() {
        return skipBonusesAsked;
    }

    @Override
    public synchronized void setStatus(String status) {
        if (PAUSED_STATUSES.contains(status)) {
            Logger.v(TAG, "Remembering that sequence went through a paused state (recently*)");
            wasMissedOrDismissedOrPaused = true;
        }
        // Save occurs in super.setStatus()
        super.setStatus(status);
    }

    public boolean wasMissedOrDismissedOrPaused() {
        return wasMissedOrDismissedOrPaused;
    }

    public synchronized Page getCurrentPage() {
        Logger.d(TAG, "Getting current page");

        // Get last not answered page
        Page currentPage = null;
        String status;
        for (PageGroup pg : pageGroups) {

            for (Page p : pg.getPages()) {

                status = p.getStatus();
                if (status != null && (status.equals(Page.STATUS_ANSWERED) ||
                        status.equals(Page.STATUS_BONUS_SKIPPED))) {

                    // We're at a page with status answered or skipped

                    if (currentPage != null) {

                        // We already found a current page before this page! Something is wrong

                        String msg = "Found a page with status STATUS_ANSWERED or" +
                                " STATUS_BONUS_SKIPPED after the current page " +
                                "(i.e. an answered page after the current one)";
                        Logger.e(TAG, msg);
                        throw new RuntimeException(msg);
                    }

                } else {

                    if (currentPage == null) {

                        // This could be our current page

                        if (p.isBonus() && skipBonusesAsked && skipBonuses) {
                            // No it's not, this page is bonus and we're asked to skip it
                            p.setStatus(Page.STATUS_BONUS_SKIPPED);
                        } else {
                            // It's the first non-answered and non-skipped page,
                            // ergo the current page
                            currentPage = p;
                        }
                    }
                }
            }
        }

        if (currentPage == null) {
            String msg = "Asked for a current page, but none found (all pages answered or skipped)";
            Logger.e(TAG, msg);
            throw new RuntimeException(msg);
        }

        return currentPage;
    }

    public synchronized void skipRemainingBonuses() {
        Logger.v(TAG, "Skipping all remaining bonus pages");

        for (PageGroup pg : pageGroups) {

            for (Page p : pg.getPages()) {

                String status = p.getStatus();
                if (status == null || !(status.equals(Page.STATUS_ANSWERED) ||
                        status.equals(Page.STATUS_BONUS_SKIPPED))) {

                    // This page has either null status, or something else than answered or skipped

                    if (p.isBonus()) {
                        // This is one of the remaining bonus pages
                        p.setStatus(Page.STATUS_BONUS_SKIPPED);
                    } else {
                        // We have a problem: there should be only bonus pages here
                        // (otherwise we wouldn't be skipping them all in one go)
                        String msg = "Found a non-bonus non-answered (and non-skipped) page " +
                                "while skipping remaining bonus pages. Something is wrong.";
                        Logger.e(TAG, msg);
                        throw new RuntimeException(msg);
                    }
                }
            }
        }
    }

    @Override
    protected synchronized Sequence self() {
        return this;
    }

    @Override
    protected synchronized SequencesStorage getStorage() {
        return sequencesStorage;
    }

}
