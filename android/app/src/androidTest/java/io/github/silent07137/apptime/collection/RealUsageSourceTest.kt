// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.collection

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.silent07137.apptime.core.EventKind
import io.github.silent07137.apptime.core.EventRead
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealUsageSourceTest {
    @Test fun authorizedDeviceReturnsRealForegroundEvents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = AndroidUsageSource(context)
        // This read-only smoke check needs a test device with actual usage access enabled.
        assumeTrue("Grant AppTime usage access and open an app before this test", source.hasAccess())
        val end = System.currentTimeMillis()
        val result = source.read(end - 600_000, end)
        assertTrue("Authorized unlocked device should supply a usable query", result is EventRead.Available)
        val events = (result as EventRead.Available).events
        assertTrue("An app must have been opened in the last 10 minutes", events.any { it.kind == EventKind.RESUME && it.packageName.isNotBlank() })
        assertTrue(events.all { it.timeMs >= end - 600_000 && it.timeMs < end })
    }
    @Test fun earliestQueryReturnsActualSystemAggregates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(AndroidUsageSource(context).hasAccess())
        val result = AndroidUsageHistorySource(context).read(0, System.currentTimeMillis())
        assertTrue(result is io.github.silent07137.apptime.core.HistoryRead.Available)
        val buckets = (result as io.github.silent07137.apptime.core.HistoryRead.Available).buckets
        assertTrue("Use an app on the test device before querying history", buckets.any { it.usageMs > 0 })
        assertTrue(buckets.all { it.source == "android_usage_stats_best" && it.endMs > it.startMs })
    }
}
