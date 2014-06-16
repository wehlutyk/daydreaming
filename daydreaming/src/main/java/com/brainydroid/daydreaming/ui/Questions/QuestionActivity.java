package com.brainydroid.daydreaming.ui.Questions;

import android.app.Activity;
import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.brainydroid.daydreaming.R;
import com.brainydroid.daydreaming.background.*;
import com.brainydroid.daydreaming.db.Poll;
import com.brainydroid.daydreaming.db.PollsStorage;
import com.brainydroid.daydreaming.db.Question;
import com.brainydroid.daydreaming.network.SntpClient;
import com.brainydroid.daydreaming.network.SntpClientCallback;
import com.brainydroid.daydreaming.ui.FontUtils;
import com.google.inject.Inject;
import roboguice.activity.RoboFragmentActivity;
import roboguice.inject.ContentView;
import roboguice.inject.InjectView;

import java.util.Calendar;

@ContentView(R.layout.activity_question)
public class QuestionActivity extends RoboFragmentActivity {

    private static String TAG = "QuestionActivity";

    public static String EXTRA_POLL_ID = "pollId";
    public static String EXTRA_QUESTION_INDEX = "questionIndex";

    public static long BACK_REPEAT_DELAY = 2 * 1000; // 2 seconds, in milliseconds

    private int pollId;
    private Poll poll;
    private int questionIndex;
    private Question question;
    private int nQuestions;
    private boolean isContinuingOrFinishing = false;
    private long lastBackTime = 0;
    private IQuestionViewAdapter questionViewAdapter;

    @InjectView(R.id.question_linearLayout) LinearLayout questionLinearLayout;
    @InjectView(R.id.question_introlinearLayout) LinearLayout questionIntroLinearLayout;

    @InjectView(R.id.question_nextButton)   ImageButton nextButton;
    @InjectView(R.id.question_finishButton)   ImageButton finishButton;

    @Inject LocationServiceConnection locationServiceConnection;
    @Inject PollsStorage pollsStorage;
    @Inject StatusManager statusManager;
    @Inject QuestionViewAdapterFactory questionViewAdapterFactory;
    @Inject SntpClient sntpClient;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logger.v(TAG, "Creating");

        checkTestMode();
        super.onCreate(savedInstanceState);

        initVars();
        setChrome();
        questionViewAdapter.inflate(isFirstQuestion());
        setRobotoFont(this);

