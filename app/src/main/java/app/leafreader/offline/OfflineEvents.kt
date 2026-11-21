package app.leafreader.offline

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicLong

object OfflineEvents {
    private val sequence=AtomicLong()
    val revision=MutableStateFlow(0L)
    fun changed(){revision.value=sequence.incrementAndGet()}
}
