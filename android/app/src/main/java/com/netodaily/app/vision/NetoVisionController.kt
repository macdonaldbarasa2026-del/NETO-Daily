package com.netodaily.app.vision

import android.content.Context
import android.content.Intent
import com.netodaily.app.live.NetoLiveSession
import com.netodaily.app.media.NetoCameraController
import com.netodaily.app.media.NetoMediaCaptureService
import com.netodaily.app.media.NetoScreenFrameBus

class NetoVisionController(
    private val context: Context,
    private val liveSession: NetoLiveSession,
    private val onPreviewFrame: (ByteArray) -> Unit,
    private val onStateChanged: (State) -> Unit
) {

    enum class Lens {
        FRONT,
        BACK
    }

    enum class State {
        OFF,
        FRONT_CAMERA,
        BACK_CAMERA,
        SCREEN
    }

    private var state = State.OFF

    private val frameRouter = NetoLiveFrameRouter { frame ->
        liveSession.sendVideoFrame(frame)
        onPreviewFrame(frame)
    }

    private val cameraController = NetoCameraController(
        context = context,
        onFrame = { frame ->
            frameRouter.submit(frame)
        },
        onError = {
            stopCamera()
        }
    )

    init {
        NetoScreenFrameBus.setListener { frame ->
            frameRouter.submit(frame)
        }
    }

    fun start() {
        frameRouter.start()
    }

    fun startFrontCamera() {
        if (!cameraController.hasPermission()) {
            onStateChanged(State.OFF)
            return
        }

        stopCameraOnly()

        cameraController.start(NetoCameraController.Lens.FRONT)

        state = State.FRONT_CAMERA
        onStateChanged(state)
    }

    fun startBackCamera() {
        if (!cameraController.hasPermission()) {
            onStateChanged(State.OFF)
            return
        }

        stopCameraOnly()

        cameraController.start(NetoCameraController.Lens.BACK)

        state = State.BACK_CAMERA
        onStateChanged(state)
    }

    fun switchCamera() {
        when (state) {
            State.FRONT_CAMERA -> startBackCamera()
            State.BACK_CAMERA -> startFrontCamera()
            else -> startFrontCamera()
        }
    }

    fun stopCamera() {
        stopCameraOnly()

        if (state == State.FRONT_CAMERA || state == State.BACK_CAMERA) {
            state = State.OFF
            onStateChanged(state)
        }
    }

    private fun stopCameraOnly() {
        cameraController.stop()
    }

    fun startScreenShare(resultCode: Int, data: Intent) {
        stopCameraOnly()

        val intent = Intent(context, NetoMediaCaptureService::class.java).apply {
            action = NetoMediaCaptureService.ACTION_START
            putExtra(NetoMediaCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(NetoMediaCaptureService.EXTRA_DATA, data)
        }

        context.startForegroundService(intent)

        state = State.SCREEN
        onStateChanged(state)
    }

    fun stopScreenShare() {
        context.startService(
            Intent(context, NetoMediaCaptureService::class.java).apply {
                action = NetoMediaCaptureService.ACTION_STOP
            }
        )

        if (state == State.SCREEN) {
            state = State.OFF
            onStateChanged(state)
        }
    }

    fun currentState(): State = state

    fun stop() {
        cameraController.stop()

        context.startService(
            Intent(context, NetoMediaCaptureService::class.java).apply {
                action = NetoMediaCaptureService.ACTION_STOP
            }
        )

        frameRouter.stop()
        NetoScreenFrameBus.clear()

        state = State.OFF
        onStateChanged(state)
    }
}
