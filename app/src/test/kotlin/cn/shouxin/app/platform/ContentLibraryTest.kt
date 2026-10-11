package cn.shouxin.app.platform

import cn.shouxin.app.data.UserSettings
import org.junit.Assert.*
import org.junit.Test

class ContentLibraryTest {
    @Test fun inspiringStyleUsesShortQuotesAndKeepsTheRequestedExample() {
        val eligible = EncouragementQuotes.entries.map { it.id }.toSet()
        assertTrue(EncouragementQuotes.entries.any { it.body == "努力让自己发光，对的人才会迎光而来。" })
        repeat(80) {
            val content = ContentLibrary.select(UserSettings(style = "INSPIRING"), null, null, emptySet())
            assertTrue(content.id in eligible)
            assertTrue(content.body.length <= 180)
        }
    }
    @Test fun blockingTakesPrecedenceOverFavorites() {
        val all = ContentLibrary.catalog().map { it.id }.toSet()
        val settings = UserSettings(style = "INSPIRING", favorites = setOf("example-shine"), blocked = all - "web-act-now")
        assertEquals("web-act-now", ContentLibrary.select(settings, null, null, emptySet()).id)
    }
    @Test fun recentQuoteIsNotRepeatedWhenAnotherIsAvailable() {
        val all = ContentLibrary.catalog().map { it.id }.toSet()
        val settings = UserSettings(style = "INSPIRING", blocked = all - setOf("example-shine", "web-act-now"))
        assertEquals("web-act-now", ContentLibrary.select(settings, null, null, setOf("example-shine")).id)
    }
    @Test fun existingOriginalFavoritesSurviveSwitchingToInspiring() {
        val all = ContentLibrary.catalog().map { it.id }.toSet()
        val settings = UserSettings(style = "INSPIRING", favorites = setOf("original-0-0"), blocked = all - "original-0-0")
        assertEquals("original-0-0", ContentLibrary.select(settings, null, null, emptySet()).id)
    }
    @Test fun customContentStillWorksAndAllBlockedContentHasASafeFallback() {
        val blocked = ContentLibrary.catalog().map { it.id }.toSet()
        val settings = UserSettings(style = "INSPIRING", blocked = blocked, customPhrases = listOf("先读一页书。"))
        assertEquals("先读一页书。", ContentLibrary.select(settings, null, null, emptySet()).body)
        assertEquals("fallback", ContentLibrary.select(settings.copy(customPhrases = emptyList()), null, null, emptySet()).id)
    }
}
