package app.leafreader
import app.leafreader.data.*

/** Fake fixtures only; never part of the APK. */
class MemorySessionVault : SessionVault {
    var value: SavedSession? = null
    override fun load(): SessionLoad = value?.let { SessionLoad.Ready(it) } ?: SessionLoad.Empty
    override fun save(session: SavedSession): Boolean { value = session; return true }
    override fun clear() { value = null }
}
