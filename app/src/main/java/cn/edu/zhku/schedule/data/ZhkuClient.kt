package cn.edu.zhku.schedule.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset

class ZhkuException(
    message: String,
    val kind: Kind = Kind.NETWORK,
    cause: Throwable? = null
) : Exception(message, cause) {
    enum class Kind { AUTH, NETWORK, SERVER, PARSE }
}

data class FetchResult(val timetable: Timetable, val cookie: String)

/**
 * 教务系统客户端。
 *
 * 登录算法（湖南强智科技 jsxsd）：
 *  1) GET /Logon.do?method=logon&flag=sess  返回 "scode#sxh"，同时下发 JSESSIONID
 *  2) code = 学号 + "%%%" + 密码；前 20 个字符逐个输出，再从 scode 头部切掉 sxh[i] 个字符拼上
 *  3) POST /Logon.do?method=logon，userPassword 传空，encoded 传上一步结果
 *
 * ⚠️ 第 1 步返回的 scode/sxh 是**存在服务端会话里**的，第 3 步必须带上同一个 JSESSIONID。
 * 每个请求各建一条连接（相当于每次都是新会话）时，服务端在第 3 步找不到密钥，
 * 只会回一句「该帐号不存在或密码错误」——哪怕账号密码完全正确。
 * 浏览器和 Python 版都是因为自带 cookie 容器才正常，所以这里必须维护一个会话 Cookie 罐。
 *
 * 课表：GET /jsxsd/xskb/xskb_list.do?Ves632DSdyV=NEW_XSD_PYGL，返回服务端渲染的 HTML。
 * 会话失效没有状态码信号（永远 200），只能靠页面特征判断。
 */
class ZhkuClient(private val base: String = BASE) {

    companion object {
        const val BASE = "https://edu-admin.zhku.edu.cn"
        const val MAIN_PAGE = "/jsxsd/framework/xsMain.htmlx"
        const val TIMETABLE_PATH = "/jsxsd/xskb/xskb_list.do?Ves632DSdyV=NEW_XSD_PYGL"
        const val DEFAULT_KBJCMSID = "3831EE04599D452CBFD0532E01DE52BA"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val SHOW_MSG_RE = Regex("""id=["']showMsg["'][^>]*>([^<]+)<""")
        private val CHARSET_RE = Regex("""charset=["']?([\w-]+)""", RegexOption.IGNORE_CASE)
        private const val MAX_REDIRECTS = 3
    }

    private data class Resp(
        val code: Int,
        val text: String,
        val cookies: List<String>,
        val location: String? = null
    )

    /**
     * 会话 Cookie 罐（按名字存，新值覆盖旧值）。
     * - JSESSIONID：登录密钥与登录态都挂在这个会话上，丢了就必然「密码错误」；
     * - SERVERID：负载均衡的粘性会话，丢了会被分到没有登录态的节点，
     *   表现为「登录成功又跳回登录页」。
     */
    private val jar = LinkedHashMap<String, String>()

    // ------------------------------------------------------------ 公开接口

    /**
     * 全流程：确认会话 → 抓课表 →（必要时）自动重登重试一次。
     * @param cachedCookie 本地存的会话 Cookie，可为空
     * @param fallbackWeek 教务系统页面没标出当前周时用的兜底周次（0 表示没有）
     */
    suspend fun load(
        account: String,
        password: String,
        term: String = "",
        week: Int? = null,
        kbjcmsid: String = "",
        cachedCookie: String = "",
        fallbackWeek: Int = 0
    ): FetchResult = withContext(Dispatchers.IO) {
        require(account.isNotBlank()) { "学号为空" }
        adopt(cachedCookie)
        if (cookieHeader().isNullOrBlank() || !sessionValid()) {
            if (password.isBlank()) {
                throw ZhkuException("登录已过期，请在设置里重新登录", ZhkuException.Kind.AUTH)
            }
            login(account, password)
        }

        val page = try {
            fetchPage(term, week, kbjcmsid)
        } catch (e: ZhkuException) {
            if (e.kind == ZhkuException.Kind.AUTH && password.isNotBlank()) {
                login(account, password)
                fetchPage(term, week, kbjcmsid)
            } else {
                throw e
            }
        }

        val parsed = TimetableParser.parse(page)
        // 教务系统在课表页 zc 下拉里帮我们选中的那一周，就是在线的“当前教学周”
        val serverWeek = parsed.currentWeek.takeIf { it > 0 }
        val week = serverWeek ?: fallbackWeek.takeIf { it > 0 } ?: 1
        val timetable = Timetable(
            courses = parsed.courses,
            term = parsed.term.ifBlank { term },
            currentWeek = week,
            weekCount = maxOf(parsed.weekCount, week, 1),
            fetchedAt = System.currentTimeMillis(),
            account = account,
            kbjcmsid = parsed.kbjcmsid.ifBlank {
                kbjcmsid.ifBlank { DEFAULT_KBJCMSID }
            },
            weekSource = if (serverWeek != null) WeekSource.SERVER else WeekSource.ESTIMATED
        )
        FetchResult(timetable, cookieHeader().orEmpty())
    }

