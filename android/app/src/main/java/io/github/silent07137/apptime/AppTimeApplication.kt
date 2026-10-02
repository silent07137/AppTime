// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime

import android.app.Application
import android.content.pm.PackageManager
import android.os.Process
import androidx.room.Room
import androidx.work.*
import io.github.silent07137.apptime.collection.AndroidUsageSource
import io.github.silent07137.apptime.collection.AndroidUsageHistorySource
import io.github.silent07137.apptime.collection.AndroidAppCatalog
import io.github.silent07137.apptime.data.AppDatabase
import io.github.silent07137.apptime.data.UsageRepository
import java.util.concurrent.TimeUnit

class AppTimeApplication : Application() {
    val catalog by lazy { AndroidAppCatalog(this) }
    val database by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "apptime.db").addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5).build() }
    val repository by lazy {
        UsageRepository(database, AndroidUsageSource(this), { pkg ->
            try {
                @Suppress("DEPRECATION")
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: PackageManager.NameNotFoundException) { pkg }
        }, profile = Process.myUserHandle().toString(), historySource = AndroidUsageHistorySource(this), inspectApp = catalog::inspect)
    }
    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("usage-collection", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CollectWorker>(6, TimeUnit.HOURS).build())
    }
}

class CollectWorker(context: android.content.Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { (applicationContext as AppTimeApplication).repository.collect() }
        // Permission/empty data are visible states; retrying repeatedly cannot repair authorization.
        Result.success()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        try { (applicationContext as AppTimeApplication).repository.recordFailure() } catch (_: Exception) { }
        Result.retry()
    }
}
