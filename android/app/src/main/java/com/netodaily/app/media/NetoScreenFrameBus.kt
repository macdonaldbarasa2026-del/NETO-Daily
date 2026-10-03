package com.netodaily.app.media

object NetoScreenFrameBus {

    @Volatile
    private var listener: ((ByteArray) -> Unit)? = null

    fun setListener(listener: ((ByteArray) -> Unit)?) {
        this.listener = listener
    }

    fun publish(frame: ByteArray) {
        listener?.invoke(frame)
    }

    fun clear() {
        listener = null
    }
}
