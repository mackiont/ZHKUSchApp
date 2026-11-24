package cn.edu.zhku.schedule.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cn.edu.zhku.schedule.widget.ScheduleWidget
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zhku_schedule")

/**
 * 本地存储：设置、凭据、课表快照。
 * 全部放在应用私有目录（data/data/cn.edu.zhku.schedule/files/datastore），不对外暴露。
 */
class Prefs(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object K {
        val ACCOUNT = stringPreferencesKey("account")
        val PASSWORD = stringPreferencesKey("password")
        val COOKIE = stringPreferencesKey("cookie")
        val TIMETABLE = stringPreferencesKey("timetable_json")
        val AUTO_FETCH = booleanPreferencesKey("auto_fetch")
        val FETCH_MINUTE = intPreferencesKey("fetch_minute")
        val NOTIFY_ENABLED = booleanPreferencesKey("notify_enabled")
        val NOTIFY_LEAD = intPreferencesKey("notify_lead")
        val CAMPUS = stringPreferencesKey("campus")
        val SECTIONS = stringPreferencesKey("sections")
        val LAST_FETCH_AT = longPreferencesKey("last_fetch_at")
        val LAST_ERROR = stringPreferencesKey("last_error")
        val ALARM_COUNT = intPreferencesKey("alarm_count")
        val MODE = stringPreferencesKey("data_mode")
        val SOURCE_NAME = stringPreferencesKey("source_name")
        val IMPORTED_AT = longPreferencesKey("imported_at")
    }

    private suspend fun read(): Preferences = appContext.dataStore.data.first()

    /**
     * 所有写盘的统一入口：写完顺手刷新桌面小组件。
     * 课表、节次、数据模式、登录态任何一处变了，小组件都要马上跟着变。
     */
    private suspend fun write(block: suspend (MutablePreferences) -> Unit) {
        appContext.dataStore.edit(block)
        ScheduleWidget.requestRefresh(appContext)
    }

    suspend fun settings(): Settings {
        val p = read()
        val campus = runCatching {
            CampusPreset.valueOf(p[K.CAMPUS] ?: CampusPreset.BAIYUN.name)
        }.getOrDefault(CampusPreset.BAIYUN)
        val sections = SectionTimes.decode(p[K.SECTIONS] ?: "") ?: SectionTimes.preset(campus)
        return Settings(
            account = p[K.ACCOUNT] ?: "",
            hasPassword = !(p[K.PASSWORD] ?: "").isNullOrEmpty(),
            hasCookie = !(p[K.COOKIE] ?: "").isNullOrEmpty(),
            autoFetch = p[K.AUTO_FETCH] ?: true,
            fetchMinuteOfDay = p[K.FETCH_MINUTE] ?: (7 * 60 + 30),
            notifyEnabled = p[K.NOTIFY_ENABLED] ?: true,
            notifyLeadMinutes = p[K.NOTIFY_LEAD] ?: 15,
            campus = campus,
            sections = sections,
            lastFetchAt = p[K.LAST_FETCH_AT] ?: 0L,
            lastError = p[K.LAST_ERROR] ?: "",
            mode = runCatching { DataMode.valueOf(p[K.MODE] ?: DataMode.ONLINE.name) }
                .getOrDefault(DataMode.ONLINE),
            sourceName = p[K.SOURCE_NAME] ?: "",
            importedAt = p[K.IMPORTED_AT] ?: 0L
        )
    }

    suspend fun password(): String = read()[K.PASSWORD] ?: ""
    suspend fun cookie(): String = read()[K.COOKIE] ?: ""

    suspend fun saveCredentials(account: String, password: String, cookie: String) {
        write {
            it[K.ACCOUNT] = account
            if (password.isNotEmpty()) it[K.PASSWORD] = password
            if (cookie.isNotEmpty()) it[K.COOKIE] = cookie
        }
    }

    suspend fun saveCookie(cookie: String) {
        write { it[K.COOKIE] = cookie }
    }

    suspend fun clearCookie() {
        write { it[K.COOKIE] = "" }
    }

    suspend fun logout() {
        write {
            it[K.PASSWORD] = ""
            it[K.COOKIE] = ""
            it[K.ACCOUNT] = ""
            it[K.TIMETABLE] = ""
            it[K.LAST_ERROR] = ""
            it[K.MODE] = DataMode.ONLINE.name
            it[K.SOURCE_NAME] = ""
            it[K.IMPORTED_AT] = 0L
        }
    }

    suspend fun loadTimetable(): Timetable? {
        val raw = read()[K.TIMETABLE] ?: return null
        if (raw.isBlank()) return null
        return runCatching { json.decodeFromString(Timetable.serializer(), raw) }.getOrNull()
    }

    suspend fun saveTimetable(timetable: Timetable) {
        write {
            it[K.TIMETABLE] = json.encodeToString(Timetable.serializer(), timetable)
        }
    }

    /** 保存本地导入的课表，并把数据来源切成本地模式 */
    suspend fun saveLocalTimetable(timetable: Timetable, fileName: String) {
        write {
            it[K.TIMETABLE] = json.encodeToString(Timetable.serializer(), timetable)
            it[K.MODE] = DataMode.LOCAL.name
            it[K.SOURCE_NAME] = fileName
            it[K.IMPORTED_AT] = System.currentTimeMillis()
            it[K.LAST_ERROR] = ""
        }
    }

    /** 只改当前周基线（本地课表放久了会跑偏，允许手动校正） */
    suspend fun rebaseWeek(timetable: Timetable, week: Int) {
        val rebased = timetable.withWeek(week, WeekSource.MANUAL)
        write {
            it[K.TIMETABLE] = json.encodeToString(Timetable.serializer(), rebased)
        }
    }

    suspend fun setMode(mode: DataMode) {
        write { it[K.MODE] = mode.name }
    }

    suspend fun setAutoFetch(enabled: Boolean) {
        write { it[K.AUTO_FETCH] = enabled }
    }

    suspend fun setFetchMinute(minuteOfDay: Int) {
        write { it[K.FETCH_MINUTE] = minuteOfDay.coerceIn(0, 1439) }
    }

    suspend fun setNotifyEnabled(enabled: Boolean) {
        write { it[K.NOTIFY_ENABLED] = enabled }
    }

    suspend fun setNotifyLead(minutes: Int) {
        write { it[K.NOTIFY_LEAD] = minutes.coerceIn(0, 120) }
    }

    suspend fun setCampus(campus: CampusPreset) {
        write {
            it[K.CAMPUS] = campus.name
            it[K.SECTIONS] = SectionTimes.encode(SectionTimes.preset(campus))
        }
    }

    suspend fun setSections(sections: List<SectionTime>) {
        write { it[K.SECTIONS] = SectionTimes.encode(sections) }
    }

    suspend fun markFetch(ok: Boolean, error: String = "") {
        write {
            if (ok) {
                it[K.LAST_FETCH_AT] = System.currentTimeMillis()
                it[K.LAST_ERROR] = ""
            } else {
                it[K.LAST_ERROR] = error
            }
        }
    }

    suspend fun alarmCount(): Int = read()[K.ALARM_COUNT] ?: 0

    suspend fun setAlarmCount(count: Int) {
        write { it[K.ALARM_COUNT] = count }
    }
}
