package cn.shouxin.app.data

import android.content.Context
import cn.shouxin.app.BuildConfig
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private val Context.preferences by preferencesDataStore("shouxin_settings")
data class UserSettings(
    val targets: Set<String> = emptySet(), val goal: String = "完成今天的小目标", val style: String = "GENTLE",
    val quietStart: Int = 1350, val quietEnd: Int = 480, val dailyLimit: Int = 6, val cooldownMinutes: Int = 20,
    val pausedDate: String = "", val monitoring: Boolean = true, val sound: Boolean = false,
    val usageStartAt: Long = 0, val customPhrases: List<String> = emptyList(),
    val repository: String = BuildConfig.UPDATE_REPOSITORY,
    val favorites: Set<String> = emptySet(), val blocked: Set<String> = emptySet()
)
class SettingsStore(private val context: Context) {
    private val targets = stringSetPreferencesKey("targets")
    private val goal = stringPreferencesKey("goal")
    private val style = stringPreferencesKey("style")
    private val quietStart = intPreferencesKey("quiet_start")
    private val quietEnd = intPreferencesKey("quiet_end")
    private val limit = intPreferencesKey("daily_limit")
    private val cooldown = intPreferencesKey("cooldown_minutes")
    private val pausedDate = stringPreferencesKey("paused_date")
    private val monitoring = booleanPreferencesKey("monitoring")
    private val sound = booleanPreferencesKey("sound")
    private val usageStart = longPreferencesKey("usage_start_at")
    private val phrases = stringPreferencesKey("custom_phrases")
    private val repo = stringPreferencesKey("update_repository")
    private val favorites = stringSetPreferencesKey("favorite_content")
    private val blocked = stringSetPreferencesKey("blocked_content")
    private fun decode(p: Preferences): UserSettings {
        val custom = runCatching { val a = JSONArray(p[phrases] ?: "[]"); (0 until a.length()).map { a.getString(it) } }.getOrDefault(emptyList())
        return UserSettings(p[targets] ?: emptySet(), p[goal] ?: "完成今天的小目标", p[style] ?: "GENTLE",
            p[quietStart] ?: 1350, p[quietEnd] ?: 480, p[limit] ?: 6, p[cooldown] ?: 20,
            p[pausedDate] ?: "", p[monitoring] ?: true, p[sound] ?: false, p[usageStart] ?: 0, custom, p[repo] ?: BuildConfig.UPDATE_REPOSITORY, p[favorites] ?: emptySet(), p[blocked] ?: emptySet())
    }
    val flow = context.preferences.data.map(::decode)
    suspend fun read() = decode(context.preferences.data.first())
    suspend fun change(transform: (UserSettings) -> UserSettings) {
        context.preferences.edit { p ->
            val s = transform(decode(p))
            require(s.quietStart in 0..1439 && s.quietEnd in 0..1439 && s.quietStart != s.quietEnd)
            require(s.dailyLimit in 1..20 && s.cooldownMinutes in 5..120)
            p[targets] = s.targets; p[goal] = s.goal.take(80); p[style] = s.style
            p[quietStart] = s.quietStart; p[quietEnd] = s.quietEnd; p[limit] = s.dailyLimit; p[cooldown] = s.cooldownMinutes
            p[pausedDate] = s.pausedDate; p[monitoring] = s.monitoring; p[sound] = s.sound
            p[usageStart] = s.usageStartAt; p[phrases] = JSONArray(s.customPhrases).toString(); p[repo] = s.repository
            p[favorites] = s.favorites; p[blocked] = s.blocked
        }
    }
}
