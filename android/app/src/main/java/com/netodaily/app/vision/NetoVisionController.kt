package com.netodaily.app.vision

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != NetoMediaCaptureService.ACTION_STATE) {
                return
            }

            val active = intent.getBooleanExtra(
                NetoMediaCaptureService.EXTRA_ACTIVE,
                false
            )

            if (!active && state == State.SCREEN) {
                state = State.OFF
                onStateChanged(state)
            }
        }
    }

    private var receiverRegistered = false

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
        if (!receiverRegistered) {
            val filter = IntentFilter(
                NetoMediaCaptureService.ACTION_STATE
            )

            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(
                    screenStateReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(
                    screenStateReceiver,
                    filter
                )
            }

            receiverRegistered = true
        }

        frameRouter.start()
    }

    fun startFrontCamera() {
        frameRouter.start()

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
        frameRouter.start()

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
        if (resultCode == 0) {
            onStateChanged(State.OFF)
            return
        }

        frameRouter.start()
        stopCameraOnly()
        NetoScreenFrameBus.clear()

        val intent = Intent(context, NetoMediaCaptureService::class.java).apply {
            action = NetoMediaCaptureService.ACTION_START
            putExtra(NetoMediaCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(NetoMediaCaptureService.EXTRA_RESULT_DATA, data)
        }

        runCatching {
            context.startForegroundService(intent)
        }.onFailure {
            NetoScreenFrameBus.clear()
            state = State.OFF
            onStateChanged(state)
        }
    }

    fun stopScreenShare() {
        NetoScreenFrameBus.clear()

        context.stopService(
            Intent(context, NetoMediaCaptureService::class.java)
        )

        if (state == State.SCREEN) {
            state = State.OFF
            onStateChanged(state)
        }
    }

    fun currentState(): State = state

    fun stop() {
        NetoScreenFrameBus.clear()

        cameraController.stop()

        context.stopService(
            Intent(context, NetoMediaCaptureService::class.java)
        )

        frameRouter.stop()

        state = State.OFF
        onStateChanged(state)
    }

    fun dispose() {
        NetoScreenFrameBus.clear()
        cameraController.stop()
        context.stopService(
            Intent(context, NetoMediaCaptureService::class.java)
        )
        frameRouter.stop()

        if (receiverRegistered) {
            runCatching {
                context.unregisterReceiver(screenStateReceiver)
            }
            receiverRegistered = false
        }

        state = State.OFF
    }
}
