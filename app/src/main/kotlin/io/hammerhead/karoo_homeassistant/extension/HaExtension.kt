package io.hammerhead.karoo_homeassistant.extension

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import io.hammerhead.karoo_homeassistant.R

class HaExtension : KarooExtension("home-assistant", "1.0.0") {

    override val types: List<DataTypeImpl> = listOf(
        HaButtonDataType(this)
    )

    inner class HaButtonDataType(private val context: Context) : DataTypeImpl("home-assistant", "ha-button") {
        override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
            val intent = Intent(context, HaActionReceiver::class.java).apply {
                action = "io.hammerhead.karoo_homeassistant.HA_ACTION"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val views = RemoteViews(context.packageName, R.layout.ha_button_view)
            views.setOnClickPendingIntent(R.id.ha_button_root, pendingIntent)

            emitter.updateView(views)
        }
    }
}
