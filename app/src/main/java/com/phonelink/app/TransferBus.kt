package com.phonelink.app

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

object TransferBus {

    data class Event(
        val rowId: Long,
        val direction: String,
        val name: String,
        val bytesDone: Long,
        val size: Long,
        val bytesPerSec: Long,
        val status: String,
        val message: String? = null
    )

    private val listeners = CopyOnWriteArrayList<(Event) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun subscribe(l: (Event) -> Unit) { listeners.add(l) }
    fun unsubscribe(l: (Event) -> Unit) { listeners.remove(l) }

    fun publish(e: Event) {
        main.post { listeners.toList().forEach { it(e) } }
    }

    @Volatile var active: List<Event> = emptyList()
        private set

    fun updateActive(list: List<Event>) { active = list }
}
