package app.leafreader

import android.app.Application
import android.content.Intent
import android.os.BatteryManager
import android.os.LocaleList
import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import app.leafreader.reader.ReadingStatusFormatting
import app.leafreader.reader.ReadingStatusProvider
import app.leafreader.reader.ReadingStatusSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS")
class ReadingStatusProviderTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun batteryUsesActualLevelScaleAndUnknownDoesNotBecomeZero() {
        assertEquals("57%", ReadingStatusFormatting.battery(57, 100))
        assertEquals("50%", ReadingStatusFormatting.battery(5, 10))
        assertEquals("0%", ReadingStatusFormatting.battery(0, 100))
        assertEquals("100%", ReadingStatusFormatting.battery(100, 100))
        for ((level, scale) in listOf(-1 to 100, 101 to 100, 1 to 0, -1 to -1)) {
            assertEquals("—", ReadingStatusFormatting.battery(level, scale))
        }
        assertEquals("—", ReadingStatusFormatting.battery(57, 100, present = false))
    }

    @Test fun timeUsesSystem12Or24HourChoiceAndLocalTimezone() {
        val oldZone = TimeZone.getDefault()
        val oldLocale = Locale.getDefault()
        val oldPreference = Settings.System.getString(app.contentResolver, Settings.System.TIME_12_24)
        val oldConfiguration = android.content.res.Configuration(app.resources.configuration)
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            Locale.setDefault(Locale.US)
            val configuration = android.content.res.Configuration(app.resources.configuration)
            configuration.setLocales(LocaleList(Locale.US))
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(configuration, app.resources.displayMetrics)
            val at = Instant.parse("2026-11-20T14:05:00Z").toEpochMilli()
            Settings.System.putString(app.contentResolver, Settings.System.TIME_12_24, "24")
            assertEquals("22:05", ReadingStatusFormatting.time(app, at))
            Settings.System.putString(app.contentResolver, Settings.System.TIME_12_24, "12")
            val twelve = ReadingStatusFormatting.time(app, at)
            assertTrue(twelve, twelve.contains("10:05"))
            assertTrue(twelve, twelve.uppercase(Locale.US).contains("PM"))
        } finally {
            TimeZone.setDefault(oldZone); Locale.setDefault(oldLocale)
            Settings.System.putString(app.contentResolver, Settings.System.TIME_12_24, oldPreference)
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(oldConfiguration, app.resources.displayMetrics)
        }
    }

    @Test fun lifecycleSubscriptionsStopOnPauseAndCloseAndRefreshOnResume() {
        val owner = Owner()
        val events = mutableListOf<ReadingStatusSnapshot>()
        var now = Instant.parse("2026-11-20T14:05:00Z").toEpochMilli()
        val minuteIntent = Intent(Intent.ACTION_TIME_TICK)
        val beforeReceivers = shadowOf(app).getReceiversForIntent(minuteIntent).size
        val provider = ReadingStatusProvider(app, owner.lifecycle, clock = { now }, onChanged = events::add)
        assertFalse(provider.listening)
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertTrue(provider.listening)
        assertEquals(beforeReceivers + 1, shadowOf(app).getReceiversForIntent(minuteIntent).size)
        assertTrue(events.isNotEmpty())
        val battery = Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, 73).putExtra(BatteryManager.EXTRA_SCALE, 100)
        app.sendBroadcast(battery); shadowOf(Looper.getMainLooper()).idle()
        assertEquals("73%", provider.snapshot.batteryLabel)
        val previousTime = provider.snapshot.timeLabel
        now += 60_000
        app.sendBroadcast(minuteIntent); shadowOf(Looper.getMainLooper()).idle()
        assertNotEquals(previousTime, provider.snapshot.timeLabel)
        owner.registry.currentState = Lifecycle.State.STARTED
        assertFalse(provider.listening)
        assertEquals(beforeReceivers, shadowOf(app).getReceiversForIntent(minuteIntent).size)
        val pausedEvents = events.size
        app.sendBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, 41).putExtra(BatteryManager.EXTRA_SCALE, 100))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(pausedEvents, events.size)
        now += 60_000
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertTrue(provider.listening)
        assertTrue(events.size > pausedEvents)
        provider.close()
        assertFalse(provider.listening)
        assertEquals(beforeReceivers, shadowOf(app).getReceiversForIntent(minuteIntent).size)
        val closedEvents = events.size
        app.sendBroadcast(battery); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(closedEvents, events.size)
        owner.registry.currentState = Lifecycle.State.STARTED
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertFalse(provider.listening)
        provider.close()
    }
}
