package me.balaj.inverterlink

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date

class LiveStatus(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var last: Reading? = null

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                "monitoring",
                "Inverter monitoring",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    fun update(name: String, reading: Reading?, paused: Boolean) {
        if (reading != null) last = reading
        val r = reading ?: last ?: return
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        )
            return
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val metrics =
            "Solar ${(r.values["PV charging power"]?:0.0).formatNumber()} W · Load ${(r.values["Load power"]?:0.0).formatNumber()} W · Battery ${(r.values["Reported battery"]?:0.0).formatNumber()}%"
        val builder =
            NotificationCompat.Builder(context, "monitoring")
                .setSmallIcon(R.drawable.ic_inverter)
                .setContentTitle("$name · ${if(paused)"Paused" else r.mode}")
                .setContentText(metrics)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(
                            "$metrics\nUpdated ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(r.time))}"
                        )
                )
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(!paused)
                .setWhen(r.time)
                .setShowWhen(true)
        // Live Update promotion is a system decision; retain a regular notification on unsupported
        // devices.
        if (Build.VERSION.SDK_INT >= 36 && !paused) builder.setRequestPromotedOngoing(true)
        manager.notify(8899, builder.build())
    }

    fun end() {
        manager.cancel(8899)
        last = null
    }
}
