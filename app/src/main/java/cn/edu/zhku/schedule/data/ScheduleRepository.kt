package cn.edu.zhku.schedule.data

import android.content.Context
import java.time.LocalDate

/**
 * 课表数据仓库：抓取 + 本地缓存。
 * 抓取成功后立即落盘，之后离线也能看课表。
 */
class ScheduleRepository(
    context: Context,
    private val prefs: Prefs
) {
    private val client = ZhkuClient()

    suspend fun cached(): Timetable? = prefs.loadTimetable()

    /** 用本地保存的学号密码（或 cookie）刷新课表 */
    suspend fun refresh(): Timetable = refresh(prefs.settings())

    suspend fun refresh(settings: Settings): Timetable =
        refresh(settings.account, prefs.password(), settings)

    suspend fun refresh(account: String, password: String, settings: Settings): Timetable {
        if (account.isBlank()) {
            throw ZhkuException("请先填写学号和密码", ZhkuException.Kind.AUTH)
        }
        val cachedTimetable = prefs.loadTimetable()
        return try {
            val result = client.load(
                account = account,
                password = password,
                // 不固定学期：让教务系统返回它认为的当前学期，
                // 否则新学期开始后会一直抓到旧学期的课表
                term = "",
                week = null,
                kbjcmsid = cachedTimetable?.kbjcmsid ?: ZhkuClient.DEFAULT_KBJCMSID,
                cachedCookie = prefs.cookie(),
                // 教务系统没标出当前周时（zc 停在“全部”），用上次的周次按自然周推算兜底
                fallbackWeek = cachedTimetable?.weekAt(LocalDate.now()) ?: 0
            )
            prefs.saveCookie(result.cookie)
            prefs.saveCredentials(account, password, "")
            prefs.saveTimetable(result.timetable)
            // 抓取成功说明回到了在线数据源
            prefs.setMode(DataMode.ONLINE)
            prefs.markFetch(true)
            result.timetable
        } catch (e: ZhkuException) {
            prefs.markFetch(false, e.message ?: "获取失败")
            throw e
        } catch (e: Exception) {
            prefs.markFetch(false, e.message ?: "获取失败")
            throw ZhkuException("获取课表失败：${e.message ?: e.javaClass.simpleName}", ZhkuException.Kind.NETWORK, e)
        }
    }

    // ---------------------------------------------------------------- 本地模式

    /** 解析用户选中的 JSON（不落盘，先给用户确认周次） */
    fun parseLocal(raw: String, fileName: String): PendingImport =
        PendingImport(LocalImport.parse(raw, fileName), fileName)

    /** 确认导入：写盘并切到本地模式 */
    suspend fun commitLocal(pending: PendingImport, week: Int): Timetable {
        val timetable = pending.timetable.withWeek(week, WeekSource.IMPORT)
        prefs.saveLocalTimetable(timetable, pending.fileName)
        return timetable
    }

    /** 校正当前周（本地课表不联网，周次会随时间跑偏） */
    suspend fun rebaseWeek(week: Int): Timetable? {
        val current = prefs.loadTimetable() ?: return null
        prefs.rebaseWeek(current, week)
        return prefs.loadTimetable()
    }

    /** 切回在线模式（保留本地课表作为离线兜底） */
    suspend fun switchToOnline() {
        prefs.setMode(DataMode.ONLINE)
    }

    suspend fun exportJson(): String? = prefs.loadTimetable()?.let { LocalImport.encode(it) }
}
