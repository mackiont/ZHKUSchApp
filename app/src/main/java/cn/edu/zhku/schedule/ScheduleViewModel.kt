package cn.edu.zhku.schedule

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.zhku.schedule.data.CampusPreset
import cn.edu.zhku.schedule.data.DataMode
import cn.edu.zhku.schedule.data.ImportException
import cn.edu.zhku.schedule.data.LocalImport
import cn.edu.zhku.schedule.data.PendingImport
import cn.edu.zhku.schedule.data.Prefs
import cn.edu.zhku.schedule.data.ScheduleRepository
import cn.edu.zhku.schedule.data.SectionTime
import cn.edu.zhku.schedule.data.Settings
import cn.edu.zhku.schedule.data.Timetable
import cn.edu.zhku.schedule.data.ZhkuException
import cn.edu.zhku.schedule.notify.ClassNotifier
import cn.edu.zhku.schedule.work.DailyWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class UiState(
    val ready: Boolean = false,
    val refreshing: Boolean = false,
    val loggingIn: Boolean = false,
    val importing: Boolean = false,
    val timetable: Timetable? = null,
    val settings: Settings = Settings(),
    val toast: String? = null,
    /** 需要用户重新输入密码（cookie 失效且自动重登失败） */
    val needRelogin: Boolean = false,
    /** 在线模式但连不上教务系统，正在用本地缓存的课表 */
    val offline: Boolean = false,
    /** 主动要求打开登录页（从本地模式切回在线模式） */
    val loginPrompt: Boolean = false,
    /** 已解析、待确认周次的本地文件 */
    val pendingImport: PendingImport? = null
) {
    val loggedIn: Boolean get() = settings.loggedIn
    val isLocal: Boolean get() = settings.isLocal
    /** 没有凭据也没有缓存课表（首次启动）→ 需要登录页 */
    val needLogin: Boolean get() = !settings.loggedIn && timetable == null && !settings.isLocal
    val showLogin: Boolean get() = needLogin || loginPrompt

    /** 首页顶部提示条（本地模式 / 离线兜底） */
    val homeBanner: String?
        get() = when {
            isLocal -> "本地模式 · 课表来自导入的文件，不会联网更新"
            offline && timetable != null -> "离线模式 · 在线更新失败，显示本地缓存的课表"
            else -> null
        }
}

class ScheduleViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val repo = ScheduleRepository(app, prefs)
    private val notifier = ClassNotifier(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        notifier.ensureChannel()
        viewModelScope.launch {
            var settings = withContext(Dispatchers.IO) { prefs.settings() }
            var timetable = withContext(Dispatchers.IO) { repo.cached() }
            // 本地模式却没有课表（数据被清掉）→ 回到在线模式，走登录流程
            if (settings.isLocal && timetable == null) {
                withContext(Dispatchers.IO) { prefs.setMode(DataMode.ONLINE) }
                settings = withContext(Dispatchers.IO) { prefs.settings() }
            }
            _state.update { it.copy(ready = true, settings = settings, timetable = timetable) }

            when {
                // 本地模式：不联网，只重排提醒
                settings.isLocal -> reschedule()
                // 在线模式且有凭据：启动时静默更新一次；失败则用缓存进入离线模式
                settings.loggedIn -> refresh(silent = true)
                else -> reschedule()
            }
        }
    }

    fun refresh(silent: Boolean = false) {
        if (_state.value.refreshing) return
        if (_state.value.settings.isLocal) {
            _state.update { it.copy(toast = "本地模式不会联网更新，可在设置里切换到在线模式") }
            return
        }
        if (!_state.value.settings.loggedIn && _state.value.timetable != null) {
            // 有缓存但没凭据（例如刚退出本地模式）：直接引导登录
            _state.update { it.copy(loginPrompt = true) }
            return
        }
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                val timetable = withContext(Dispatchers.IO) { repo.refresh() }
                val settings = withContext(Dispatchers.IO) { prefs.settings() }
                _state.update {
                    it.copy(
                        refreshing = false,
                        timetable = timetable,
                        settings = settings,
                        offline = false,
                        loginPrompt = false,
                        toast = if (silent) null else "课表已更新"
                    )
                }
                reschedule()
            } catch (e: Exception) {
                val hasCache = _state.value.timetable != null
                val authError = (e as? ZhkuException)?.kind == ZhkuException.Kind.AUTH
                _state.update {
                    it.copy(
                        refreshing = false,
                        // 有缓存就降级为离线模式继续用；没缓存只能提示错误
                        offline = hasCache,
                        // 启动时的静默失败不弹登录框，避免每次开应用都被打断
                        needRelogin = authError && !silent,
                        toast = if (silent && hasCache) null else e.friendlyMessage()
                    )
                }
            }
        }
    }

    fun login(account: String, password: String) {
        if (_state.value.loggingIn) return
        if (_state.value.settings.isLocal) {
            _state.update { it.copy(toast = "当前是本地模式，请先切换到在线模式") }
            return
        }
        if (account.isBlank() || password.isBlank()) {
            _state.update { it.copy(toast = "请填写学号和密码") }
            return
        }
        _state.update { it.copy(loggingIn = true) }
        viewModelScope.launch {
            try {
                val timetable = withContext(Dispatchers.IO) {
                    val settings = prefs.settings()
                    repo.refresh(account, password, settings)
                }
                val settings = withContext(Dispatchers.IO) { prefs.settings() }
                _state.update {
                    it.copy(
                        loggingIn = false,
                        timetable = timetable,
                        settings = settings,
                        toast = "登录成功",
                        needRelogin = false,
                        loginPrompt = false,
                        offline = false
                    )
                }
                reschedule()
            } catch (e: Exception) {
                _state.update { it.copy(loggingIn = false, toast = e.friendlyMessage()) }
            }
        }
    }

    // ------------------------------------------------------------ 本地模式导入

    fun importFrom(uri: Uri) {
        if (_state.value.importing) return
        _state.update { it.copy(importing = true) }
        viewModelScope.launch {
            try {
                val (raw, name) = withContext(Dispatchers.IO) { readDocument(uri) }
                val pending = withContext(Dispatchers.IO) { repo.parseLocal(raw, name) }
                _state.update {
                    it.copy(
                        importing = false,
                        pendingImport = pending,
                        toast = null
                    )
                }
            } catch (e: ImportException) {
                _state.update { it.copy(importing = false, toast = e.message) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(importing = false, toast = "读取文件失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    fun confirmImport(week: Int) {
        val pending = _state.value.pendingImport ?: return
        _state.update { it.copy(pendingImport = null, importing = true) }
        viewModelScope.launch {
            try {
                val timetable = withContext(Dispatchers.IO) { repo.commitLocal(pending, week) }
                val settings = withContext(Dispatchers.IO) { prefs.settings() }
                // 本地模式不抓课表，但仍要保留每日任务来续排上课提醒
                DailyWork.scheduleNext(getApplication(), settings.fetchMinuteOfDay)
                _state.update {
                    it.copy(
                        importing = false,
                        timetable = timetable,
                        settings = settings,
                        toast = "已导入 ${pending.courseCount} 门课程（第 ${timetable.currentWeek} 周）",
                        offline = false,
                        loginPrompt = false,
                        needRelogin = false
                    )
                }
                reschedule()
            } catch (e: Exception) {
                _state.update {
                    it.copy(importing = false, toast = "导入失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    fun cancelImport() {
        _state.update { it.copy(pendingImport = null) }
    }

    /** 导出当前课表为 JSON（用系统文件选择器选保存位置） */
    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { repo.exportJson() }
                    ?: throw ImportException("还没有课表可以导出")
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                        ?: throw ImportException("无法写入所选文件")
                }
                _state.update { it.copy(toast = "课表已导出") }
            } catch (e: Exception) {
                _state.update { it.copy(toast = e.message ?: "导出失败") }
            }
        }
    }

    fun adjustCurrentWeek(week: Int) {
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) { repo.rebaseWeek(week) }
            if (updated != null) {
                _state.update { it.copy(timetable = updated, toast = "已按第 ${updated.currentWeek} 周计算") }
                reschedule()
            }
        }
    }

    /** 本地模式 → 在线模式：打开登录页，本地课表暂时留作离线兜底 */
    fun switchToOnline() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.switchToOnline() }
            val settings = withContext(Dispatchers.IO) { prefs.settings() }
            _state.update {
                it.copy(
                    settings = settings,
                    offline = it.timetable != null,
                    loginPrompt = true,
                    toast = "已切换到在线模式，请登录以自动更新课表"
                )
            }
        }
    }

    fun dismissLogin() {
        _state.update { it.copy(loginPrompt = false) }
    }

    fun requestRelogin() {
        _state.update { it.copy(needRelogin = true) }
    }

    fun dismissRelogin() {
        _state.update { it.copy(needRelogin = false) }
    }

    fun logout() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { prefs.logout() }
            notifier.reschedule(null, emptyList(), false, 0, prefs)
            DailyWork.cancel(getApplication())
            val settings = prefs.settings()
            _state.update {
                it.copy(
                    settings = settings,
                    timetable = null,
                    toast = if (it.isLocal) "已退出本地模式" else "已退出登录",
                    offline = false,
                    loginPrompt = false,
                    needRelogin = false
                )
            }
        }
    }

    fun setAutoFetch(enabled: Boolean) {
        viewModelScope.launch {
            if (_state.value.settings.isLocal) {
                _state.update { it.copy(toast = "本地模式下无法自动获取课表") }
                return@launch
            }
            withContext(Dispatchers.IO) { prefs.setAutoFetch(enabled) }
            if (enabled) {
                val s = prefs.settings()
                DailyWork.scheduleNext(getApplication(), s.fetchMinuteOfDay)
            } else {
                DailyWork.cancel(getApplication())
            }
            syncSettings()
        }
    }

    fun setFetchMinute(minuteOfDay: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { prefs.setFetchMinute(minuteOfDay) }
            val s = prefs.settings()
            if (s.autoFetch && !s.isLocal) {
                DailyWork.scheduleNext(getApplication(), s.fetchMinuteOfDay)
            }
            syncSettings()
        }
    }

    fun setNotifyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { prefs.setNotifyEnabled(enabled) }
            syncSettings()
            reschedule()
        }
    }

    fun setNotifyLead(minutes: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { prefs.setNotifyLead(minutes) }
            syncSettings()
            reschedule()
        }
    }

    fun setCampus(preset: CampusPreset) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { prefs.setCampus(preset) }
            syncSettings()
            reschedule()
        }
    }

    fun setSectionTime(index: Int, start: Int, end: Int) {
        viewModelScope.launch {
            val current = prefs.settings().sections.toMutableList()
            if (index !in current.indices) return@launch
            current[index] = SectionTime(start, end)
            withContext(Dispatchers.IO) { prefs.setSections(current) }
            syncSettings()
            reschedule()
        }
    }

    /** 应用回到前台时重排提醒（闹钟可能被系统清理） */
    fun onForeground() {
        viewModelScope.launch {
            val s = prefs.settings()
            if (s.autoFetch && !s.isLocal) {
                DailyWork.scheduleNext(getApplication(), s.fetchMinuteOfDay)
            }
            reschedule()
        }
    }

    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }

    private suspend fun syncSettings() {
        val settings = withContext(Dispatchers.IO) { prefs.settings() }
        _state.update { it.copy(settings = settings) }
    }

    private fun reschedule() {
        viewModelScope.launch {
            val s = prefs.settings()
            val tt = prefs.loadTimetable()
            notifier.reschedule(tt, s.sections, s.notifyEnabled, s.notifyLeadMinutes, prefs)
        }
    }

    // ------------------------------------------------------------ 文件读写

    private fun readDocument(uri: Uri): Pair<String, String> {
        val resolver = getApplication<Application>().contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: "课表.json"

        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                total += read
                if (total > LocalImport.MAX_BYTES) {
                    throw ImportException("文件太大（超过 4 MB），可能不是课表 JSON")
                }
                out.write(chunk, 0, read)
            }
            out.toByteArray()
        } ?: throw ImportException("无法读取这个文件")

        return decode(bytes) to name
    }

    /** 优先 UTF-8；个别编辑器另存为 GBK 时兜底，避免中文课名变成乱码 */
    private fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        return runCatching { String(bytes, charset("GBK")) }.getOrDefault(utf8)
    }
}

private fun Exception.friendlyMessage(): String = when (this) {
    is ZhkuException -> message ?: "操作失败"
    is ImportException -> message ?: "导入失败"
    else -> message?.let { "操作失败：$it" } ?: "操作失败，请稍后再试"
}
