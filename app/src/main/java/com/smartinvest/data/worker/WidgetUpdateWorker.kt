package com.smartinvest.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.smartinvest.widget.WidgetDataLoader
import com.smartinvest.widget.WidgetRenderer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class WidgetUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val loader: WidgetDataLoader,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        val data = loader.load()
        WidgetRenderer.update(applicationContext, data)
        Result.success()
    }.getOrElse { Result.retry() }
}
