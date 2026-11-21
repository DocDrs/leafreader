package app.leafreader.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.text.format.DateFormat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.Date
import kotlin.math.roundToInt

data class ReadingStatusSnapshot(val timeLabel: String, val batteryLabel: String)

object ReadingStatusFormatting {
    fun time(context: Context, epochMillis: Long): String = runCatching {
        // Android selects the system user's current locale and 12/24-hour preference.
        DateFormat.getTimeFormat(context).format(Date(epochMillis)).takeIf { it.isNotBlank() } ?: "—"
    }.getOrDefault("—")

    fun battery(level: Int, scale: Int, present: Boolean = true): String {
        if (!present || scale <= 0 || level < 0 || level > scale) return "—"
        return "${(level.toDouble() * 100 / scale).roundToInt()}%"
    }

    internal fun battery(intent: Intent): String = battery(
        intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
        intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
        intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true),
    )
}

/**
 * A lifecycle-bound display feed. System minute broadcasts avoid an independent ticking timer.
 * No permissions, network, content binding or pagination. The consumer should only invalidate drawing.
 * Call close() when a reading View detaches, even if the Activity lifecycle remains alive.
 */
class ReadingStatusProvider(
    context: Context,
    private val lifecycle: Lifecycle,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onChanged: (ReadingStatusSnapshot) -> Unit,
) : DefaultLifecycleObserver, AutoCloseable {
    private val appContext = context.applicationContext
    private var battery = "—"
    private var closed = false
    internal var listening = false
        private set
    var snapshot = ReadingStatusSnapshot(ReadingStatusFormatting.time(appContext, clock()), battery)
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!listening || closed || intent == null) return
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) battery = ReadingStatusFormatting.battery(intent)
            refresh()
        }
    }

    init {
        lifecycle.addObserver(this)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
    }

    override fun onResume(owner: LifecycleOwner) = start()
    override fun onPause(owner: LifecycleOwner) = stop()
    override fun onDestroy(owner: LifecycleOwner) = close()

    private fun start() {
        if (closed || listening) return
        listening = true
        battery = "—" // A paused snapshot is not evidence of the current system battery level.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        try {
            val sticky = if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, filter)
            }
            if (sticky?.action == Intent.ACTION_BATTERY_CHANGED) battery = ReadingStatusFormatting.battery(sticky)
        } catch (_: RuntimeException) {
            // A restricted host may not permit a subscription. Keep unknown battery rather than invent it.
            listening = false
            battery = "—"
        }
        refresh(force = true)
    }

    private fun stop() {
        if (!listening) return
        listening = false
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    private fun refresh(force: Boolean = false) {
        val current = ReadingStatusSnapshot(ReadingStatusFormatting.time(appContext, clock()), battery)
        if (force || current != snapshot) {
            snapshot = current
            onChanged(current)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        stop()
        lifecycle.removeObserver(this)
    }
}
