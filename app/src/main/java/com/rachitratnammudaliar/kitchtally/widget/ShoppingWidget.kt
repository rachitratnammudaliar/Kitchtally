package com.rachitratnammudaliar.kitchtally.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.rachitratnammudaliar.kitchtally.MainActivity
import com.rachitratnammudaliar.kitchtally.R
import com.rachitratnammudaliar.kitchtally.data.AppDatabase
import com.rachitratnammudaliar.kitchtally.data.counting
import com.rachitratnammudaliar.kitchtally.domain.Pantry
import com.rachitratnammudaliar.kitchtally.domain.StockStatus
import com.rachitratnammudaliar.kitchtally.ui.PantryState
import com.rachitratnammudaliar.kitchtally.ui.onShoppingList
import com.rachitratnammudaliar.kitchtally.ui.stockLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Home screen widget: how many things are on To buy, and what's almost out. Tapping opens the list. */
class ShoppingWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ShoppingWidget.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }
}

object ShoppingWidget {
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, ShoppingWidgetProvider::class.java))
        if (ids.isEmpty()) return@withContext
        val dao = AppDatabase.get(context).pantry()
        val state = PantryState(dao.items(), dao.events(), dao.notes())
        val today = LocalDate.now()
        val toBuy = state.items.filter { onShoppingList(it, state, today) }
        val count = toBuy.size + state.notes.size
        // Almost out first (still some left), then what's run out.
        val lines = toBuy
            .sortedWith(compareBy({ state.stock(it.id) <= 0 }, { Pantry.status(state.entries(it.id), it.counting(), today) == StockStatus.OK }, { it.name.lowercase() }))
            .take(4)
            .map { "${it.name} · ${stockLabel(it, state.stock(it.id))}" } +
            state.notes.take(maxOf(0, 4 - toBuy.size)).map { it.text }

        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, "shopping")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val views = RemoteViews(context.packageName, R.layout.widget_shopping).apply {
            setTextViewText(R.id.widget_count, if (count == 0) "Nothing to buy" else "$count to buy")
            setTextViewText(R.id.widget_lines, if (lines.isEmpty()) "Your pantry is stocked" else lines.joinToString("\n"))
            setOnClickPendingIntent(R.id.widget_root, tap)
        }
        manager.updateAppWidget(ids, views)
    }
}
