package com.smartinvest.data.widget

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.smartinvest.data.worker.WidgetUpdateWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface WidgetRefresher {
    fun refresh()
}

@Singleton
class WidgetRefresherImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : WidgetRefresher {

    override fun refresh() {
        val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        const val UNIQUE_WORK = "smartinvest_widget_refresh_once"
    }
}
