package com.netodaily.app.media

object NetoScreenFrameBus {

    @Volatile
    private var listener: ((ByteArray) -> Unit)? = null

    fun setListener(
        callback: ((ByteArray) -> Unit)?
    ) {
        listener = callback
    }

    fun publish(
        jpeg: ByteArray
    ) {
        listener?.invoke(jpeg)
    }
}
