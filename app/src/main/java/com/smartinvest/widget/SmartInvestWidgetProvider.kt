package com.smartinvest.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.smartinvest.data.widget.WidgetRefresherImpl
import com.smartinvest.data.worker.WidgetUpdateWorker

class SmartInvestWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        enqueueUpdate(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            enqueueUpdate(context)
        } else {
            super.onReceive(context, intent)
        }
    }

    private fun enqueueUpdate(context: Context) {
        val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WidgetRefresherImpl.UNIQUE_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        const val ACTION_REFRESH = "com.smartinvest.widget.ACTION_REFRESH"
    }
}
