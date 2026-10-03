package com.netodaily.app.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

class NetoCameraController(
    private val context: Context,
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit
) {

    enum class Lens {
        FRONT,
        BACK
    }

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var lastFrameTime = 0L

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    fun start(lens: Lens) {

        if (!hasPermission()) {
            onError("Camera permission is required.")
            return
        }

        stop()

        thread = HandlerThread("NETO-Camera").also {
            it.start()
        }

        handler = Handler(thread!!.looper)

        val manager =
            context.getSystemService(
                Context.CAMERA_SERVICE
            ) as CameraManager

        val cameraId = findCamera(manager, lens)

        if (cameraId == null) {
            onError("Requested camera is not available.")
            return
        }

        reader =
            ImageReader.newInstance(
                1280,
                720,
                ImageFormat.YUV_420_888,
                2
            )

        reader!!.setOnImageAvailableListener(
            { source ->

                val image =
                    source.acquireLatestImage()
                        ?: return@setOnImageAvailableListener

                try {

                    val now = System.currentTimeMillis()

                    // Gemini Live video input is limited to
                    // about one image per second.
                    if (now - lastFrameTime >= 1000L) {

                        lastFrameTime = now

                        val jpeg =
                            yuv420ToJpeg(image)

                        onFrame(jpeg)
                    }

                } catch (e: Exception) {

                    onError(
                        e.message ?: "Camera frame error."
                    )

                } finally {
                    image.close()
                }

            },
            handler
        )

        try {

            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {

                    override fun onOpened(
                        device: CameraDevice
                    ) {
                        camera = device
                        createCaptureSession()
                    }

                    override fun onDisconnected(
                        device: CameraDevice
                    ) {
                        device.close()
                        camera = null
                    }

                    override fun onError(
                        device: CameraDevice,
                        error: Int
                    ) {
                        device.close()
                        camera = null
                        onError("Camera error: $error")
                    }
                },
                handler
            )

        } catch (_: SecurityException) {

            onError("Camera permission was denied.")

        } catch (e: Exception) {

            onError(
                e.message ?: "Could not open camera."
            )
        }
    }

    private fun createCaptureSession() {

        val device = camera ?: return
        val surface = reader?.surface ?: return

        try {

            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(
                        captureSession: CameraCaptureSession
                    ) {

                        session = captureSession

                        try {

                            val request =
                                device.createCaptureRequest(
                                    CameraDevice.TEMPLATE_RECORD
                                )

                            request.addTarget(surface)

                            request.set(
                                CaptureRequest.CONTROL_MODE,
                                CameraMetadata.CONTROL_MODE_AUTO
                            )

                            captureSession.setRepeatingRequest(
                                request.build(),
                                null,
                                handler
                            )

                        } catch (e: Exception) {

                            onError(
                                e.message
                                    ?: "Camera capture failed."
                            )
                        }
                    }

                    override fun onConfigureFailed(
                        captureSession: CameraCaptureSession
                    ) {
                        onError(
                            "Could not configure camera."
                        )
                    }
                },
                handler
            )

        } catch (e: Exception) {

            onError(
                e.message ?: "Could not start camera."
            )
        }
    }

    private fun findCamera(
        manager: CameraManager,
        lens: Lens
    ): String? {

        for (id in manager.cameraIdList) {

            val characteristics =
                manager.getCameraCharacteristics(id)

            val facing =
                characteristics.get(
                    CameraCharacteristics.LENS_FACING
                )

            if (
                lens == Lens.FRONT &&
                facing == CameraCharacteristics.LENS_FACING_FRONT
            ) {
                return id
            }

            if (
                lens == Lens.BACK &&
                facing == CameraCharacteristics.LENS_FACING_BACK
            ) {
                return id
            }
        }

        return null
    }

    private fun yuv420ToJpeg(
        image: android.media.Image
    ): ByteArray {

        val width = image.width
        val height = image.height

        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]

        val yBuffer = y.buffer
        val uBuffer = u.buffer
        val vBuffer = v.buffer

        val yRowStride = y.rowStride
        val yPixelStride = y.pixelStride
        val uRowStride = u.rowStride
        val uPixelStride = u.pixelStride
        val vRowStride = v.rowStride
        val vPixelStride = v.pixelStride

        val nv21 =
            ByteArray(width * height * 3 / 2)

        var offset = 0

        for (row in 0 until height) {

            val rowStart =
                row * yRowStride

            for (col in 0 until width) {

                val index =
                    rowStart + col * yPixelStride

                if (index < yBuffer.limit()) {
                    nv21[offset++] =
                        yBuffer.get(index)
                }
            }
        }

        for (row in 0 until height / 2) {

            for (col in 0 until width / 2) {

                val uIndex =
                    row * uRowStride +
                        col * uPixelStride

                val vIndex =
                    row * vRowStride +
                        col * vPixelStride

                if (
                    uIndex < uBuffer.limit() &&
                    vIndex < vBuffer.limit()
                ) {
                    nv21[offset++] =
                        vBuffer.get(vIndex)

                    nv21[offset++] =
                        uBuffer.get(uIndex)
                }
            }
        }

        val yuv =
            YuvImage(
                nv21,
                ImageFormat.NV21,
                width,
                height,
                null
            )

        val output =
            ByteArrayOutputStream()

        yuv.compressToJpeg(
            Rect(0, 0, width, height),
            70,
            output
        )

        return output.toByteArray()
    }

    fun stop() {

        try {
            session?.stopRepeating()
        } catch (_: Exception) {
        }

        try {
            session?.close()
        } catch (_: Exception) {
        }

        session = null

        try {
            camera?.close()
        } catch (_: Exception) {
        }

        camera = null

        try {
            reader?.close()
        } catch (_: Exception) {
        }

        reader = null

        try {
            thread?.quitSafely()
        } catch (_: Exception) {
        }

        thread = null
        handler = null
    }
}
