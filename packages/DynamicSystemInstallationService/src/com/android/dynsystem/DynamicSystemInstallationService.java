/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dynsystem;

import static android.os.AsyncTask.Status.FINISHED;
import static android.os.AsyncTask.Status.PENDING;
import static android.os.AsyncTask.Status.RUNNING;
import static android.os.image.DynamicSystemClient.ACTION_NOTIFY_IF_IN_USE;
import static android.os.image.DynamicSystemClient.ACTION_START_INSTALL;
import static android.os.image.DynamicSystemClient.CAUSE_ERROR_EXCEPTION;
import static android.os.image.DynamicSystemClient.CAUSE_ERROR_INVALID_URL;
import static android.os.image.DynamicSystemClient.CAUSE_ERROR_IO;
import static android.os.image.DynamicSystemClient.CAUSE_INSTALL_CANCELLED;
import static android.os.image.DynamicSystemClient.CAUSE_INSTALL_COMPLETED;
import static android.os.image.DynamicSystemClient.CAUSE_NOT_SPECIFIED;
import static android.os.image.DynamicSystemClient.STATUS_IN_PROGRESS;
import static android.os.image.DynamicSystemClient.STATUS_IN_USE;
import static android.os.image.DynamicSystemClient.STATUS_NOT_STARTED;
import static android.os.image.DynamicSystemClient.STATUS_READY;

import static com.android.dynsystem.InstallationAsyncTask.RESULT_CANCELLED;
import static com.android.dynsystem.InstallationAsyncTask.RESULT_ERROR_EXCEPTION;
import static com.android.dynsystem.InstallationAsyncTask.RESULT_ERROR_IO;
import static com.android.dynsystem.InstallationAsyncTask.RESULT_ERROR_UNSUPPORTED_FORMAT;
import static com.android.dynsystem.InstallationAsyncTask.RESULT_ERROR_UNSUPPORTED_URL;
import static com.android.dynsystem.InstallationAsyncTask.RESULT_OK;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.http.HttpResponseCache;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelableException;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.image.DynamicSystemClient;
import android.os.image.DynamicSystemManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * This class is the service in charge of DynamicSystem installation.
 * It also posts status to notification bar and wait for user's
 * cancel and confirm commnands.
 */
