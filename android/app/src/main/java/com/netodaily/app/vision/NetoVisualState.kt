package com.netodaily.app.vision

sealed class NetoVisualState {

    data object None : NetoVisualState()

    data object FrontCamera : NetoVisualState()

    data object BackCamera : NetoVisualState()

    data object Screen : NetoVisualState()
}