    /** 登录，返回可用于下次直接使用的 Cookie 串 */
    suspend fun login(account: String, password: String): String = withContext(Dispatchers.IO) {
        try {
            // 换新会话：旧会话里的密钥已经作废
            synchronized(jar) { jar.clear() }
            request("$base/")

            val sess = request("$base/Logon.do?method=logon&flag=sess").text.trim()
            val hash = sess.indexOf('#')
            if (hash <= 0 || hash >= sess.length - 1) {
                throw ZhkuException(
                    "获取登录密钥失败，教务系统可能正在维护" +
                        if (sess.isNotBlank() && sess.length < 200) "：${sess.take(80)}" else "",
                    ZhkuException.Kind.SERVER
                )
            }
            val scode = sess.substring(0, hash)
            val sxh = sess.substring(hash + 1).trim()
            val encoded = encode(account, password, scode, sxh)

            val resp = request(
                url = "$base/Logon.do?method=logon",
                method = "POST",
                form = mapOf(
                    "userAccount" to account,
                    "userPassword" to "",
                    "encoded" to encoded
                ),
                referer = "$base/"
            )

            if (TimetableParser.looksLikeLogin(resp.text)) {
                throw ZhkuException(serverMessage(resp.text) ?: "学号或密码错误", ZhkuException.Kind.AUTH)
            }
            if (resp.location?.contains("Logon.do", ignoreCase = true) == true) {
                throw ZhkuException("登录被拒绝，请检查学号密码或联系教务管理员", ZhkuException.Kind.AUTH)
            }

            // 登录成功后服务端会 302 到主页，跟过去才能拿到 /jsxsd 用的会话 Cookie
            followRedirects(resp)

            // 302 的正文是空的，不能只凭正文判断，回主页确认一次
            val main = request("$base$MAIN_PAGE")
            val ok = main.code in 200..299 &&
                main.text.isNotBlank() &&
                !TimetableParser.looksLikeLogin(main.text)
            if (!ok) {
                // POST 没回错误页、主页却要登录 → 是会话没保持住，不是密码问题，别误导用户
                val fallback = if (resp.text.isBlank()) {
                    "登录没有生效（会话未能保持），请重试"
                } else {
                    "学号或密码错误，请检查后重试"
                }
                throw ZhkuException(serverMessage(main.text) ?: fallback, ZhkuException.Kind.AUTH)
            }

            val cookie = cookieHeader()
            if (cookie.isNullOrBlank() || !synchronized(jar) { jar.containsKey("JSESSIONID") }) {
                throw ZhkuException("登录成功但没有拿到会话，请重试", ZhkuException.Kind.SERVER)
            }
            cookie
        } catch (e: ZhkuException) {
            throw e
        } catch (e: Exception) {
            throw ZhkuException("网络连接失败：${e.message ?: e.javaClass.simpleName}", ZhkuException.Kind.NETWORK, e)
        }
    }

    suspend fun sessionValid(): Boolean = withContext(Dispatchers.IO) {
        if (cookieHeader().isNullOrBlank()) return@withContext false
        try {
            val html = request("$base$MAIN_PAGE").text
            html.isNotBlank() && !TimetableParser.looksLikeLogin(html)
        } catch (e: Exception) {
            false
        }
    }

    /** 抓取课表页并校验是否被踢回登录页 */
    suspend fun fetchPage(term: String = "", week: Int? = null, kbjcmsid: String = ""): String =
        withContext(Dispatchers.IO) {
            val params = ArrayList<String>()
            if (term.isNotBlank()) params.add("xnxq01id=" + urlEncode(term))
            if (week != null && week > 0) params.add("zc=$week")
            if (kbjcmsid.isNotBlank()) params.add("kbjcmsid=" + urlEncode(kbjcmsid))
            val url = TIMETABLE_PATH.let {
                if (params.isEmpty()) "$base$it" else "$base$it" + "&" + params.joinToString("&")
            }
            val resp = request(url, referer = "$base$MAIN_PAGE")
            if (resp.code >= 400) {
                throw ZhkuException("教务系统返回 ${resp.code}，请稍后再试", ZhkuException.Kind.SERVER)
            }
            if (resp.text.isBlank()) {
                throw ZhkuException("教务系统返回了空页面，请稍后再试", ZhkuException.Kind.SERVER)
            }
            if (TimetableParser.looksLikeLogin(resp.text)) {
                throw ZhkuException("登录状态已失效", ZhkuException.Kind.AUTH)
            }
            resp.text
        }

