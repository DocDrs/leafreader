package app.leafreader.data

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** UI and persisted JobService may reconstruct the same source. They share file locks and request pacing. */
internal object IndependentSourceIo {
    class Scope {
        val books=ConcurrentHashMap<String,Mutex>()
        val metadata=ConcurrentHashMap<String,Mutex>()
        val requests=Mutex()
        val catalog=Mutex()
        val lastRequest=AtomicLong(0)
    }
    private val scopes=ConcurrentHashMap<String,Scope>()
    fun forRoot(root:java.io.File)=scopes.getOrPut(root.absolutePath){Scope()}
}
