package cn.shouxin.app

import android.app.Application
import android.content.Context

class ShouxinApplication : Application() {
    lateinit var graph: AppGraph; private set
    override fun onCreate() { super.onCreate(); graph = AppGraph(this); graph.launch { graph.initialize() } }
}
val Context.graph: AppGraph get() = (applicationContext as ShouxinApplication).graph
