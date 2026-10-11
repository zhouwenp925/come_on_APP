package cn.shouxin.app.platform

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.app.KeyguardManager
import androidx.core.app.ServiceCompat
import cn.shouxin.app.graph
import cn.shouxin.app.data.MetaRow
import kotlinx.coroutines.*

class GuardianService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val minutes = (intent?.getIntExtra("minutes", 120) ?: 120).coerceIn(1, 480)
        val deadline = SystemClock.elapsedRealtime() + minutes * 60_000L
        val until = System.currentTimeMillis() + minutes * 60_000L
        try {
            ServiceCompat.startForeground(this, 1, graph.notifier.guardian(until),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch (_: Exception) { graph.feedback.value = "系统未允许开启守护，请查看权限与运行限制。"; stopSelf(); return START_NOT_STICKY }
        running = true; graph.guardianUntil.value = until
        job?.cancel()
        job = serviceScope.launch {
            try {
                while (isActive && SystemClock.elapsedRealtime() < deadline) {
                    graph.dao.saveMeta(MetaRow("guardian_heartbeat", System.currentTimeMillis().toString()))
                    if (getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                        graph.collector.refresh(); graph.finishFocusIfDue(); graph.evaluate()
                    }
                    if (!graph.settings.read().monitoring || !Permissions.usage(this@GuardianService)) break
                    delay(10_000)
                }
            } finally { withContext(NonCancellable + Dispatchers.Main) { stopSelf(startId) } }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { running = false; graph.guardianUntil.value = 0; serviceScope.cancel(); super.onDestroy() }
    companion object { @Volatile var running = false; private set }
}
