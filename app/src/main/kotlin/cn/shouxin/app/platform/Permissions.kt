package cn.shouxin.app.platform

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.os.Process
import androidx.core.app.NotificationManagerCompat

object Permissions {
    fun usage(context: Context): Boolean = runCatching {
        context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
    fun notifications(context: Context, channel: String = "motivation"): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
}
