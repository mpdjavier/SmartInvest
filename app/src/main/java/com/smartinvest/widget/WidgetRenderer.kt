package com.smartinvest.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.smartinvest.MainActivity
import com.smartinvest.R
import com.smartinvest.core.money.MoneyFormatter
import com.smartinvest.ui.util.MoneyVisibility
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

object WidgetRenderer {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("es-AR"))

    fun update(context: Context, data: WidgetData) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, SmartInvestWidgetProvider::class.java)
        val ids = manager.getAppWidgetIds(component)
        if (ids.isEmpty()) return

        val views = RemoteViews(context.packageName, R.layout.widget_smart_invest).apply {
            val hidden = data.hideAmounts
            setTextViewText(R.id.widget_total, money(data.totalArsCents, hidden, Currency.ARS))
            val mep = money((data.mepRate * 100).roundToLong(), hidden, Currency.ARS)
            val estimated = if (data.mepEstimated) " (est.)" else ""
            setTextViewText(
                R.id.widget_total_usd,
                "${money(data.totalUsdCents, hidden, Currency.USD)} · MEP $mep$estimated",
            )
            setTextViewText(R.id.widget_interest, "+ ${money(data.dailyInterestCents, hidden, Currency.ARS)}")
            setTextViewText(
                R.id.widget_installments,
                "${money(data.installmentsCents, hidden, Currency.ARS)} · ${data.installmentsCount} cuotas",
            )
            setTextViewText(R.id.widget_household, data.householdStatus)

            val time = Instant.ofEpochMilli(data.updatedAtMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
                .format(timeFormatter)
            setTextViewText(R.id.widget_updated, "Actualizado $time")

            setOnClickPendingIntent(R.id.widget_refresh, refreshIntent(context))
            setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        }

        manager.updateAppWidget(ids, views)
    }

    private enum class Currency { ARS, USD }

    private fun money(cents: Long, hidden: Boolean, currency: Currency): String = when {
        hidden -> if (currency == Currency.USD) "US$ ${MoneyVisibility.MASK}" else "$ ${MoneyVisibility.MASK}"
        currency == Currency.USD -> MoneyFormatter.usd(cents)
        else -> MoneyFormatter.ars(cents)
    }

    private fun refreshIntent(context: Context): PendingIntent {
        val intent = Intent(context, SmartInvestWidgetProvider::class.java).apply {
            action = SmartInvestWidgetProvider.ACTION_REFRESH
        }
        return PendingIntent.getBroadcast(
            context,
            101,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            102,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