public class DynamicSystemInstallationService extends Service
        implements InstallationAsyncTask.ProgressListener {

    private static final String TAG = "DynSystemInstallationService";

    // TODO (b/131866826): This is currently for test only. Will move this to System API.
    static final String KEY_ENABLE_WHEN_COMPLETED = "KEY_ENABLE_WHEN_COMPLETED";
    static final String KEY_DSU_SLOT = "KEY_DSU_SLOT";
    static final String DEFAULT_DSU_SLOT = "dsu";
    static final String KEY_PUBKEY = "KEY_PUBKEY";

    // Default userdata partition size is 2GiB.
    private static final long DEFAULT_USERDATA_SIZE = 2L << 30;

    /*
     * Intent actions
     */
    private static final String ACTION_CANCEL_INSTALL =
            "com.android.dynsystem.ACTION_CANCEL_INSTALL";
    private static final String ACTION_DISCARD_INSTALL =
            "com.android.dynsystem.ACTION_DISCARD_INSTALL";
    private static final String ACTION_REBOOT_TO_DYN_SYSTEM =
            "com.android.dynsystem.ACTION_REBOOT_TO_DYN_SYSTEM";
    private static final String ACTION_REBOOT_TO_NORMAL =
            "com.android.dynsystem.ACTION_REBOOT_TO_NORMAL";

    /*
     * For notification
     */
    private static final String NOTIFICATION_CHANNEL_ID = "com.android.dynsystem";
    private static final int NOTIFICATION_ID = 1;

    /*
     * IPC
     */
    /** Keeps track of all current registered clients. */
    ArrayList<Messenger> mClients = new ArrayList<>();

    /** Handler of incoming messages from clients. */
    final Messenger mMessenger = new Messenger(new IncomingHandler(this));

    static class IncomingHandler extends Handler {
        private final WeakReference<DynamicSystemInstallationService> mWeakService;

        IncomingHandler(DynamicSystemInstallationService service) {
            mWeakService = new WeakReference<>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            DynamicSystemInstallationService service = mWeakService.get();

            if (service != null) {
                service.handleMessage(msg);
            }
        }
    }

    private DynamicSystemManager mDynSystem;
    private NotificationManager mNM;

    private int mNumInstalledPartitions;

    private String mCurrentPartitionName;
    private long mCurrentPartitionSize;
    private long mCurrentPartitionInstalledSize;

    private boolean mJustCancelledByUser;
    private boolean mKeepNotification;

    // This is for testing only now
    private boolean mEnableWhenCompleted;

    private InstallationAsyncTask mInstallTask;


    @Override
    public void onCreate() {
        super.onCreate();

        prepareNotification();

        mDynSystem = (DynamicSystemManager) getSystemService(Context.DYNAMIC_SYSTEM_SERVICE);

        // Install an HttpResponseCache in the application cache directory so we can cache
        // gsi key revocation list. The http(s) protocol handler uses this cache transparently.
        // The cache size is chosen heuristically. Since we don't have too much traffic right now,
        // a moderate size of 1MiB should be enough.
        try {
            File httpCacheDir = new File(getCacheDir(), "httpCache");
            long httpCacheSize = 1 * 1024 * 1024; // 1 MiB
            HttpResponseCache.install(httpCacheDir, httpCacheSize);
        } catch (IOException e) {
            Log.d(TAG, "HttpResponseCache.install() failed: " + e);
        }
    }

    @Override
    public void onDestroy() {
        HttpResponseCache cache = HttpResponseCache.getInstalled();
        if (cache != null) {
            cache.flush();
        }

        if (!mKeepNotification) {
            // Cancel the persistent notification.
            mNM.cancel(NOTIFICATION_ID);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();

        Log.d(TAG, "onStartCommand(): action=" + action);

        if (ACTION_START_INSTALL.equals(action)) {
            executeInstallCommand(intent);
        } else if (ACTION_CANCEL_INSTALL.equals(action)) {
            executeCancelCommand();
        } else if (ACTION_DISCARD_INSTALL.equals(action)) {
            executeDiscardCommand();
        } else if (ACTION_REBOOT_TO_DYN_SYSTEM.equals(action)) {
            executeRebootToDynSystemCommand();
        } else if (ACTION_REBOOT_TO_NORMAL.equals(action)) {
            executeRebootToNormalCommand();
        } else if (ACTION_NOTIFY_IF_IN_USE.equals(action)) {
            executeNotifyIfInUseCommand();
        }

        return Service.START_NOT_STICKY;
    }

    @Override
    public void onProgressUpdate(InstallationAsyncTask.Progress progress) {
        mCurrentPartitionName = progress.mPartitionName;
        mCurrentPartitionSize = progress.mPartitionSize;
        mCurrentPartitionInstalledSize = progress.mInstalledSize;
        mNumInstalledPartitions = progress.mNumInstalledPartitions;

        postStatus(STATUS_IN_PROGRESS, CAUSE_NOT_SPECIFIED, null);
    }

    @Override
    public void onResult(int result, Throwable detail) {
        if (result == RESULT_OK) {
            postStatus(STATUS_READY, CAUSE_INSTALL_COMPLETED, null);

            // For testing: enable DSU and restart the device when install completed
            if (mEnableWhenCompleted) {
                executeRebootToDynSystemCommand();
            }
            return;
        }

        switch (result) {
            case RESULT_CANCELLED:
                postStatus(STATUS_NOT_STARTED, CAUSE_INSTALL_CANCELLED, null);
                break;

            case RESULT_ERROR_IO:
                postStatus(STATUS_NOT_STARTED, CAUSE_ERROR_IO, detail);
                break;

            case RESULT_ERROR_UNSUPPORTED_URL:
            case RESULT_ERROR_UNSUPPORTED_FORMAT:
                postStatus(STATUS_NOT_STARTED, CAUSE_ERROR_INVALID_URL, detail);
                break;

            case RESULT_ERROR_EXCEPTION:
                postStatus(STATUS_NOT_STARTED, CAUSE_ERROR_EXCEPTION, detail);
                break;
        }

        // if it's not successful, reset the task and stop self.
        resetTaskAndStop();
    }

    private void executeInstallCommand(Intent intent) {
        if (!verifyRequest(intent)) {
            Log.e(TAG, "Verification failed. Did you use VerificationActivity?");
            return;
        }

        if (mInstallTask != null) {
            Log.e(TAG, "There is already an installation task running");
            return;
        }

        if (isInDynamicSystem()) {
            Log.e(TAG, "We are already running in DynamicSystem");
            return;
        }

        String url = intent.getDataString();
        long systemSize = intent.getLongExtra(DynamicSystemClient.KEY_SYSTEM_SIZE, 0);
        long userdataSize = intent.getLongExtra(DynamicSystemClient.KEY_USERDATA_SIZE, 0);
        mEnableWhenCompleted = intent.getBooleanExtra(KEY_ENABLE_WHEN_COMPLETED, false);
        String dsuSlot = intent.getStringExtra(KEY_DSU_SLOT);
        String publicKey = intent.getStringExtra(KEY_PUBKEY);

        if (userdataSize == 0) {
            userdataSize = DEFAULT_USERDATA_SIZE;
        }

        if (TextUtils.isEmpty(dsuSlot)) {
            dsuSlot = DEFAULT_DSU_SLOT;
        }
        // TODO: better constructor or builder
        mInstallTask =
                new InstallationAsyncTask(
                        url, dsuSlot, publicKey, systemSize, userdataSize, this, mDynSystem, this);

        mInstallTask.execute();

        // start fore ground
        startForeground(NOTIFICATION_ID,
                buildNotification(STATUS_IN_PROGRESS, CAUSE_NOT_SPECIFIED));
    }

    private void executeCancelCommand() {
        if (mInstallTask == null || mInstallTask.getStatus() != RUNNING) {
            Log.e(TAG, "Cancel command triggered, but there is no task running");
            return;
        }

        stopForeground(true);
        mJustCancelledByUser = true;

        if (mInstallTask.cancel(false)) {
            // Will stopSelf() in onResult()
            Log.d(TAG, "Cancel request filed successfully");
        } else {
            Log.e(TAG, "Trying to cancel installation while it's already completed.");
        }
    }

    private void executeDiscardCommand() {
        if (isInDynamicSystem()) {
            Log.e(TAG, "We are now running in AOT, please reboot to normal system first");
            return;
        }

        if (!isDynamicSystemInstalled() && (getStatus() != STATUS_READY)) {
            Log.e(TAG, "Trying to discard AOT while there is no complete installation");
            // Stop foreground state and dismiss stale notification.
            stopForeground(STOP_FOREGROUND_REMOVE);
            resetTaskAndStop();
            return;
        }

        Toast.makeText(this,
                getString(R.string.toast_dynsystem_discarded),
                Toast.LENGTH_LONG).show();

        resetTaskAndStop();
        postStatus(STATUS_NOT_STARTED, CAUSE_INSTALL_CANCELLED, null);

        mDynSystem.remove();
    }

    private void executeRebootToDynSystemCommand() {
        boolean enabled = false;

        if (mInstallTask != null && mInstallTask.isCompleted()) {
            enabled = mInstallTask.commit();
        } else if (isDynamicSystemInstalled()) {
            enabled = mDynSystem.setEnable(true, true);
        } else {
            Log.e(TAG, "Trying to reboot to AOT while there is no complete installation");
            return;
        }

        if (enabled) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

            if (powerManager != null) {
                powerManager.reboot("dynsystem");
            }
            return;
        }

        Log.e(TAG, "Failed to enable DynamicSystem because of native runtime error.");

        Toast.makeText(this,
                getString(R.string.toast_failed_to_reboot_to_dynsystem),
                Toast.LENGTH_LONG).show();

        postStatus(STATUS_NOT_STARTED, CAUSE_ERROR_EXCEPTION, null);
        resetTaskAndStop();
        mDynSystem.remove();
    }

    private void executeRebootToNormalCommand() {
        if (!isInDynamicSystem()) {
            Log.e(TAG, "It's already running in normal system.");
            return;
        }

        // Per current design, we don't have disable() API. AOT is disabled on next reboot.
        // TODO: Use better status query when b/125079548 is done.
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

        if (powerManager != null) {
            powerManager.reboot(null);
        }
    }

    private void executeNotifyIfInUseCommand() {
        int status = getStatus();

        if (status == STATUS_IN_USE) {
            startForeground(NOTIFICATION_ID,
                    buildNotification(STATUS_IN_USE, CAUSE_NOT_SPECIFIED));
        } else if (status == STATUS_READY) {
            startForeground(NOTIFICATION_ID,
                    buildNotification(STATUS_READY, CAUSE_NOT_SPECIFIED));
        } else {
            stopSelf();
        }
    }

    private void resetTaskAndStop() {
        mInstallTask = null;

        new Handler().postDelayed(() -> {
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        }, 50);
    }

    private void prepareNotification() {
        NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);

        mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (mNM != null) {
            mNM.createNotificationChannel(chan);
        }
    }

    private PendingIntent createPendingIntent(String action) {
        Intent intent = new Intent(this, DynamicSystemInstallationService.class);
        intent.setAction(action);
        return PendingIntent.getService(this, 0, intent, 0);
    }

    private Notification buildNotification(int status, int cause) {
        return buildNotification(status, cause, null);
    }

    private Notification buildNotification(int status, int cause, Throwable detail) {
        Notification.Builder builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_system_update_googblue_24dp)
                .setProgress(0, 0, false);

        switch (status) {
            case STATUS_IN_PROGRESS:
                builder.setContentText(getString(R.string.notification_install_inprogress));

                int max = 1024;
                int progress = 0;

                int currentMax = max >> (mNumInstalledPartitions + 1);
                progress = max - currentMax * 2;

                long currentProgress = (mCurrentPartitionInstalledSize >> 20) * currentMax
                        / Math.max(mCurrentPartitionSize >> 20, 1);

                progress += (int) currentProgress;

                builder.setProgress(max, progress, false);

                builder.addAction(new Notification.Action.Builder(
                        null, getString(R.string.notification_action_cancel),
                        createPendingIntent(ACTION_CANCEL_INSTALL)).build());

                break;

            case STATUS_READY:
                String msgCompleted = getString(R.string.notification_install_completed);
                builder.setContentText(msgCompleted)
                        .setStyle(new Notification.BigTextStyle().bigText(msgCompleted));

                builder.addAction(new Notification.Action.Builder(
                        null, getString(R.string.notification_action_discard),
                        createPendingIntent(ACTION_DISCARD_INSTALL)).build());

                builder.addAction(new Notification.Action.Builder(
                        null, getString(R.string.notification_action_reboot_to_dynsystem),
                        createPendingIntent(ACTION_REBOOT_TO_DYN_SYSTEM)).build());

                break;

            case STATUS_IN_USE:
                String msgInUse = getString(R.string.notification_dynsystem_in_use);
                builder.setContentText(msgInUse)
                        .setStyle(new Notification.BigTextStyle().bigText(msgInUse));

                builder.addAction(new Notification.Action.Builder(
                        null, getString(R.string.notification_action_reboot_to_origin),
                        createPendingIntent(ACTION_REBOOT_TO_NORMAL)).build());

                break;

            case STATUS_NOT_STARTED:
                if (cause != CAUSE_NOT_SPECIFIED && cause != CAUSE_INSTALL_CANCELLED) {
                    if (detail instanceof InstallationAsyncTask.ImageValidationException) {
                        builder.setContentText(
                                getString(R.string.notification_image_validation_failed));
                    } else {
                        builder.setContentText(getString(R.string.notification_install_failed));
                    }
                } else {
                    // no need to notify the user if the task is not started, or cancelled.
                }
                break;

            default:
                throw new IllegalStateException("status is invalid");
        }

        return builder.build();
    }

    private boolean verifyRequest(Intent intent) {
        String url = intent.getDataString();

        return VerificationActivity.isVerified(url);
    }

    private void postStatus(int status, int cause, Throwable detail) {
        String statusString;
        String causeString;
        mKeepNotification = false;

        switch (status) {
            case STATUS_NOT_STARTED:
                statusString = "NOT_STARTED";
                break;
            case STATUS_IN_PROGRESS:
                statusString = "IN_PROGRESS";
                break;
            case STATUS_READY:
                statusString = "READY";
                break;
            case STATUS_IN_USE:
                statusString = "IN_USE";
                break;
            default:
                statusString = "UNKNOWN";
                break;
        }

        switch (cause) {
            case CAUSE_INSTALL_COMPLETED:
                causeString = "INSTALL_COMPLETED";
                break;
            case CAUSE_INSTALL_CANCELLED:
                causeString = "INSTALL_CANCELLED";
                break;
            case CAUSE_ERROR_IO:
                causeString = "ERROR_IO";
                mKeepNotification = true;
                break;
            case CAUSE_ERROR_INVALID_URL:
                causeString = "ERROR_INVALID_URL";
                mKeepNotification = true;
                break;
            case CAUSE_ERROR_EXCEPTION:
                causeString = "ERROR_EXCEPTION";
                mKeepNotification = true;
                break;
            default:
                causeString = "CAUSE_NOT_SPECIFIED";
                break;
        }

        Log.d(TAG, "status=" + statusString + ", cause=" + causeString + ", detail=" + detail);

        boolean notifyOnNotificationBar = true;

        if (status == STATUS_NOT_STARTED
                && cause == CAUSE_INSTALL_CANCELLED
                && mJustCancelledByUser) {
            // if task is cancelled by user, do not notify them
            notifyOnNotificationBar = false;
            mJustCancelledByUser = false;
        }

        if (notifyOnNotificationBar) {
            mNM.notify(NOTIFICATION_ID, buildNotification(status, cause, detail));
        }

        for (int i = mClients.size() - 1; i >= 0; i--) {
            try {
                notifyOneClient(mClients.get(i), status, cause, detail);
            } catch (RemoteException e) {
                mClients.remove(i);
            }
        }
    }

    private void notifyOneClient(Messenger client, int status, int cause, Throwable detail)
            throws RemoteException {
        Bundle bundle = new Bundle();

        // TODO: send more info to the clients
        bundle.putLong(DynamicSystemClient.KEY_INSTALLED_SIZE, mCurrentPartitionInstalledSize);

        if (detail != null) {
            bundle.putSerializable(DynamicSystemClient.KEY_EXCEPTION_DETAIL,
                    new ParcelableException(detail));
        }

        client.send(Message.obtain(null,
                  DynamicSystemClient.MSG_POST_STATUS, status, cause, bundle));
    }

    private int getStatus() {
        if (isInDynamicSystem()) {
            return STATUS_IN_USE;
        } else if (isDynamicSystemInstalled()) {
            return STATUS_READY;
        } else if (mInstallTask == null) {
            return STATUS_NOT_STARTED;
        }

        switch (mInstallTask.getStatus()) {
            case PENDING:
                return STATUS_NOT_STARTED;

            case RUNNING:
                return STATUS_IN_PROGRESS;

            case FINISHED:
                if (mInstallTask.isCompleted()) {
                    return STATUS_READY;
                } else {
                    throw new IllegalStateException("A failed InstallationTask is not reset");
                }

            default:
                return STATUS_NOT_STARTED;
        }
    }

    private boolean isInDynamicSystem() {
        return mDynSystem.isInUse();
    }

    private boolean isDynamicSystemInstalled() {
        return mDynSystem.isInstalled();
    }

    void handleMessage(Message msg) {
        switch (msg.what) {
            case DynamicSystemClient.MSG_REGISTER_LISTENER:
                try {
                    Messenger client = msg.replyTo;

                    int status = getStatus();

                    // tell just registered client my status, but do not specify cause
                    notifyOneClient(client, status, CAUSE_NOT_SPECIFIED, null);

                    mClients.add(client);
                } catch (RemoteException e) {
                    // do nothing if we cannot send update to the client
                    e.printStackTrace();
                }

                break;
            case DynamicSystemClient.MSG_UNREGISTER_LISTENER:
                mClients.remove(msg.replyTo);
                break;
            default:
                // do nothing
        }
    }
}
