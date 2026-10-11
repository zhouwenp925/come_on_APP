package cn.shouxin.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.shouxin.app.*
import cn.shouxin.app.data.*
import cn.shouxin.app.platform.*
import cn.shouxin.core.*
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

private val Green = Color(0xFF246B4C)
private val Pale = Color(0xFFF4F7F2)
private val palette = lightColorScheme(primary = Green, secondary = Color(0xFF68814B), background = Pale, surface = Color.White, onSurface = Color(0xFF1D2B24))
fun duration(value: Long): String { val m = value.coerceAtLeast(0) / 60000; return if (m >= 60) "${m / 60} 小时 ${m % 60} 分钟" else "$m 分钟" }
private fun clock(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
private fun minuteText(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
private fun parseMinute(text: String): Int? = runCatching { val t = LocalTime.parse(text); t.hour * 60 + t.minute }.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShouxinApp(graph: AppGraph, requestedTab: Int) {
    val context = LocalContext.current
    val settings by graph.settings.flow.collectAsStateWithLifecycle(UserSettings())
    val rulesFlow = remember(graph) { graph.dao.rulesFlow() }
    val dailyFlow = remember(graph) { graph.dao.dailyFlow() }
    val focusFlow = remember(graph) { graph.dao.focusFlow() }
    val notificationsFlow = remember(graph) { graph.dao.notificationsFlow() }
    val rules by rulesFlow.collectAsStateWithLifecycle(emptyList())
    val usage by graph.collector.dashboard.collectAsStateWithLifecycle()
    val choices by graph.apps.collectAsStateWithLifecycle()
    val daily by dailyFlow.collectAsStateWithLifecycle(emptyList())
    val focusRows by focusFlow.collectAsStateWithLifecycle(emptyList())
    val logs by notificationsFlow.collectAsStateWithLifecycle(emptyList())
    val guardUntil by graph.guardianUntil.collectAsStateWithLifecycle()
    val update by graph.updates.state.collectAsStateWithLifecycle()
    val feedback by graph.feedback.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<Rule?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }
    var contentPicker by remember { mutableStateOf(false) }
    var guardMinutes by rememberSaveable { mutableIntStateOf(120) }
    val snackbar = remember { SnackbarHostState() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { graph.launch { graph.refresh() } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) graph.launch { val text = graph.exportData(); context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }; graph.feedback.value = "数据已导出，请妥善保管文件。" }
    }
    LaunchedEffect(requestedTab) { if (requestedTab > 0) tab = 2 }
    LaunchedEffect(feedback) { feedback?.let { snackbar.showSnackbar(it); graph.feedback.value = null } }
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick = System.currentTimeMillis(); if (focusRows.any { it.state == "RUNNING" && graph.focus.remaining(it) == 0L }) graph.launch { graph.finishFocusIfDue() } } }
    MaterialTheme(colorScheme = palette) {
        Scaffold(containerColor = Pale, snackbarHost = { SnackbarHost(snackbar) },
            topBar = { TopAppBar(title = { Column { Text("收心", fontWeight = FontWeight.Bold); Text("把时间留给你想做的事", fontSize = 12.sp, color = Green) } }, actions = { TextButton(onClick = { graph.launch { graph.refresh() } }) { Text("刷新") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Pale)) },
            bottomBar = { NavigationBar(containerColor = Color.White) {
                listOf("今天", "规则", "专注", "我的").forEachIndexed { index, label -> NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(listOf("◉", "≡", "◷", "○")[index], fontSize = 22.sp) }, label = { Text(label) }) }
            } }
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (tab) {
                    0 -> {
                        item { Panel {
                            Text("今天的注意力", color = Green, fontWeight = FontWeight.SemiBold)
                            Text(if (usage.available) duration(usage.phoneMillis) else "尚无有效数据", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            Text("手机交互时长 · 前台事件估算", style = MaterialTheme.typography.bodySmall)
                            Text(if (usage.updatedAt > 0) "${usage.quality} · 更新于 ${clock(usage.updatedAt)}" else usage.quality, color = Color.Gray, fontSize = 12.sp)
                            HorizontalDivider()
                            Text("娱乐应用组：${if (usage.available) duration(usage.groupMillis) else "—"}")
                            val budget = rules.firstOrNull { it.type == "DAILY_BUDGET" }?.thresholdMinutes ?: 60
                            LinearProgressIndicator(progress = { (usage.groupMillis / (budget * 60_000f)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text("使用预算 $budget 分钟 · 已选 ${settings.targets.size} 个 APP", fontSize = 12.sp)
                            OutlinedButton(onClick = { picker = true }) { Text("选择需要留意的 APP") }
                        } }
                        if (!Permissions.usage(context) || !Permissions.notifications(context)) item { Panel {
                            Text("完成两项设置", fontWeight = FontWeight.Bold)
                            Text("使用访问用于统计时长；通知用于你设置的鼓励。使用内容不会上传。", fontSize = 13.sp)
                            if (!Permissions.usage(context)) Button(onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("开启使用情况访问") }
                            if (!Permissions.notifications(context)) OutlinedButton(onClick = {
                                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                            }) { Text("允许鼓励通知") }
                        } }
                        item { Panel {
                            Text(if (guardUntil > tick && GuardianService.running) "守护进行中 · 至 ${clock(guardUntil)}" else "实时守护未开启", fontWeight = FontWeight.Bold)
                            Text("仅在主动开启的时段监测连续使用。锁屏减少采集，到时自动结束。", fontSize = 13.sp)
                            if (guardUntil > tick && GuardianService.running) OutlinedButton(onClick = { context.stopService(Intent(context, GuardianService::class.java)) }) { Text("停止守护") }
                            else {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(60, 120, 240).forEach { value -> FilterChip(selected = guardMinutes == value, onClick = { guardMinutes = value }, label = { Text("${value / 60} 小时") }) } }
                            Button(onClick = {
                                when {
                                    !settings.monitoring -> graph.feedback.value = "先在「我的」开启使用监测。"
                                    settings.targets.isEmpty() -> picker = true
                                    !Permissions.usage(context) -> graph.feedback.value = "请先开启使用情况访问。"
                                    !Permissions.notifications(context) -> graph.feedback.value = "请先允许通知，才能收到守护提醒。"
                                    else -> runCatching { ContextCompat.startForegroundService(context, Intent(context, GuardianService::class.java).putExtra("minutes", guardMinutes)) }.onFailure { graph.feedback.value = "系统暂未允许启动守护。" }
                                }
                            }) { Text("开启 ${guardMinutes / 60} 小时守护") }
                            }
                            Text("后台和省电限制可能使提醒延迟。", color = Color.Gray, fontSize = 12.sp)
                        } }
                        item { Panel { Text("给小目标 10 分钟", fontWeight = FontWeight.Bold); Text(settings.goal); Button(onClick = { tab = 2 }) { Text("开始一段专注") } } }
                        item { Panel {
                            Text("今天 APP 使用", fontWeight = FontWeight.Bold)
                            if (!usage.available) Text("获得有效数据后展示排名。")
                            else usage.appMillis.entries.sortedByDescending { it.value }.take(8).forEach { (pkg, millis) ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(if (pkg == context.packageName) "收心" else choices.firstOrNull { it.packageName == pkg }?.name ?: pkg, modifier = Modifier.weight(1f), maxLines = 1); Text(duration(millis), fontSize = 12.sp) }
                            }
                        } }
                        item { WeekPanel(daily, settings.targets) }
                    }
                    1 -> {
                        item { Panel {
                            Text("按你的节奏，收到一句鼓励", fontWeight = FontWeight.Bold)
                            Text("可以每天在指定时刻提醒，也可以在选定时段内每隔 15～180 分钟提醒。定时与间隔鼓励无需开启使用监测或守护。", fontSize = 13.sp)
                            OutlinedButton(onClick = { editor = (rules.firstOrNull { it.type == "FIXED_TIME" }?.model()
                                ?: Rule(UUID.randomUUID().toString(), type = RuleType.FIXED_TIME)).copy(enabled = true, style = "INSPIRING") }, modifier = Modifier.fillMaxWidth()) { Text("设置定时鸡汤") }
                            OutlinedButton(onClick = { editor = (rules.firstOrNull { it.type == "INTERVAL" }?.model()
                                ?: Rule(UUID.randomUUID().toString(), type = RuleType.INTERVAL)).copy(enabled = true, style = "INSPIRING") }, modifier = Modifier.fillMaxWidth()) { Text("设置间隔鸡汤") }
                            TextButton(onClick = { contentPicker = true }) { Text("看看励志文案 · 收藏喜欢的句子") }
                        } }
                        item { Panel {
                            Text("让提醒更准时", fontWeight = FontWeight.Bold)
                            val exact = remember(tick / 5000) { Permissions.exactAlarms(context) }
                            Text(if (exact) "闹钟与提醒：已允许，使用精确定时。" else "闹钟与提醒：未允许，系统可能延迟安排。", fontSize = 13.sp)
                            Text("允许后会重新安排已开启的规则。省电限制、强行停止、通知关闭仍会影响送达。", color = Color.Gray, fontSize = 12.sp)
                            if (!exact && Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = {
                                runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
                                    .onFailure { graph.feedback.value = "请在系统设置 → 特殊应用权限 → 闹钟与提醒中允许收心。" }
                            }) { Text("允许准时提醒") }
                            if (!Permissions.notifications(context)) OutlinedButton(onClick = {
                                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                            }) { Text("允许鼓励通知") }
                            TextButton(onClick = {
                                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                                    .onFailure { graph.feedback.value = "请从系统设置打开收心的应用信息，允许后台运行与通知。" }
                            }) { Text("查看系统后台与通知设置") }
                        } }
                        item { Panel { Text("提醒要有分寸", fontWeight = FontWeight.Bold); Text("每天及滚动 24 小时最多 ${settings.dailyLimit} 条鼓励，冷却 ${settings.cooldownMinutes} 分钟。安静时段 ${minuteText(settings.quietStart)}—${minuteText(settings.quietEnd)}。", fontSize = 13.sp); Text("达到上限、冷却中、专注中或今天暂停时会跳过提醒。可在「我的」调整上限与冷却；迟到超过 5 分钟不补发。", color = Color.Gray, fontSize = 12.sp) } }
                        items(rules, key = { it.id }) { row -> Panel {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(ruleTitle(row.type), fontWeight = FontWeight.Bold); Text(ruleDescription(row.model()), fontSize = 12.sp) }
                                Switch(checked = row.enabled, onCheckedChange = { enabled -> graph.launch { graph.saveRule(row.model().copy(enabled = enabled)) } })
                            }
                            if (row.enabled && row.type in setOf("FIXED_TIME", "INTERVAL")) {
                                val next = remember(row, tick / 60_000) { Schedules.next(row.model(), tick, ZoneId.systemDefault()) }
                                next?.let { Text("下次计划：${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))} · 仍受上限与安静时段约束", color = Color.Gray, fontSize = 11.sp) }
                            }
                            Row { TextButton(onClick = { editor = row.model() }) { Text("编辑") }; TextButton(onClick = { graph.launch { graph.deleteRule(row.id) } }) { Text("删除") } }
                        } }
                        item { Button(onClick = { editor = Rule(UUID.randomUUID().toString(), type = RuleType.FIXED_TIME) }, modifier = Modifier.fillMaxWidth()) { Text("新增提醒规则") } }
                        item { OutlinedButton(onClick = { runCatching { graph.notifier.preview(ContentLibrary.select(settings, null, null, emptySet())) }.onFailure { graph.feedback.value = "请先在系统设置中允许鼓励通知。" } }) { Text("发送一条提醒预览") } }
                        item { Panel { Text("最近提醒", fontWeight = FontWeight.Bold); if (logs.isEmpty()) Text("还没有提醒记录。预览不计入每日额度。", fontSize = 12.sp); logs.take(6).forEach { Text("${clock(it.createdAt)} · ${if (it.state == "SUBMITTED") "已交给系统" else "未发送"} · ${it.title}", fontSize = 12.sp) } } }
                    }
                    2 -> {
                        item { FocusPanel(graph, settings, focusRows, tick) }
                        item { Panel { Text("最近专注计时", fontWeight = FontWeight.Bold); if (focusRows.isEmpty()) Text("从 10 分钟开始，不用一次完成所有事情。"); focusRows.take(8).forEach { Text("${it.task} · ${focusLabel(it.state)} · ${duration(if (it.state == "COMPLETED") it.durationMillis else it.actualMillis)}", fontSize = 13.sp) } } }
                    }
                    3 -> {
                        item { PreferencesPanel(graph, settings) }
                        item { OutlinedButton(onClick = { contentPicker = true }, modifier = Modifier.fillMaxWidth()) { Text("管理 ${ContentLibrary.catalog().size} 条鼓励 · 摘句与原创") } }
                        item { Panel {
                            Text("权限与提醒", fontWeight = FontWeight.Bold)
                            Text("使用访问：${if (Permissions.usage(context)) "已开启" else "未开启"}")
                            Text("鼓励通知：${if (Permissions.notifications(context)) "已允许" else "未允许"}")
                            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("设置通知声音和渠道") }
                            TextButton(onClick = { graph.launch { graph.pauseToday() } }) { Text("今天暂停鼓励") }
                            if (settings.pausedDate == LocalDate.now().toString()) TextButton(onClick = { graph.launch { graph.settings.change { it.copy(pausedDate = "") } } }) { Text("恢复今天的鼓励") }
                        } }
                        item { Panel {
                            Text("版本与更新", fontWeight = FontWeight.Bold); Text("当前版本 ${BuildConfig.VERSION_NAME}")
                            Text(update.message, fontSize = 13.sp)
                            if (update.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            update.release?.let { release -> Text(release.notes.ifBlank { "新版改进，请查看 GitHub Release。" }, fontSize = 12.sp); Button(enabled = !update.busy, onClick = { graph.launch { graph.updates.download(release) } }) { Text("下载 ${release.version}") } }
                            if (update.readyFile != null) Button(onClick = { graph.updates.install()?.let { context.startActivity(it) } }) { Text("安装更新") }
                            OutlinedButton(enabled = !update.busy, onClick = { graph.launch { graph.updates.check(settings.repository) } }) { Text("检查更新") }
                            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases"))) }) { Text("查看版本发布页") }
                            Text("更新由你确认；正式版需保持同一包名、签名及递增版本号。", color = Color.Gray, fontSize = 12.sp)
                        } }
                        item { Panel {
                            Text("数据保存在这台手机", fontWeight = FontWeight.Bold)
                            Text("无需账号。使用明细不会上传；文案离线可用，检查更新或查看出处时才访问网络。", fontSize = 13.sp)
                            Text("本地原始事件保留 7 天，聚合与专注记录保留 90 天。", fontSize = 12.sp)
                            TextButton(onClick = { export.launch("shouxin-${LocalDate.now()}.json") }) { Text("导出个人数据") }
                            TextButton(onClick = { clearConfirm = true }) { Text("清空使用与专注记录") }
                        } }
                    }
                }
            }
            if (picker) AppPicker(choices, settings.targets, onDismiss = { picker = false }, onSave = { selected -> graph.launch { graph.saveTargets(selected) }; picker = false })
            if (contentPicker) ContentPicker(graph, settings) { contentPicker = false }
            editor?.let { rule -> RuleEditor(rule, onDismiss = { editor = null }, onSave = { graph.launch { graph.saveRule(it) }; editor = null }) }
            if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text("清空本机记录？") }, text = { Text("会删除使用明细、专注和提醒记录，并停止守护。提醒规则和偏好保留，清理前历史不会自动重新导入。") }, confirmButton = { TextButton(onClick = { graph.launch { graph.clearRecords() }; clearConfirm = false }) { Text("确认清空") } }, dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("取消") } })
        }
    }
}

@Composable
fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}
private fun ruleTitle(type: String) = when (type) { "FIXED_TIME" -> "固定时间鼓励"; "INTERVAL" -> "间隔鼓励"; "CONTINUOUS_GROUP" -> "连续使用提醒"; else -> "每日使用预算" }
private fun ruleDescription(r: Rule) = when (r.type) { RuleType.FIXED_TIME -> "${minuteText(r.hour * 60 + r.minute)} · 每周 ${r.weekdays.sorted().joinToString("、")}"; RuleType.INTERVAL -> "每 ${r.intervalMinutes} 分钟 · ${minuteText(r.windowStart)}—${minuteText(r.windowEnd)}"; RuleType.CONTINUOUS_GROUP -> "娱乐应用组连续 ${r.thresholdMinutes} 分钟 · 需要守护开启"; RuleType.DAILY_BUDGET -> "当日累计 ${r.thresholdMinutes} 分钟 · 当天最多提醒一次" }
private fun focusLabel(state: String) = when (state) { "COMPLETED" -> "已完成"; "CANCELLED" -> "提前结束"; "INTERRUPTED" -> "计时中断"; else -> "进行中" }
