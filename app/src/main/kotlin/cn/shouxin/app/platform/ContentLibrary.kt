package cn.shouxin.app.platform

import cn.shouxin.app.data.UserSettings
import cn.shouxin.core.RuleType

data class MessageContent(val id: String, val title: String, val body: String)
object ContentLibrary {
    private val gentle = listOf(
        "休息可以，停下来也可以。给自己 10 分钟，做一件今天想完成的小事。",
        "今天不用一下子做到完美。先完成小目标的第一步。",
        "把注意力轻轻带回来。现在开始，也是一种进展。",
        "给自己一个小小的开始。选择一件事，专注 10 分钟。",
        "你可以重新选择接下来的时间。先喝口水，再开始一小步。",
        "休息已经给了你一点能量。把这一点能量留给想做的事。",
        "不用赶上任何人。现在为自己的目标留一点时间。",
        "从这里重新开始就好。小小的一步，也值得记录。",
        "先把手机放在一边，试着完成一个最简单的动作。",
        "留一点安静的时间给自己。十分钟后再决定下一步。"
    )
    private val actions = listOf("打开任务，只做最容易开始的那一步。", "把手机放下，给小目标 10 分钟。", "先写一句话，或者先读一页。开始可以很小。", "如果 25 分钟太长，就先做 5 分钟。", "这段休息先到这里，选好下一件事再行动。",
        "把待办拆成一个小动作，然后试着完成它。", "先清理桌面上的一件东西，再打开你的任务。", "关掉当前页面，给自己一个新的开始。", "试着写下接下来最想完成的一件事。", "从一页、一行或者一次练习开始。")
    fun catalog(): List<Pair<String, String>> = gentle.flatMapIndexed { g, first -> actions.mapIndexed { a, second -> "original-$g-$a" to "$first $second" } }
    fun select(s: UserSettings, type: RuleType?, minutes: Int?, recent: Set<String>): MessageContent {
        val values = s.customPhrases.mapIndexed { index, value -> "custom-$index" to value } +
            gentle.flatMapIndexed { g, first -> actions.mapIndexed { a, second -> "original-$g-$a" to (if (s.style == "ACTION") "$second $first" else "$first $second") } }
        val allowed = values.filter { it.first !in s.blocked }.ifEmpty { listOf("fallback" to "给自己一个小小的开始，选择一件事，专注 10 分钟。") }
        val available = allowed.filter { it.first !in recent }.ifEmpty { allowed }
        val preferred = available.filter { it.first in s.favorites }
        val item = (available + preferred + preferred).random()
        val prefix = when (type) {
            RuleType.CONTINUOUS_GROUP -> "你在娱乐应用里已经待了约 ${minutes ?: 0} 分钟。"
            RuleType.DAILY_BUDGET -> "今天的娱乐使用已达到你设置的预算。"
            else -> ""
        }
        val suffix = if (s.style == "GOAL") " 给「${s.goal}」留 10 分钟吧。" else ""
        return MessageContent(item.first, "给注意力一个小休息", (prefix + item.second + suffix).take(180))
    }
}
