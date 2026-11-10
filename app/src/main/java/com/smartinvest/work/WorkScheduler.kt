package com.smartinvest.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.smartinvest.data.worker.AlertScanWorker
import com.smartinvest.data.worker.PortfolioSnapshotWorker
import com.smartinvest.data.worker.RateSyncWorker
import com.smartinvest.data.worker.WidgetUpdateWorker
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object WorkScheduler {

    const val RATE_SYNC_WORK = "smartinvest_rate_sync"
    const val SNAPSHOT_WORK = "smartinvest_portfolio_snapshot"
    const val ALERTS_WORK = "smartinvest_alerts"
    const val WIDGET_WORK = "smartinvest_widget_refresh_periodic"

    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)

        val rateSync = PeriodicWorkRequestBuilder<RateSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        manager.enqueueUniquePeriodicWork(RATE_SYNC_WORK, ExistingPeriodicWorkPolicy.UPDATE, rateSync)

        val snapshot = PeriodicWorkRequestBuilder<PortfolioSnapshotWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayUntil(LocalTime.of(23, 30)), TimeUnit.MILLISECONDS)
            .build()
        manager.enqueueUniquePeriodicWork(SNAPSHOT_WORK, ExistingPeriodicWorkPolicy.UPDATE, snapshot)

        val alerts = PeriodicWorkRequestBuilder<AlertScanWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayUntil(LocalTime.of(8, 0)), TimeUnit.MILLISECONDS)
            .build()
        manager.enqueueUniquePeriodicWork(ALERTS_WORK, ExistingPeriodicWorkPolicy.UPDATE, alerts)

        val widget = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(1, TimeUnit.HOURS).build()
        manager.enqueueUniquePeriodicWork(WIDGET_WORK, ExistingPeriodicWorkPolicy.UPDATE, widget)
    }

    private fun delayUntil(time: LocalTime): Long {
        val now = LocalDateTime.now()
        var target = now.toLocalDate().atTime(time)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target).toMillis().coerceAtLeast(0)
    }
}
