package com.smartinvest.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.smartinvest.domain.repository.AlertsRepository
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.domain.repository.SettingsRepository
import com.smartinvest.notification.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class RateSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val investments: InvestmentRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        investments.refreshDollarRates()
        investments.syncRates()
        investments.syncMarketQuotes()
        Result.success()
    }.getOrElse { Result.retry() }
}

@HiltWorker
class PortfolioSnapshotWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val investments: InvestmentRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        investments.refreshDollarRates()
        investments.syncMarketQuotes()
        investments.refreshSnapshot()
        Result.success()
    }.getOrElse { Result.retry() }
}

@HiltWorker
class AlertScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val alerts: AlertsRepository,
    private val settings: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        val created = alerts.scanAlerts()
        val current = settings.currentSettings()
        if (current.notificationsEnabled) {
            NotificationHelper.notifyAlerts(applicationContext, created)
        }
        Result.success()
    }.getOrElse { Result.retry() }
}