        // Reschedule so as not to have a new poll appear in the middle of
        // this set of questions
        if (isFirstQuestion()) {
            startSchedulerService();
        }
    }

    @Override
    public void onResume() {
        Logger.d(TAG, "Resuming");
        super.onResume();

        poll.setStatus(Poll.STATUS_RUNNING);
        question.setStatus(Question.STATUS_ASKED);
        question.setSystemTimestamp(Calendar.getInstance().getTimeInMillis());

        if (statusManager.isDataAndLocationEnabled()) {
            Logger.i(TAG, "Data and location enabled -> starting listening " +
                    "tasks");
            startListeningTasks();
        } else {
            Logger.i(TAG, "No data or no location -> not starting listening" +
                    " tasks");
        }
    }

    @Override
    public void onPause() {
        Logger.d(TAG, "Pausing");
        super.onPause();
        if (!isContinuingOrFinishing) {
            Logger.d(TAG, "We're not moving to next question or finishing " +
                    "the poll -> dismissing");
            dismissPoll();
        }

        Logger.d(TAG, "Clearing LocationService callback and unbinding");
        locationServiceConnection.clearQuestionLocationCallback();
        // the LocationService finishes if nobody else has listeners registered
        locationServiceConnection.unbindLocationService();
    }

    @Override
    public void onBackPressed() {
        Logger.v(TAG, "Back pressed");
        if (!isRepeatingBack()) {
            Toast.makeText(this, getString(R.string.questionActivity_catch_key),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        finish();
        super.onBackPressed();
    }

    private boolean isRepeatingBack() {
        long now = SystemClock.elapsedRealtime();
        boolean ret = (lastBackTime != 0) && (lastBackTime + BACK_REPEAT_DELAY >= now);
        lastBackTime = now;
        return ret;
    }

    private void initVars() {
        Logger.d(TAG, "Initializing variables");
        Intent intent = getIntent();
        pollId = intent.getIntExtra(EXTRA_POLL_ID, -1);
        poll = pollsStorage.get(pollId);
        questionIndex = intent.getIntExtra(EXTRA_QUESTION_INDEX, -1);
        question = poll.getQuestionByIndex(questionIndex);
        nQuestions = poll.getLength();
        questionViewAdapter = questionViewAdapterFactory.create(question,
                questionLinearLayout);
    }

    private void setChrome() {
        Logger.d(TAG, "Setting chrome");

        setTitle(getString(R.string.app_name) + " " + (questionIndex + 1) + "/" + nQuestions);

        if (!isFirstQuestion()) {
            Logger.d(TAG, "Not the first question -> removing welcome text");
            TextView welcomeText = (TextView) questionIntroLinearLayout.findViewById(R.id.question_welcomeText);
            welcomeText.setVisibility(View.GONE);
            if (isLastQuestion()) {
                Logger.d(TAG, "Last question -> setting finish button text");
                nextButton.setVisibility(View.GONE);
                finishButton.setVisibility(View.VISIBLE);
                finishButton.setClickable(true);
            }
        }
    }


    private void startListeningTasks() {
        LocationCallback locationCallback = new LocationCallback() {

            private final String TAG = "LocationCallback";

            @Override
            public void onLocationReceived(Location location) {
                Logger.i(TAG, "Received location for question, setting it");
                question.setLocation(location);
            }

        };

        SntpClientCallback sntpCallback = new SntpClientCallback() {

            private final String TAG = "SntpClientCallback";

            @Override
            public void onTimeReceived(SntpClient sntpClient) {
                if (sntpClient != null) {
                    question.setNtpTimestamp(sntpClient.getNow());
                    Logger.i(TAG, "Received and saved NTP time for " +
                            "question");
                } else {
                    Logger.e(TAG, "Received successful NTP request but " +
                            "sntpClient is null");
                }
            }

        };

        locationServiceConnection.setQuestionLocationCallback(locationCallback);

        Logger.i(TAG, "Launching NTP request");
        sntpClient.asyncRequestTime(sntpCallback);

        if (!statusManager.isLocationServiceRunning()) {
            Logger.i(TAG, "LocationService not running -> binding and " +
                    "starting");
            locationServiceConnection.bindLocationService();
            locationServiceConnection.startLocationService();
        } else {
            Logger.i(TAG, "LocationService running -> binding (but not " +
                    "starting)");
            locationServiceConnection.bindLocationService();
        }
    }

    public void onClick_nextButton(@SuppressWarnings("UnusedParameters") View view) {
        Logger.d(TAG, "Next button clicked");

        if (questionViewAdapter.validate()) {
            Logger.i(TAG, "Question validation succeeded, " +
                    "setting question status to answered");
            questionViewAdapter.saveAnswer();
            question.setStatus(Question.STATUS_ANSWERED);

            if (isLastQuestion()) {
                Logger.d(TAG, "Last question -> finishing poll");
                finishPoll();
            } else {
                Logger.d(TAG, "Launching next question");
                launchNextQuestion();
            }
        }
    }

    private void launchNextQuestion() {
        Logger.d(TAG, "Launching next question");

        setIsContinuingOrFinishing();

        Intent intent = new Intent(this, QuestionActivity.class);
        intent.putExtra(EXTRA_POLL_ID, pollId);
        intent.putExtra(EXTRA_QUESTION_INDEX, questionIndex + 1);
        intent.setFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        startActivity(intent);

        overridePendingTransition(R.anim.push_left_in, R.anim.push_left_out);
        finish();
    }

    private void dismissPoll() {
        Logger.i(TAG, "Dismissing poll");

        question.setStatus(Question.STATUS_ASKED_DISMISSED);
        poll.setStatus(Poll.STATUS_PARTIALLY_COMPLETED);

        Logger.i(TAG, "Starting sync service to sync answers");
        startSyncService();
    }

    private void finishPoll() {
        Logger.i(TAG, "Finishing poll");

        setIsContinuingOrFinishing();

        Toast.makeText(this, getString(R.string.question_thank_you), Toast.LENGTH_SHORT).show();
        poll.setStatus(Poll.STATUS_COMPLETED);

        Logger.i(TAG, "Starting sync service to sync answers, " +
                "and finishing self");
        startSyncService();
        finish();
    }

    private boolean isLastQuestion() {
        return questionIndex == nQuestions - 1;
    }

    private boolean isFirstQuestion() {
        return questionIndex == 0;
    }

    private void setIsContinuingOrFinishing() {
        isContinuingOrFinishing = true;
    }

    private void startSyncService() {
        Logger.d(TAG, "Starting SyncService");

        Intent syncIntent = new Intent(this, SyncService.class);
        startService(syncIntent);
    }

    /**
     * Start {@link SchedulerService} for the next {@link Poll}.
     */
    private void startSchedulerService() {
        Logger.d(TAG, "Starting SchedulerService");

        Intent schedulerIntent = new Intent(this, SchedulerService.class);
        startService(schedulerIntent);
    }

    public void setRobotoFont(Activity activity) {
        ViewGroup godfatherView = (ViewGroup) activity.getWindow().getDecorView();
        FontUtils.setRobotoFont(activity, godfatherView);
    }

    public void checkTestMode() {
        Logger.d(TAG, "Checking test mode status");
        if (StatusManager.getCurrentModeStatic(this) == StatusManager.MODE_PROD) {
            Logger.d(TAG, "Setting production theme");
            setTheme(R.style.MyCustomTheme);
        } else {
            Logger.d(TAG, "Setting test theme");
            setTheme(R.style.MyCustomTheme_test);
        }
    }

}