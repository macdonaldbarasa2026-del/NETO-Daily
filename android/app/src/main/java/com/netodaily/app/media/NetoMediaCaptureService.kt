package com.netodaily.app.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.view.WindowManager
import java.io.ByteArrayOutputStream

class NetoMediaCaptureService : Service() {

    companion object {

        const val ACTION_START =
            "com.netodaily.app.media.START_SCREEN"

        const val ACTION_STOP =
            "com.netodaily.app.media.STOP_SCREEN"

        const val EXTRA_RESULT_CODE =
            "result_code"

        const val EXTRA_RESULT_DATA =
            "result_data"

        private const val CHANNEL =
            "neto_screen_share"

        private const val NOTIFICATION_ID = 4201
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var lastFrameTime = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_START -> {

                val resultCode =
                    intent.getIntExtra(
                        EXTRA_RESULT_CODE,
                        0
                    )

                val resultData =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(
                            EXTRA_RESULT_DATA,
                            Intent::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Intent>(
                            EXTRA_RESULT_DATA
                        )
                    }

                if (
                    resultData != null &&
                    resultCode != 0
                ) {
                    startCapture(
                        resultCode,
                        resultData
                    )
                }
            }

            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startCapture(
        resultCode: Int,
        resultData: Intent
    ) {

        stopCapture()

        val notification =
            Notification.Builder(
                this,
                CHANNEL
            )
                .setContentTitle(
                    "NETO screen sharing"
                )
                .setContentText(
                    "NETO can see your shared screen."
                )
                .setSmallIcon(
                    com.netodaily.app.R.mipmap.ic_launcher
                )
                .setOngoing(true)
                .build()

        if (Build.VERSION.SDK_INT >= 29) {

            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

        } else {

            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }

        val manager =
            getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        projection =
            manager.getMediaProjection(
                resultCode,
                resultData
            )

        if (projection == null) {
            stopSelf()
            return
        }

        projection!!.registerCallback(
            object : MediaProjection.Callback() {

                override fun onStop() {
                    stopCapture()
                    stopSelf()
                }
            },
            null
        )

        val metrics =
            resources.displayMetrics

        val width =
            (metrics.widthPixels * 0.75f)
                .toInt()
                .coerceAtLeast(640)

        val height =
            (metrics.heightPixels * 0.75f)
                .toInt()
                .coerceAtLeast(360)

        thread =
            HandlerThread("NETO-Screen").also {
                it.start()
            }

        handler =
            Handler(thread!!.looper)

        reader =
            ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )

        reader!!.setOnImageAvailableListener(
            { source ->

                val image =
                    source.acquireLatestImage()
                        ?: return@setOnImageAvailableListener

                try {

                    val now =
                        System.currentTimeMillis()

                    if (
                        now - lastFrameTime >= 1000L
                    ) {

                        lastFrameTime = now

                        val plane =
                            image.planes[0]

                        val buffer =
                            plane.buffer

                        val pixelStride =
                            plane.pixelStride

                        val rowStride =
                            plane.rowStride

                        val rowPadding =
                            rowStride -
                                pixelStride * width

                        val bitmapWidth =
                            width +
                                rowPadding /
                                pixelStride

                        val bitmap =
                            Bitmap.createBitmap(
                                bitmapWidth,
                                height,
                                Bitmap.Config.ARGB_8888
                            )

                        buffer.rewind()

                        bitmap.copyPixelsFromBuffer(
                            buffer
                        )

                        val output =
                            ByteArrayOutputStream()

                        bitmap.compress(
                            Bitmap.CompressFormat.JPEG,
                            65,
                            output
                        )

                        bitmap.recycle()

                        NetoScreenFrameBus.publish(
                            output.toByteArray()
                        )
                    }

                } catch (_: Exception) {

                } finally {

                    image.close()
                }

            },
            handler
        )

        display =
            projection!!.createVirtualDisplay(
                "NETO-Screen",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface,
                null,
                handler
            )
    }

    private fun stopCapture() {

        try {
            display?.release()
        } catch (_: Exception) {
        }

        display = null

        try {
            reader?.close()
        } catch (_: Exception) {
        }

        reader = null

        try {
            projection?.stop()
        } catch (_: Exception) {
        }

        projection = null

        try {
            thread?.quitSafely()
        } catch (_: Exception) {
        }

        thread = null
        handler = null
    }

    private fun createChannel() {

        if (Build.VERSION.SDK_INT >= 26) {

            val channel =
                NotificationChannel(
                    CHANNEL,
                    "NETO screen sharing",
                    NotificationManager.IMPORTANCE_LOW
                )

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(
                channel
            )
        }
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null

    override fun onDestroy() {

        stopCapture()

        super.onDestroy()
    }
}
