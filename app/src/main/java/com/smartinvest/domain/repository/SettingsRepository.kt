package com.smartinvest.domain.repository

import com.smartinvest.domain.model.AppAlert
import com.smartinvest.domain.model.AppSettings
import com.smartinvest.domain.model.NewAlert
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun currentSettings(): AppSettings
    suspend fun update(settings: AppSettings)
    suspend fun setNotificationsEnabled(enabled: Boolean)
    suspend fun isInitialized(): Boolean
    suspend fun markInitialized()
}

interface AlertsRepository {
    fun observeAlerts(): Flow<List<AppAlert>>
    fun observeUnreadCount(): Flow<Int>
    suspend fun markRead(id: Long)
    suspend fun markAllRead()
    suspend fun insertIfNew(alert: NewAlert): Boolean
    suspend fun pruneOlderThan(days: Long)
    suspend fun scanAlerts(): List<AppAlert>
}

interface BootstrapRepository {
    suspend fun seedIfNeeded()
}
