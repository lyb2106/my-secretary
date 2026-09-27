package com.lyb.mysecretary.pipeline

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import com.lyb.mysecretary.App
import com.lyb.mysecretary.MainActivity
import com.lyb.mysecretary.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service so a running conversion is not killed when the screen turns off.
 * Only one job runs at a time; state is published through [Pipeline.state].
 */
class TranscriptionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Pipeline.cancel()
            return START_NOT_STICKY
        }
        // startForegroundService() requires startForeground() on every start, even when ignored.
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        val uri = intent?.data
        if (job?.isActive == true) return START_NOT_STICKY
        if (uri == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val name = intent.getStringExtra(EXTRA_NAME) ?: uri.lastPathSegment.orEmpty()
        val time = intent.getLongExtra(EXTRA_TIME, System.currentTimeMillis())
        job = scope.launch {
            try {
                Pipeline.run(application as App, uri, name, time)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // mediaProcessing FGS time limit reached (hours); never expected for a 5-minute file.
        Pipeline.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, TranscriptionService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("나의 아침비서")
            .setContentText("녹음을 할 일 목록으로 변환하는 중…")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "취소", cancel).build())
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CANCEL = "com.lyb.mysecretary.CANCEL"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_TIME = "time"

        fun start(context: Context, uri: Uri, name: String, recordingTime: Long) {
            val intent = Intent(context, TranscriptionService::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_TIME, recordingTime)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, TranscriptionService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