    // ------------------------------------------------------------ 算法

    /** 强智前端 loginajax() 的切片混淆算法 */
    fun encode(account: String, password: String, scodeIn: String, sxh: String): String {
        val code = "$account%%%$password"
        var scode = scodeIn
        val out = StringBuilder()
        var i = 0
        while (i < code.length) {
            if (i < 20 && i < sxh.length) {
                out.append(code[i])
                val n = (sxh[i].digitToIntOrNull() ?: 0).coerceIn(0, scode.length)
                out.append(scode.take(n))
                scode = scode.drop(n)
                i++
            } else {
                out.append(code.substring(i))
                break
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------ Cookie

    private fun cookieHeader(): String? = synchronized(jar) {
        if (jar.isEmpty()) {
            null
        } else {
            jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
    }

    private fun absorb(cookies: List<String>) {
        if (cookies.isEmpty()) return
        synchronized(jar) {
            for (header in cookies) {
                val pair = header.substringBefore(';').trim()
                val idx = pair.indexOf('=')
                if (idx <= 0) continue
                val name = pair.substring(0, idx).trim()
                val value = pair.substring(idx + 1).trim()
                if (name.isEmpty() || value.isEmpty()) continue
                jar[name] = value
            }
        }
    }

    private fun adopt(cookie: String?) {
        if (cookie.isNullOrBlank()) return
        synchronized(jar) {
            for (part in cookie.split(';')) {
                val p = part.trim()
                val idx = p.indexOf('=')
                if (idx <= 0) continue
                val name = p.substring(0, idx).trim()
                val value = p.substring(idx + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty()) jar[name] = value
            }
        }
    }

    private fun serverMessage(html: String): String? =
        SHOW_MSG_RE.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** 登录成功后的 302 要跟过去：服务端会在 /jsxsd 路径上补发会话 Cookie */
    private fun followRedirects(first: Resp) {
        var resp = first
        var hops = 0
        while (resp.code in 300..399 && hops < MAX_REDIRECTS) {
            val next = resp.location ?: return
            val url = when {
                next.startsWith("http://") || next.startsWith("https://") -> next
                next.startsWith("/") -> base + next
                else -> "$base/$next"
            }
            resp = request(url, referer = "$base/")
            hops++
        }
    }

    // ------------------------------------------------------------ HTTP

    private fun request(
        url: String,
        method: String = "GET",
        form: Map<String, String>? = null,
        referer: String? = null
    ): Resp {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 25000
            instanceFollowRedirects = false
            requestMethod = method
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            cookieHeader()?.let { setRequestProperty("Cookie", it) }
            if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
        }
        try {
            if (form != null) {
                val body = form.entries.joinToString("&") {
                    urlEncode(it.key) + "=" + urlEncode(it.value)
                }.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val bytes = try {
                stream?.use { it.readBytes() } ?: ByteArray(0)
            } catch (_: Exception) {
                ByteArray(0)
            }
            val text = decode(bytes, conn.contentType)
            val headers: List<String> = conn.headerFields
                .filterKeys { key -> key?.equals("Set-Cookie", ignoreCase = true) == true }
                .values.flatten()
            absorb(headers)
            val location = conn.getHeaderField("Location")
            return Resp(code, text, headers, location)
        } finally {
            conn.disconnect()
        }
    }

    private fun decode(bytes: ByteArray, contentType: String?): String {
        if (bytes.isEmpty()) return ""
        val declared = contentType?.let { CHARSET_RE.find(it)?.groupValues?.get(1) }
            ?: CHARSET_RE.find(String(bytes, 0, minOf(2048, bytes.size), Charsets.ISO_8859_1))
                ?.groupValues?.get(1)
        val candidates = listOfNotNull(declared, "UTF-8", "GBK", "GB18030").distinct()
        for (name in candidates) {
            try {
                val cs = Charset.forName(name)
                val text = String(bytes, cs)
                if (!text.contains('\uFFFD')) return text
            } catch (_: Exception) {
                // 换下一个编码
            }
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
