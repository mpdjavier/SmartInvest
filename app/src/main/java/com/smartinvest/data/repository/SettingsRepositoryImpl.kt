package com.smartinvest.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.smartinvest.domain.model.AppSettings
import com.smartinvest.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "smartinvest_settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private object Keys {
        val initialized = booleanPreferencesKey("initialized_v1")
        val notifications = booleanPreferencesKey("notifications_enabled")
        val fci = intPreferencesKey("salary_fci_percent")
        val lecap = intPreferencesKey("salary_lecap_percent")
        val mep = intPreferencesKey("salary_mep_percent")
        val ao27d = longPreferencesKey("ao27d_threshold_usd_cents")
        val rebalanceTarget = intPreferencesKey("rebalance_target_peso_percent")
        val rebalanceTolerance = doublePreferencesKey("rebalance_tolerance_pp")
        val maturityDays = intPreferencesKey("maturity_alert_days")
        val rateDrop = doublePreferencesKey("rate_drop_threshold_pp")
        val fallbackMep = doublePreferencesKey("fallback_mep_rate")
        val monthlyDevaluation = doublePreferencesKey("monthly_devaluation_percent")
        val pesoReferenceTna = doublePreferencesKey("peso_reference_tna_percent")
        val lastSync = stringPreferencesKey("last_sync_at")
    }

    override val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map { prefs -> prefs.toSettings() }

    override suspend fun currentSettings(): AppSettings = settings.first()

    override suspend fun update(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.fci] = settings.salaryFciPercent
            prefs[Keys.lecap] = settings.salaryLecapPercent
            prefs[Keys.mep] = settings.salaryMepPercent
            prefs[Keys.ao27d] = settings.ao27dThresholdUsdCents
            prefs[Keys.rebalanceTarget] = settings.rebalanceTargetPesoPercent
            prefs[Keys.rebalanceTolerance] = settings.rebalanceTolerancePp
            prefs[Keys.maturityDays] = settings.maturityAlertDays
            prefs[Keys.rateDrop] = settings.rateDropThresholdPp
            prefs[Keys.fallbackMep] = settings.fallbackMepRate
            prefs[Keys.monthlyDevaluation] = settings.monthlyDevaluationPercent
            prefs[Keys.pesoReferenceTna] = settings.pesoReferenceTnaPercent
            prefs[Keys.notifications] = settings.notificationsEnabled
            prefs[Keys.initialized] = true
        }
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.notifications] = enabled }
    }

    override suspend fun isInitialized(): Boolean =
        context.settingsDataStore.data.first()[Keys.initialized] ?: false

    override suspend fun markInitialized() {
        context.settingsDataStore.edit { it[Keys.initialized] = true }
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        salaryFciPercent = this[Keys.fci] ?: 50,
        salaryLecapPercent = this[Keys.lecap] ?: 30,
        salaryMepPercent = this[Keys.mep] ?: 20,
        ao27dThresholdUsdCents = this[Keys.ao27d] ?: 2_000_000L,
        rebalanceTargetPesoPercent = this[Keys.rebalanceTarget] ?: 60,
        rebalanceTolerancePp = this[Keys.rebalanceTolerance] ?: 5.0,
        maturityAlertDays = this[Keys.maturityDays] ?: 30,
        rateDropThresholdPp = this[Keys.rateDrop] ?: 0.5,
        fallbackMepRate = this[Keys.fallbackMep] ?: 1_000.0,
        monthlyDevaluationPercent = this[Keys.monthlyDevaluation] ?: 2.0,
        pesoReferenceTnaPercent = this[Keys.pesoReferenceTna] ?: 0.0,
        notificationsEnabled = this[Keys.notifications] ?: true,
    )
}
