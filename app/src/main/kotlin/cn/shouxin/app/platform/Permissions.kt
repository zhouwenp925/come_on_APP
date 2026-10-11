package cn.shouxin.app.platform

import android.app.AppOpsManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Process
import android.os.Build
import androidx.core.app.NotificationManagerCompat

object Permissions {
    fun exactAlarms(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun usage(context: Context): Boolean = runCatching {
        context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
    fun notifications(context: Context, channel: String = "motivation"): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
}
