package com.frca.vsexam.helper;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.os.AsyncTask;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.util.SparseArray;
import android.widget.Toast;

import com.frca.vsexam.R;
import com.frca.vsexam.context.MainActivity;
import com.frca.vsexam.entities.exam.Exam;
import com.frca.vsexam.entities.exam.ExamList;
import com.frca.vsexam.network.HttpRequest;
import com.frca.vsexam.network.NetworkInterface;
import com.frca.vsexam.network.Response;
import com.frca.vsexam.network.tasks.BaseNetworkTask;
import com.frca.vsexam.network.tasks.TextNetworkTask;

import org.apache.http.client.methods.HttpRequestBase;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class RegisteringService extends Service {

    public static final String EXTRA_ID = "id";
    public static final String EXTRA_PERFORM_NOW = "perform_now";

    public static final int REQUEST_TIME_MIN_DIFF_MS = 250;
    public static final int REQUEST_TIME_MAX_DIFF_MS = 500;
    public static final int REQUEST_TIME_SPAM_SPREAD = 10000;

    private Exam exam;
    private ExamList examList;
    private DataHolder dataHolder;

    private Thread thread;

    private List<TextNetworkTask> tasks = new ArrayList<TextNetworkTask>();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int examId = intent.getIntExtra(EXTRA_ID, 0);
        Helper.appendLog("Registering process starting");
        examList = MainActivity.getLoadedExams();
        if (examList == null)
            examList = new ExamList();

        if (examId != 0)
            exam = examList.load(this, examId);

        if (exam == null) {
            Helper.appendLog("ERROR: Exam data could not be found nor loaded from file!");
            stopSelf();
        } else if (exam.isRegistered()) {
            Helper.appendLog("ERROR: Exam is already registered!");
            stopSelf();
        } else {
            dataHolder = DataHolder.getInstance(this);
            SparseArray<RegisteringService> container = dataHolder.getRegisteringServiceContainer();
            synchronized (container) {
                RegisteringService runningService = container.get(exam.getId());
                if (runningService != null) {
                    Helper.appendLog("ERROR: Registering service is already running!");
                    stopSelf();
                    return START_STICKY;
                } else {
                    container.put(exam.getId(), this);
                }
            }

            notifyUserOnPrepare();
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        runRegistering();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            });
            thread.start();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Helper.appendLog("Registering service stopped, canceling all concurrent tasks");
        Helper.appendLog("Totally cancelled tasks: " + String.valueOf(tasks.size()));

        for (TextNetworkTask task : tasks) {
            if (task != null && task.getStatus() != AsyncTask.Status.FINISHED)
                task.cancel(true);
        }

        synchronized (dataHolder.getRegisteringServiceContainer()) {
            dataHolder.getRegisteringServiceContainer().remove(exam.getId());
        }
    }

    private void runRegistering() throws InterruptedException {
        Helper.appendLog("Preparing premature request");
        Response response = dataHolder.getNetworkInterface().execute(getRegisterRequest(), Response.Type.TEXT);
        if (examList.onRegistrationResponse(this, exam, response)) {
            notifyUserOnSuccess();
            Helper.appendLog("Register success!");
            stopSelf();
            return;
        }

        long msUntilRegistration = exam.getRegisterStart().getTime() - response.getServerTime().getTime() - response.getDuration();
        Helper.appendLog("Proper registration should occur in: " + String.valueOf(msUntilRegistration / 1000L) + "sec (" + String.valueOf(msUntilRegistration) + "ms).");
        long timerReduction = response.getDuration() * 2 + REQUEST_TIME_SPAM_SPREAD;
        Helper.appendLog("Registering process advance: " + String.valueOf(timerReduction / 1000L) + "sec (" + String.valueOf(timerReduction) + "ms).");
        long timeToSleep = msUntilRegistration - timerReduction;
        if (timeToSleep > 0) {
            Helper.appendLog("Registration process will start in: " + String.valueOf(timeToSleep / 1000L) + "sec (" + String.valueOf(timeToSleep) + "ms).");
            Helper.sleepThread(timeToSleep);
        } else
            Helper.appendLog("Request starting should start ASAP");

        Helper.appendLog("-- REGISTRATION PROCESS STARTING");

        final long endTime = exam.getRegisterStart().getTime() + response.getDuration() * 2;
        long duration = endTime - System.currentTimeMillis();
        Helper.appendLog("Registration process duration: " + String.valueOf(duration / 1000L) + "sec (" + String.valueOf(duration) + "ms).");
        Helper.appendLog("Registration process end: " + Helper.getDateOutput(endTime, Helper.DateOutputType.FULL));

        notifyUserOnStart();
        final long sleepTimer = Math.max(REQUEST_TIME_MIN_DIFF_MS, Math.min(REQUEST_TIME_MAX_DIFF_MS, response.getDuration() / 10));
        int loopCounter = 0;
        do {
            if (loopCounter > 0)
                Helper.sleepThread(sleepTimer);

            final TextNetworkTask task = new TextNetworkTask(this, getRegisterRequest());
            task.setResponseCallback(new BaseNetworkTask.ResponseCallback() {
                @Override
                public void onSuccess(Response response) {
                    tasks.remove(task);
                    if (examList.onRegistrationResponse(RegisteringService.this, exam, response)) {
                        Helper.appendLog("Register success!");
                        notifyUserOnSuccess();
                        stopSelf();
                    } else {
                        Helper.appendLog("Register unsuccessful");
                        if (!thread.isAlive() && tasks.isEmpty()) {
                            notifyUserOnFailure();
                            stopSelf();
                            // TODO add on failure method? maybe remove exam from TO_BE_REGISTERED group
                        }
                    }
                }
            });

            Helper.appendLog("Starting new task");

            tasks.add(task);
            BaseNetworkTask.run(task);
        } while(!exam.isRegistered() && ++loopCounter < 100 && dataHolder.getNetworkInterface().getCurrentServerTime() < endTime);
    }

    private void notifyUserOnPrepare() {
        setNotification(
            getString(R.string.notification_register_start_title),
            getString(R.string.notification_register_start_message, exam.getCourseName())
        );
    }

    private void notifyUserOnStart() {
        setNotification(
            getString(R.string.notification_register_progress_title),
            getString(R.string.notification_register_progress_message, exam.getCourseName())
        );
    }

    private void notifyUserOnSuccess() {
        setNotification(
            getString(R.string.notification_register_success_title),
            getString(R.string.notification_register_success_message, exam.getCourseName(), Helper.getDateOutput(exam.getExamDate(), Helper.DateOutputType.DATE_TIME))
        );
    }

    private void notifyUserOnFailure() {
        setNotification(
            getString(R.string.notification_register_failure_title),
            getString(R.string.notification_register_failure_message, exam.getCourseName())
        );

    }

    private void setNotification(String title, String message) {
        setNotification(title, message, 0);
    }

    private void setNotification(String title, String message, int iconResource) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        builder.setSmallIcon(iconResource != 0 ? iconResource : R.drawable.ic_launcher);
        builder.setContentTitle(title);
        builder.setContentText(message);
        builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));

        builder.setStyle(new NotificationCompat.BigTextStyle().bigText(message));
        Intent resultIntent = new Intent(this, MainActivity.class);

        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addParentStack(MainActivity.class);
        stackBuilder.addNextIntent(resultIntent);

        PendingIntent resultPendingIntent = stackBuilder.getPendingIntent( 0, PendingIntent.FLAG_UPDATE_CURRENT );
        builder.setContentIntent(resultPendingIntent);

        getNotificationManager().notify(exam.getId(), builder.build());

        if (MainActivity.getInstance() != null) {
            Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
        }
    }

    private NotificationManager getNotificationManager() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private HttpRequestBase getRegisterRequest() {
        return HttpRequest.getRegisterRequest(dataHolder, exam, true);
    }

    public static AlarmManager getAlarmManager(Context context) {
        return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    public static void setExamRegister(Context context, Exam exam) {
        NetworkInterface networkInterface = DataHolder.getInstance(context).getNetworkInterface();

        long targetInMillis = exam.getRegisterStart().getTime() - networkInterface.getLastServerLocalTimeDiff();
        targetInMillis -= 45 * 1000;
        long startInMillis = targetInMillis - System.currentTimeMillis();

        Helper.appendLog("Exam name: " + exam.getCourseName());
        Helper.appendLog("Registration start: " + Helper.getDateOutput(exam.getRegisterStart().getTime(), Helper.DateOutputType.FULL));
        Helper.appendLog("Current server time diff: " + String.valueOf(networkInterface.getLastServerLocalTimeDiff()) + " ms");
        Helper.appendLog("Registering process start: " + Helper.getDateOutput(targetInMillis, Helper.DateOutputType.FULL));
        Helper.appendLog("Registering process start delay: " + String.valueOf(startInMillis / 1000L) + " seconds");

        if (startInMillis < 0) {
            Toast.makeText(context, R.string.registration_soon_message, Toast.LENGTH_LONG).show();
            Helper.appendLog("Exam register time is too soon, registering should start right away.");
            sendPendingIntent(getApproxRegisterPI(context, exam, false));
        } else {
            Toast.makeText(context, context.getString(R.string.registration_in_x, Helper.secondsCountdown(context, startInMillis, true)), Toast.LENGTH_LONG).show();
            Helper.appendLog("Exam registering will start in " + Helper.secondsCountdown(context, startInMillis, true));
            getAlarmManager(context).set(AlarmManager.RTC_WAKEUP, targetInMillis, getApproxRegisterPI(context, exam, false));
        }
    }

    public static void cancelExamRegister(Context context, Exam exam) {
        PendingIntent pendingIntent = getApproxRegisterPI(context, exam, exam.getRegisterStart().before(new Date()));
        getAlarmManager(context).cancel(pendingIntent);
    }

    private static void sendPendingIntent(PendingIntent pendingIntent) {
        try {
            pendingIntent.send();
        } catch (PendingIntent.CanceledException e) {
            e.printStackTrace();
        }
    }

    private static PendingIntent getApproxRegisterPI(Context context, Exam exam, boolean performNow) {
        Intent intent = new Intent(context, RegisteringService.class);
        intent.putExtra(RegisteringService.EXTRA_ID, exam.getId());
        intent.putExtra(RegisteringService.EXTRA_PERFORM_NOW, performNow);
        return PendingIntent.getService(context, exam.getId(), intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
