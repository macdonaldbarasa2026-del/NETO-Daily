package com.netodaily.app.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
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

        const val ACTION_STATE =
            "com.netodaily.app.media.SCREEN_STATE"

        const val EXTRA_ACTIVE =
            "active"

        private const val CHANNEL =
            "neto_screen_share"

        private const val NOTIFICATION_ID =
            4201

        private const val MAX_WIDTH =
            960

        private const val MAX_HEIGHT =
            540

        private const val FRAME_INTERVAL_MS = 250L
    }

    private var projection:
        MediaProjection? = null

    private var display:
        VirtualDisplay? = null

    private var reader:
        ImageReader? = null

    private var thread:
        HandlerThread? = null

    private var handler:
        Handler? = null

    @Volatile
    private var capturing = false

    @Volatile
    private var stopping = false

    private var lastFrameAt = 0L


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
                    resultCode != 0 &&
                    resultData != null
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

        stopping = false
        capturing = true
        lastFrameAt = 0L

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
                ServiceInfo
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

        projection = runCatching {
            manager.getMediaProjection(
                resultCode,
                resultData
            )
        }.getOrNull()

        val mediaProjection =
            projection ?: run {
                stopCapture()
                stopSelf()
                return
            }

        mediaProjection.registerCallback(
            object : MediaProjection.Callback() {

                override fun onStop() {
                    if (!stopping) {
                        stopCapture()
                        stopSelf()
                    }
                }
            },
            null
        )

        val metrics =
            resources.displayMetrics

        val originalWidth =
            metrics.widthPixels

        val originalHeight =
            metrics.heightPixels

        val scale =
            minOf(
                MAX_WIDTH.toFloat() /
                    originalWidth.toFloat(),
                MAX_HEIGHT.toFloat() /
                    originalHeight.toFloat(),
                1f
            )

        val width =
            (originalWidth * scale)
                .toInt()
                .coerceAtLeast(320)

        val height =
            (originalHeight * scale)
                .toInt()
                .coerceAtLeast(240)

        thread =
            HandlerThread(
                "NETO-Screen"
            ).also {
                it.start()
            }

        handler =
            Handler(
                thread!!.looper
            )

        reader =
            ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )

        reader!!.setOnImageAvailableListener(
            { source ->

                if (!capturing || stopping) {
                    return@setOnImageAvailableListener
                }

                val now = System.currentTimeMillis()

                if (now - lastFrameAt < FRAME_INTERVAL_MS) {
                    source.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }

                lastFrameAt = now

                val image =
                    source.acquireLatestImage()
                        ?: return@setOnImageAvailableListener

                try {

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

                    val paddedBitmap =
                        Bitmap.createBitmap(
                            bitmapWidth,
                            height,
                            Bitmap.Config.ARGB_8888
                        )

                    buffer.rewind()

                    paddedBitmap.copyPixelsFromBuffer(
                        buffer
                    )

                    val bitmap =
                        if (bitmapWidth != width) {
                            Bitmap.createBitmap(
                                paddedBitmap,
                                0,
                                0,
                                width,
                                height
                            ).also {
                                paddedBitmap.recycle()
                            }
                        } else {
                            paddedBitmap
                        }

                    val output =
                        ByteArrayOutputStream()

                    bitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        60,
                        output
                    )

                    bitmap.recycle()

                    if (capturing && !stopping) {
                        NetoScreenFrameBus.publish(
                            output.toByteArray()
                        )
                    }

                } catch (_: Throwable) {

                    // A single bad frame must not stop
                    // the screen-share service.

                } finally {
                    image.close()
                }

            },
            handler
        )

        display = runCatching {
            mediaProjection.createVirtualDisplay(
                "NETO-Screen",
                width,
                height,
                metrics.densityDpi,
                DisplayManager
                    .VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface,
                null,
                handler
            )
        }.getOrNull()

        if (display == null) {
            stopCapture()
            stopSelf()
            return
        }

        sendState(true)
    }

    private fun stopCapture() {

        if (stopping) return

        stopping = true
        capturing = false
        lastFrameAt = 0L

        NetoScreenFrameBus.clear()
        sendState(false)

        runCatching {
            display?.release()
        }

        display = null

        runCatching {
            reader?.close()
        }

        reader = null

        val currentProjection = projection
        projection = null

        runCatching {
            currentProjection?.stop()
        }

        runCatching {
            thread?.quitSafely()
        }

        thread = null
        handler = null
    }

    private fun sendState(active: Boolean) {
        sendBroadcast(
            Intent(ACTION_STATE).apply {
                setPackage(packageName)
                putExtra(EXTRA_ACTIVE, active)
            }
        )
    }

    private fun createChannel() {

        if (Build.VERSION.SDK_INT >= 26) {

            val channel =
                NotificationChannel(
                    CHANNEL,
                    "NETO screen sharing",
                    NotificationManager
                        .IMPORTANCE_LOW
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
