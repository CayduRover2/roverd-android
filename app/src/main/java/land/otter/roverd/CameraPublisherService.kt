package land.otter.roverd

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class CameraPublisherService : Service() {
    companion object {
        private const val CHANNEL_ID = "roverd-camera"
        private const val NOTIFICATION_ID = 2

        private const val AUTO_RESTART_MS = 2_000L

        const val ACTION_START = "camera_start"
        const val ACTION_RESTART = "camera_restart"
        const val ACTION_STOP = "camera_stop"
    }

    private var streamer: Camera2H264Streamer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRestart: Runnable? = null
    private var restartAttempt = 0
    private var allowAutoRestart = true
    private var destroying = false

    override fun onCreate() {
        super.onCreate()
        RoverRuntimeState.initialize(this)
        promoteToForeground("Camera publisher ready")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                RoverRuntimeState.log("CAMERA manual stop requested")
                allowAutoRestart = false
                cancelPendingRestart()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                RoverRuntimeState.log("CAMERA manual restart requested")
                allowAutoRestart = true
                restartAttempt = 0
                cancelPendingRestart()
                startPipeline(forceRestart = true)
            }
            ACTION_START, null -> {
                allowAutoRestart = true
                if (streamer == null) {
                    RoverRuntimeState.log("CAMERA start requested")
                    startPipeline(forceRestart = false)
                } else {
                    RoverRuntimeState.log("CAMERA start ignored; pipeline already running")
                }
            }
        }
        return START_STICKY
    }

    private fun startPipeline(forceRestart: Boolean) {
        if (!forceRestart && streamer != null) return
        cancelPendingRestart()
        streamer?.close()
        streamer = null
        RoverRuntimeState.resetCameraCounters()

        val config = RoverSettings.load(this)
        if (!config.cameraEnabled) {
            allowAutoRestart = false
            RoverRuntimeState.setCameraPipelineState(running = false, state = "Disabled", error = "")
            stopSelf()
            return
        }
        if (Build.VERSION.SDK_INT < 21) {
            allowAutoRestart = false
            RoverRuntimeState.setCameraPipelineState(
                running = false,
                state = "Unsupported Android version",
                error = "Camera streaming currently requires Android 5.0 / API 21+",
            )
            RoverRuntimeState.log("CAMERA stream not started: API ${Build.VERSION.SDK_INT} < 21")
            stopSelf()
            return
        }
        if (config.cameraSource != "USB_WEBCAM" && Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            allowAutoRestart = false
            RoverRuntimeState.setCameraPipelineState(
                running = false,
                state = "Camera permission missing",
                error = "CAMERA permission not granted",
            )
            RoverRuntimeState.log("CAMERA stream not started: CAMERA permission missing")
            stopSelf()
            return
        }

        // USB_WEBCAM is handled by RoverUvcSource through the Android USB host/UVC stack.
        // Do not look for LENS_FACING_EXTERNAL here: Samsung/Android firmware does not
        // consistently expose the C920 through Camera2 even though USB/UVC access works.
        val effectiveConfig = config

        try {
            RoverRuntimeState.log("CAMERA publisher service starting")
            streamer = Camera2H264Streamer(this, effectiveConfig) { reason ->
                handler.post { schedulePipelineRestart(reason) }
            }.also { it.start() }
            restartAttempt = 0
            val sourceLabel = if (effectiveConfig.cameraSource == "USB_WEBCAM") "USB webcam ${effectiveConfig.cameraId}" else "Camera ${effectiveConfig.cameraId}"
            updateNotification("$sourceLabel -> MediaMTX")
        } catch (t: Throwable) {
            streamer?.close()
            streamer = null
            RoverRuntimeState.setCameraPipelineState(
                running = false,
                state = "Camera startup failed",
                error = t.message ?: t.javaClass.simpleName,
            )
            RoverRuntimeState.log("CAMERA startup failure: ${t.stackTraceToString()}")
            updateNotification("Camera failed: ${t.message ?: t.javaClass.simpleName}")
            schedulePipelineRestart("startup failure: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun schedulePipelineRestart(reason: String) {
        if (!allowAutoRestart || destroying || !RoverSettings.load(this).cameraEnabled) return
        if (pendingRestart != null) return

        val attempt = ++restartAttempt
        val delay = AUTO_RESTART_MS
        RoverRuntimeState.recordCameraReconnect()
        RoverRuntimeState.setCameraPipelineState(
            running = false,
            state = "Restarting camera in ${delay}ms",
            error = reason,
        )
        RoverRuntimeState.log("CAMERA full pipeline restart scheduled in ${delay}ms attempt=$attempt fixed-delay: $reason")
        updateNotification("Camera recovering in ${delay / 1000}s")

        val task = Runnable {
            pendingRestart = null
            if (!allowAutoRestart || destroying || !RoverSettings.load(this).cameraEnabled) return@Runnable
            RoverRuntimeState.log("CAMERA full pipeline recovery starting")
            startPipeline(forceRestart = true)
        }
        pendingRestart = task
        handler.postDelayed(task, delay)
    }

    private fun cancelPendingRestart() {
        pendingRestart?.let { handler.removeCallbacks(it) }
        pendingRestart = null
    }

    @Suppress("DEPRECATION")
    private fun promoteToForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Rover camera", NotificationManager.IMPORTANCE_LOW),
                )
            }
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Roverd Android camera")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        destroying = true
        allowAutoRestart = false
        cancelPendingRestart()
        streamer?.close()
        streamer = null
        RoverRuntimeState.setCameraPipelineState(running = false, state = "Stopped", error = "")
        RoverRuntimeState.log("CAMERA publisher service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
