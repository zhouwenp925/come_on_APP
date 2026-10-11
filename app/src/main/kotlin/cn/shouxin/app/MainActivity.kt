package cn.shouxin.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import cn.shouxin.app.ui.ShouxinApp

class MainActivity : ComponentActivity() {
    private val requestedTab = mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); readIntent(intent); setContent { ShouxinApp(graph, requestedTab.intValue) } }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readIntent(intent) }
    private fun readIntent(intent: Intent) { if (intent.getBooleanExtra("openFocus", false)) { requestedTab.intValue++; intent.removeExtra("openFocus") } }
    override fun onResume() { super.onResume(); graph.launch { graph.scheduler.reconcile(); graph.refresh(); graph.updates.recoverDownload() } }
}
