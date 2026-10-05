package com.netodaily.app.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class NetoCameraController(
    private val context: Context,
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (Throwable) -> Unit
) {

    enum class Lens {
        FRONT,
        BACK
    }

    companion object {
        private const val WIDTH = 960
        private const val HEIGHT = 540
        private const val JPEG_QUALITY = 65
    }

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val running = AtomicBoolean(false)

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun start(lens: Lens) {
        if (!hasPermission()) return

        stop()

        val facing = when (lens) {
            Lens.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
            Lens.BACK -> CameraCharacteristics.LENS_FACING_BACK
        }

        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == facing
        } ?: return

        thread = HandlerThread("NETO-Camera").also { it.start() }
        handler = Handler(thread!!.looper)

        imageReader = ImageReader.newInstance(
            WIDTH,
            HEIGHT,
            ImageFormat.YUV_420_888,
            2
        ).also { reader ->

            reader.setOnImageAvailableListener(
                { source ->
                    val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener

                    try {
                        val jpeg = yuvToJpeg(image)
                        if (
                            running.get() &&
                            jpeg.isNotEmpty()
                        ) {
                            onFrame(jpeg)
                        }
                    } catch (t: Throwable) {
                        onError(t)
                    } finally {
                        image.close()
                    }
                },
                handler
            )
        }

        running.set(true)

        try {
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {

                    override fun onOpened(camera: CameraDevice) {
                        if (!running.get()) {
                            camera.close()
                            return
                        }

                        cameraDevice = camera
                        createCaptureSession(camera)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()

                        if (running.get() && cameraDevice === camera) {
                            cameraDevice = null
                            onError(
                                IllegalStateException(
                                    "Camera disconnected."
                                )
                            )
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()

                        if (running.get() && cameraDevice === camera) {
                            cameraDevice = null
                            onError(
                                IllegalStateException(
                                    "Camera error: $error"
                                )
                            )
                        }
                    }
                },
                handler
            )
        } catch (t: Throwable) {
            onError(t)
            stop()
        }
    }

    private fun createCaptureSession(camera: CameraDevice) {
        val readerSurface = imageReader?.surface ?: return

        camera.createCaptureSession(
            listOf(readerSurface),
            object : CameraCaptureSession.StateCallback() {

                override fun onConfigured(session: CameraCaptureSession) {
                    if (!running.get()) {
                        session.close()
                        return
                    }

                    captureSession = session

                    try {
                        val request =
                            camera.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW
                            ).apply {
                                addTarget(readerSurface)
                            }

                        session.setRepeatingRequest(
                            request.build(),
                            null,
                            handler
                        )
                    } catch (t: Throwable) {
                        onError(t)
                    }
                }

                override fun onConfigureFailed(
                    session: CameraCaptureSession
                ) {
                    if (running.get()) {
                        onError(
                            IllegalStateException(
                                "Unable to configure camera"
                            )
                        )
                    }
                }
            },
            handler
        )
    }

    private fun yuvToJpeg(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val planes = image.planes

        val nv21 = ByteArray(width * height + width * height / 2)

        var outputIndex = 0

        // Y plane.
        val yPlane = planes[0]
        val yBuffer = yPlane.buffer.duplicate()
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        for (row in 0 until height) {
            val rowStart = row * yRowStride

            for (col in 0 until width) {
                val index = rowStart + col * yPixelStride
                if (index < yBuffer.limit()) {
                    nv21[outputIndex++] = yBuffer.get(index)
                }
            }
        }

        // Interleaved VU chroma for NV21.
        val uPlane = planes[1]
        val vPlane = planes[2]
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()

        val chromaHeight = height / 2
        val chromaWidth = width / 2

        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val uIndex =
                    row * uPlane.rowStride +
                        col * uPlane.pixelStride

                val vIndex =
                    row * vPlane.rowStride +
                        col * vPlane.pixelStride

                if (
                    uIndex < uBuffer.limit() &&
                    vIndex < vBuffer.limit()
                ) {
                    nv21[outputIndex++] = vBuffer.get(vIndex)
                    nv21[outputIndex++] = uBuffer.get(uIndex)
                }
            }
        }

        val yuvImage = android.graphics.YuvImage(
            nv21,
            ImageFormat.NV21,
            width,
            height,
            null
        )

        return ByteArrayOutputStream().use { output ->
            yuvImage.compressToJpeg(
                android.graphics.Rect(
                    0,
                    0,
                    width,
                    height
                ),
                JPEG_QUALITY,
                output
            )

            output.toByteArray()
        }
    }

    fun stop() {
        running.set(false)

        try {
            captureSession?.stopRepeating()
        } catch (_: Throwable) {
        }

        try {
            captureSession?.close()
        } catch (_: Throwable) {
        }

        captureSession = null

        try {
            cameraDevice?.close()
        } catch (_: Throwable) {
        }

        cameraDevice = null

        try {
            imageReader?.close()
        } catch (_: Throwable) {
        }

        imageReader = null

        thread?.quitSafely()
        thread = null
        handler = null
    }
}
