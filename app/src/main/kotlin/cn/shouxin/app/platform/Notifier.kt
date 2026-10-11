package cn.shouxin.app.platform

import android.app.*
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import cn.shouxin.app.MainActivity
import cn.shouxin.app.R
import cn.shouxin.app.data.NotificationRow

@SuppressLint("MissingPermission") // Every user notification is gated by Permissions.notifications at submission.
class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    fun channels(sound: Boolean = false) {
        // Once created, channel sound remains under user control in Android settings.
        manager.createNotificationChannels(listOf(
            NotificationChannel("motivation", "鼓励提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "你设置的定时、间隔与使用提醒"; if (!sound) setSound(null, null); enableVibration(false)
            },
            NotificationChannel("guardian", "守护运行状态", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) },
            NotificationChannel("focus", "专注计时结束", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null); enableVibration(false) }
        ))
    }
    private fun action(kind: String, rowId: Long): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).setAction("cn.shouxin.$kind")
            .setData(Uri.parse("shouxin://action/$kind/$rowId")).putExtra("notificationId", rowId)
        return PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun submit(row: NotificationRow) {
        val focusEnd = row.type == "FOCUS_END"
        check(Permissions.notifications(context, if (focusEnd) "focus" else "motivation")) { "NOTIFICATIONS_DISABLED" }
        val open = Intent(context, MainActivity::class.java).putExtra("openFocus", !focusEnd).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val public = NotificationCompat.Builder(context, if (focusEnd) "focus" else "motivation")
            .setSmallIcon(R.drawable.ic_leaf).setContentTitle("收心").setContentText("给注意力一个小休息").build()
        val builder = NotificationCompat.Builder(context, if (focusEnd) "focus" else "motivation")
            .setSmallIcon(R.drawable.ic_leaf).setContentTitle(row.title).setContentText(row.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(row.body)).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public).setOnlyAlertOnce(true).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, row.id.toInt(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if (!focusEnd) {
            builder.addAction(0, "专注 10 分钟", PendingIntent.getActivity(context, row.id.toInt(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            builder.addAction(0, "稍后 10 分钟", action("SNOOZE_ACTION", row.id))
            builder.addAction(0, "今天暂停", action("PAUSE_TODAY", row.id))
        }
        manager.notify((row.id + 100).toInt(), builder.build())
    }
    fun guardian(until: Long): Notification {
        val stop = Intent(context, ReminderReceiver::class.java).setAction("cn.shouxin.STOP_GUARD")
        return NotificationCompat.Builder(context, "guardian").setSmallIcon(R.drawable.ic_leaf)
            .setContentTitle("收心守护已开启").setContentText("使用记录仅保存在手机，点击停止可随时结束")
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(0, "停止守护", PendingIntent.getBroadcast(context, 1, stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setWhen(until).build()
    }
    fun preview(content: MessageContent = MessageContent("preview", "收心 · 提醒预览", "给自己 10 分钟，完成今天的小目标。")) {
        check(Permissions.notifications(context))
        manager.notify(42, NotificationCompat.Builder(context, "motivation").setSmallIcon(R.drawable.ic_leaf)
            .setContentTitle("收心 · 提醒预览").setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).build())
    }
    fun cancel(rowId: Long) = manager.cancel((rowId + 100).toInt())
    fun cancelAll() = manager.cancelAll()
}
