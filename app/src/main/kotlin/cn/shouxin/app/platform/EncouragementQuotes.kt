package cn.shouxin.app.platform

/** Stable IDs are persisted in favorites, blocked items and notification history. */
data class EncouragementEntry(
    val id: String,
    val body: String,
    val source: String = "收心原创",
    val sourceUrl: String? = null
)

object EncouragementQuotes {
    // Short excerpts checked on 2026-10-11. One excerpt per article; do not imply the
    // publishing account is the original author when its article credits other writers.
    val entries = listOf(
        EncouragementEntry("example-shine", "努力让自己发光，对的人才会迎光而来。", "你提供的句子 · 网络流传，原作者未核实"),
        EncouragementEntry("web-act-now", "把行动交给现在，把结果交给时间。", "人民日报夜读《追光的人，终会光芒万丈》", "https://www.peopleapp.com/column/30047509450-500005955638"),
        EncouragementEntry("web-own-sun", "努力做自己的太阳，温暖而有力量。", "人民日报夜读《越努力，越幸运》（来源：夜听）", "https://www.peopleapp.com/column/30039296013-500004504859"),
        EncouragementEntry("web-persistence", "你日复一日的努力，终会迎来厚积薄发的一天。", "人民日报夜读《有一种力量，叫坚持》", "https://www.peopleapp.com/column/30047710481-500005994529"),
        EncouragementEntry("web-own-life", "你想要的人生，只有自己能给。", "人民日报夜读《你想要的那种人生，只有自己能给》（文中引述）", "https://www.peopleapp.com/column/30038186455-500003208841"),
        EncouragementEntry("web-three-keys", "向外看，向内求，向前走。", "人民日报夜读《自我成长的三把钥匙》（来源：洞见）", "https://www.peopleapp.com/column/30038811951-500003811558"),
        EncouragementEntry("web-growth", "想要获得成长，就必须持续地努力和付出。", "人民日报夜读《保持成长的六个习惯，送给渴望改变的你》", "https://www.peopleapp.com/column/30038333673-500003352890"),
        EncouragementEntry("web-hope", "心怀期待，步履不停。", "人民日报夜读《7月1天1件“高能量”小事》", "https://www.peopleapp.com/column/30049532152-500006351705"),
        EncouragementEntry("web-small-wins", "认可自己的点滴进步。", "人民日报夜读《正在变强大的6种迹象》", "https://www.peopleapp.com/column/30052194948-500007505212"),
        EncouragementEntry("web-best-dream", "为最棒的梦想，尽最大的努力。", "人民日报夜读《在最好的年纪，全力以赴》", "https://www.peopleapp.com/column/30048371425-500006116539"),
        EncouragementEntry("web-restart", "愿意给自己一次重新开始的机会，也是一种勇敢。", "人民日报夜读《致每一个努力发光的你》", "https://www.peopleapp.com/column/30050613427-500007168301"),
        EncouragementEntry("web-execution", "执行力强，才是最顶级的自律。", "人民日报夜读《执行力强，才是最顶级的自律》", "https://www.peopleapp.com/column/30035415462-500000467599"),
        EncouragementEntry("web-responsibility", "对自己负责，才是真正的自律。", "人民日报夜读《真正的自律，是为自己的人生负责》", "https://www.peopleapp.com/column/30044584964-500005346591"),
        // Independently written for short phone notifications, rather than copied paragraphs.
        EncouragementEntry("shine-01", "把屏幕暗下去，把自己的生活亮起来。接下来十分钟，留给你想成为的人。"),
        EncouragementEntry("shine-02", "手机里的故事还会继续，你的故事也值得被认真书写。先完成今天的一小步。"),
        EncouragementEntry("shine-03", "你收藏了许多美好，也可以亲手创造一点美好。放下手机，让一个小计划落地。"),
        EncouragementEntry("shine-04", "不用等状态满分才出发。先做五分钟，给自己一次进入状态的机会。"),
        EncouragementEntry("shine-05", "想成为的那个人，藏在每一次小小的选择里。这一次，把时间投给自己。"),
        EncouragementEntry("shine-06", "今天的进度可以很小，但请给梦想留一个开始。翻开一页，写下一行。"),
        EncouragementEntry("shine-07", "再精彩的推荐，也代替不了你自己的经历。暂停一下，去完成一件真实的小事。"),
        EncouragementEntry("shine-08", "把期待写成计划，把计划拆成一步。现在就选最容易完成的那一步。"),
        EncouragementEntry("shine-09", "别急着要求自己闪耀。先给热爱一点时间，让它慢慢长出力量。"),
        EncouragementEntry("shine-10", "今天还没有结束，你仍然可以为自己赢回十分钟。开始一段短专注吧。"),
        EncouragementEntry("shine-11", "一段分心不会决定你的一天。愿你温柔地收回注意力，再向目标靠近一点。"),
        EncouragementEntry("shine-12", "把点赞别人的时间分一点给自己。认真完成一件事，也给自己一个赞。"),
        EncouragementEntry("shine-13", "你不需要证明比谁更快。沿着自己的方向，每天走出一点真实的进度。"),
        EncouragementEntry("shine-14", "屏幕可以稍后再看，眼前的小目标可以现在就做。给它十分钟试试看。"),
        EncouragementEntry("shine-15", "让休息真的带来恢复。站起来喝口水，再决定下一段时间想怎样度过。"),
        EncouragementEntry("shine-16", "别让一天只剩下浏览记录。为今天留下一个完成的小目标，一页书也算。"),
        EncouragementEntry("shine-17", "与其等待一个充满动力的明天，不如给今天一个轻一点的开始。只做一步就好。"),
        EncouragementEntry("shine-18", "你的注意力很珍贵。把它交给值得投入的事，让生活多一点自己的选择。"),
        EncouragementEntry("shine-19", "想发光，也需要照顾好自己。认真做事，安心休息，然后继续向前。"),
        EncouragementEntry("shine-20", "等你回头看，愿今天留下的是一些进步和快乐。现在先为自己做一件小事。")
    )
}
