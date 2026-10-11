package cn.shouxin.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.shouxin.app.*
import cn.shouxin.app.data.*
import cn.shouxin.app.platform.GuardianService
import cn.shouxin.app.platform.ContentLibrary
import cn.shouxin.core.*
import java.time.LocalDate
import java.time.LocalTime

private fun formatMinute(m: Int) = "%02d:%02d".format(m / 60, m % 60)
private fun readMinute(text: String): Int? = if (text == "24:00") 1440 else runCatching { val t = LocalTime.parse(text); t.hour * 60 + t.minute }.getOrNull()

@Composable
fun WeekPanel(rows: List<DailyRow>, targets: Set<String>) {
    Panel {
        Text("近 7 日娱乐使用", fontWeight = FontWeight.Bold)
        val dates = (6L downTo 0L).map { LocalDate.now().minusDays(it).toString() }
        val values = dates.map { date -> if (rows.none { it.date == date }) null else rows.filter { it.date == date && it.packageName in targets }.sumOf { it.millis } }
        val max = maxOf(values.filterNotNull().maxOrNull() ?: 0L, 60_000L)
        Row(Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            dates.forEachIndexed { index, date ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(values[index]?.div(60_000)?.toString() ?: "—", fontSize = 10.sp)
                    Box(Modifier.width(22.dp).height((values[index]?.let { it.toFloat() / max * 70f } ?: 2f).coerceAtLeast(3f).dp).background(if (values[index] == null) Color.LightGray else Color(0xFF70A57D)))
                    Text(date.takeLast(5), fontSize = 10.sp)
                }
            }
        }
        Text("空缺表示没有有效记录，不能当作零。按当前目标组成员汇总。", fontSize = 11.sp, color = Color.Gray)
    }
}
@Composable
fun FocusPanel(graph: AppGraph, settings: UserSettings, rows: List<FocusRow>, tick: Long) {
    var task by remember(settings.goal) { mutableStateOf(settings.goal) }
    var minutes by remember { mutableIntStateOf(10) }
    var customMinutes by remember { mutableStateOf("10") }
    val running = rows.firstOrNull { it.state == "RUNNING" }
    Panel {
        Text("现在，做一点想做的事", fontWeight = FontWeight.Bold)
        if (running != null) {
            // tick causes recalculation from the monotonic clock, never from a decrementing counter.
            val remaining = remember(tick, running) { graph.focus.remaining(running) }
            Text("%02d:%02d".format(remaining / 60000, remaining / 1000 % 60), fontSize = 48.sp, fontWeight = FontWeight.Bold)
            Text(running.task)
            LinearProgressIndicator(progress = { (1f - remaining.toFloat() / running.durationMillis).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { graph.launch { graph.focus.cancel() } }) { Text("提前结束并保存记录") }
        } else {
            OutlinedTextField(value = task, onValueChange = { task = it.take(80) }, label = { Text("一个具体的小目标") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(10, 25, 45).forEach { value -> FilterChip(selected = customMinutes == value.toString(), onClick = { minutes = value; customMinutes = value.toString() }, label = { Text("$value 分钟") }) } }
            OutlinedTextField(customMinutes, { customMinutes = it }, label = { Text("自定义分钟：5～120") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { val value = customMinutes.toIntOrNull(); if (value != null && value in 5..120) graph.launch { graph.focus.start(task, value) } else graph.feedback.value = "专注时长请填写 5～120 分钟。" }, modifier = Modifier.fillMaxWidth()) { Text("开始这段专注") }
        }
        Text("计时记录代表你主动安排的时间，不自动评判你是否在学习。", color = Color.Gray, fontSize = 12.sp)
    }
}
@Composable
fun AppPicker(choices: List<AppChoice>, selected: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    var selection by remember { mutableStateOf(selected) }
    var search by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择娱乐应用组") }, text = {
        Column {
            OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("搜索 APP") }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(choices.filter { it.name.contains(search, true) || it.packageName.contains(search, true) }, key = { it.packageName }) { app ->
                    Row(Modifier.fillMaxWidth().clickable { selection = if (app.packageName in selection) selection - app.packageName else selection + app.packageName }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = app.packageName in selection, onCheckedChange = { checked -> selection = if (checked) selection + app.packageName else selection - app.packageName })
                        Column { Text(app.name); Text(app.packageName, fontSize = 10.sp, color = Color.Gray) }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { onSave(selection) }) { Text("保存 ${selection.size} 个") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
@Composable
fun RuleEditor(rule: Rule, onDismiss: () -> Unit, onSave: (Rule) -> Unit) {
    var type by remember { mutableStateOf(rule.type) }
    var time by remember { mutableStateOf(formatMinute(rule.hour * 60 + rule.minute)) }
    var interval by remember { mutableStateOf(rule.intervalMinutes.toString()) }
    var threshold by remember { mutableStateOf(rule.thresholdMinutes.toString()) }
    var start by remember { mutableStateOf(formatMinute(rule.windowStart)) }
    var end by remember { mutableStateOf(formatMinute(rule.windowEnd)) }
    var weekdays by remember { mutableStateOf(rule.weekdays) }
    var style by remember { mutableStateOf(rule.style) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("设置提醒") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RuleType.entries.forEach { value -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(type == value, onClick = { type = value }); Text(when (value) { RuleType.FIXED_TIME -> "固定时间"; RuleType.INTERVAL -> "间隔鼓励"; RuleType.CONTINUOUS_GROUP -> "连续使用（需要守护）"; RuleType.DAILY_BUDGET -> "每日预算" }) } }
            if (type == RuleType.FIXED_TIME) OutlinedTextField(time, { time = it }, label = { Text("本地时间，例如 09:00") }, singleLine = true)
            if (type == RuleType.INTERVAL) OutlinedTextField(interval, { interval = it }, label = { Text("间隔分钟：15～180") }, singleLine = true)
            if (type in setOf(RuleType.CONTINUOUS_GROUP, RuleType.DAILY_BUDGET)) OutlinedTextField(threshold, { threshold = it }, label = { Text("阈值分钟") }, singleLine = true)
            if (type != RuleType.FIXED_TIME) {
                OutlinedTextField(start, { start = it }, label = { Text("有效时段开始，例如 09:00") }, singleLine = true)
                OutlinedTextField(end, { end = it }, label = { Text("有效时段结束，例如 21:00") }, singleLine = true)
            }
            Text("每周生效日期")
            Row(Modifier.horizontalScroll(rememberScrollState())) { (1..7).forEach { day -> FilterChip(selected = day in weekdays, onClick = { weekdays = if (day in weekdays) weekdays - day else weekdays + day }, label = { Text(listOf("一", "二", "三", "四", "五", "六", "日")[day - 1]) }, modifier = Modifier.padding(end = 4.dp)) } }
            FilterChip(selected = style == "INHERIT", onClick = { style = "INHERIT" }, label = { Text("跟随我的内容偏好") })
            StyleChips(style) { style = it }
            Text("候选提醒仍受安静时段、每日额度和冷却限制。", fontSize = 12.sp)
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        val at = readMinute(time); val a = readMinute(start); val b = readMinute(end)
        val i = interval.toIntOrNull(); val t = threshold.toIntOrNull()
        val result = rule.copy(type = type, hour = (at ?: 540) / 60, minute = (at ?: 540) % 60, intervalMinutes = i ?: 60,
            thresholdMinutes = t ?: 15, windowStart = a ?: 540, windowEnd = b ?: 1260, weekdays = weekdays, style = style)
        val thresholdValid = when (type) { RuleType.CONTINUOUS_GROUP -> t != null && t in 1..240; RuleType.DAILY_BUDGET -> t != null && t in 5..480; else -> true }
        if (at == null || a == null || b == null || i == null || !thresholdValid || !result.validate()) error = "请检查时间、日期和数值范围；有效时段结束需晚于开始。"
        else onSave(result)
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
fun ContentPicker(graph: AppGraph, settings: UserSettings, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    val catalog = remember { ContentLibrary.catalog() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("励志与鼓励文案 · ${catalog.size} 条") }, text = {
        Column {
            OutlinedTextField(search, { search = it }, label = { Text("搜索鼓励") }, singleLine = true)
            Text("网络摘句标有出处。收藏会优先出现，屏蔽后不发送。", fontSize = 12.sp)
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(catalog.filter { it.body.contains(search) || it.source.contains(search) }, key = { it.id }) { entry ->
                    val id = entry.id
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(entry.body, fontSize = 13.sp)
                        Text(entry.source, fontSize = 10.sp, color = Color.Gray)
                        entry.sourceUrl?.let { url -> TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                .onFailure { graph.feedback.value = "暂时无法打开出处，请检查是否安装浏览器。" }
                        }) { Text("查看出处", fontSize = 11.sp) } }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(id in settings.favorites, onCheckedChange = { checked -> graph.launch { graph.settings.change { it.copy(favorites = if (checked) it.favorites + id else it.favorites - id) } } }); Text("收藏", fontSize = 12.sp)
                            Checkbox(id in settings.blocked, onCheckedChange = { checked -> graph.launch { graph.settings.change { it.copy(blocked = if (checked) it.blocked + id else it.blocked - id) } } }); Text("屏蔽", fontSize = 12.sp)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}
@Composable
fun StyleChips(style: String, changed: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("INSPIRING" to "励志金句", "GENTLE" to "温和", "ACTION" to "行动", "GOAL" to "目标").forEach { (value, title) -> FilterChip(selected = style == value, onClick = { changed(value) }, label = { Text(title) }) } }
}
@Composable
fun PreferencesPanel(graph: AppGraph, settings: UserSettings) {
    var goal by remember(settings.goal) { mutableStateOf(settings.goal) }
    var style by remember(settings.style) { mutableStateOf(settings.style) }
    var start by remember(settings.quietStart) { mutableStateOf(formatMinute(settings.quietStart)) }
    var end by remember(settings.quietEnd) { mutableStateOf(formatMinute(settings.quietEnd)) }
    var limit by remember(settings.dailyLimit) { mutableStateOf(settings.dailyLimit.toString()) }
    var cooldown by remember(settings.cooldownMinutes) { mutableStateOf(settings.cooldownMinutes.toString()) }
    var custom by remember(settings.customPhrases) { mutableStateOf(settings.customPhrases.joinToString("\n")) }
    var error by remember { mutableStateOf("") }
    Panel {
        Text("我的节奏", fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("使用监测")
            Switch(settings.monitoring, onCheckedChange = { enabled -> graph.launch {
                graph.settings.change { it.copy(monitoring = enabled) }
                if (!enabled) graph.app.stopService(Intent(graph.app, GuardianService::class.java))
                graph.collector.refresh()
            } })
        }
        OutlinedTextField(goal, { goal = it.take(80) }, label = { Text("我的小目标") }, modifier = Modifier.fillMaxWidth())
        StyleChips(style) { style = it }
        OutlinedTextField(start, { start = it }, label = { Text("安静时段开始") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(end, { end = it }, label = { Text("安静时段结束") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(limit, { limit = it }, label = { Text("每天 / 24 小时鼓励上限：1～20") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(cooldown, { cooldown = it }, label = { Text("普通提醒冷却：5～120 分钟") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(custom, { custom = it.take(5000) }, label = { Text("自己的鼓励，每行一句，可留空") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            val a = readMinute(start); val b = readMinute(end); val count = limit.toIntOrNull(); val gap = cooldown.toIntOrNull()
            if (a == null || b == null || a !in 0..1439 || b !in 0..1439 || a == b || count == null || count !in 1..20 || gap == null || gap !in 5..120) error = "请检查安静时段、上限和冷却时间的范围。"
            else { error = ""; graph.launch { graph.settings.change { it.copy(goal = goal, style = style, quietStart = a, quietEnd = b, dailyLimit = count, cooldownMinutes = gap,
                customPhrases = custom.lines().map { line -> line.trim().take(120) }.filter { line -> line.isNotBlank() }.take(100)) }; graph.feedback.value = "偏好已保存。" } }
        }) { Text("保存偏好") }
    }
}
