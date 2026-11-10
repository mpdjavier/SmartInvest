package com.smartinvest.di

import com.smartinvest.data.network.ComparatasasScraper
import com.smartinvest.data.network.RateScraper
import com.smartinvest.data.repository.AlertsRepositoryImpl
import com.smartinvest.data.repository.BootstrapRepositoryImpl
import com.smartinvest.data.repository.HouseholdRepositoryImpl
import com.smartinvest.data.repository.ImportRepositoryImpl
import com.smartinvest.data.repository.InvestmentRepositoryImpl
import com.smartinvest.data.repository.SettingsRepositoryImpl
import com.smartinvest.data.widget.WidgetRefresher
import com.smartinvest.data.widget.WidgetRefresherImpl
import com.smartinvest.domain.repository.AlertsRepository
import com.smartinvest.domain.repository.BootstrapRepository
import com.smartinvest.domain.repository.HouseholdRepository
import com.smartinvest.domain.repository.ImportRepository
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindHouseholdRepository(impl: HouseholdRepositoryImpl): HouseholdRepository

    @Binds
    @Singleton
    abstract fun bindInvestmentRepository(impl: InvestmentRepositoryImpl): InvestmentRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindAlertsRepository(impl: AlertsRepositoryImpl): AlertsRepository

    @Binds
    @Singleton
    abstract fun bindBootstrapRepository(impl: BootstrapRepositoryImpl): BootstrapRepository

    @Binds
    @Singleton
    abstract fun bindImportRepository(impl: ImportRepositoryImpl): ImportRepository

    @Binds
    @Singleton
    abstract fun bindWidgetRefresher(impl: WidgetRefresherImpl): WidgetRefresher

    @Binds
    @Singleton
    abstract fun bindRateScraper(impl: ComparatasasScraper): RateScraper
}
