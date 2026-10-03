package com.netodaily.app.vision

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class NetoVisualPermissionController(
    private val activity: Activity,
    private val onCameraGranted: () -> Unit,
    private val onScreenShareResult: (Int, Intent) -> Unit
) {

    companion object {
        const val CAMERA_REQUEST = 7201
        const val SCREEN_REQUEST = 7202
    }

    fun requestCamera() {
        if (
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            onCameraGranted()
            return
        }

        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_REQUEST
        )
    }

    fun requestScreenShare() {
        val manager =
            activity.getSystemService(
                Activity.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        activity.startActivityForResult(
            manager.createScreenCaptureIntent(),
            SCREEN_REQUEST
        )
    }

    fun onRequestPermissionsResult(
        requestCode: Int,
        grantResults: IntArray
    ) {
        if (requestCode != CAMERA_REQUEST) return

        if (
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            onCameraGranted()
        }
    }

    fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        if (requestCode != SCREEN_REQUEST) return
        if (resultCode != Activity.RESULT_OK) return
        if (data == null) return

        onScreenShareResult(resultCode, data)
    }
}
