package cn.edu.zhku.schedule

import cn.edu.zhku.schedule.data.ZhkuClient
import cn.edu.zhku.schedule.data.WeekSource
import cn.edu.zhku.schedule.data.ZhkuException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * 用本地假教务系统验证登录流程，重点锁住一个真实反馈过的 bug：
 *
 * `/Logon.do?method=logon&flag=sess` 返回的 scode/sxh 是存在服务端会话里的，
 * 提交 encoded 时必须带上同一个 JSESSIONID。早期实现每个请求各建一条连接、
 * 不带 Cookie，服务端找不到密钥就一律回「该帐号不存在或密码错误」，
 * 账号密码是对的也登不进去。这个假服务器会像真服务器一样：
 * POST 不带 sess 那一步下发的 JSESSIONID 就直接报密码错误。
 */
class ZhkuClientLoginTest {

    private companion object {
        const val ACCOUNT = "202620264422"
        const val PASSWORD = "Passw0rd!"
        const val SCODE = "6obj2e5ad8675sEygo73ol54cH0J2c74ya2L3YZ3"
        const val SXH = "22112322131212223233"

        const val LOGIN_PAGE = """<html><body><form id="loginForm" action="/Logon.do?method=logon">
            <font id="showMsg">该帐号不存在或密码错误,请联系管理员!</font></form></body></html>"""

        /** 与 Python 版 `_encode` 等价的实现，用在假服务器上校验 encoded */
        fun encode(account: String, password: String, scodeIn: String, sxh: String): String {
            val code = "$account%%%$password"
            var scode = scodeIn
            val out = StringBuilder()
            for (i in code.indices) {
                if (i < 20 && i < sxh.length) {
                    out.append(code[i])
                    val n = sxh[i].digitToInt()
                    out.append(scode.take(n))
                    scode = scode.drop(n)
                } else {
                    out.append(code.substring(i))
                    break
                }
            }
            return out.toString()
        }
    }

    private lateinit var fake: FakeZhku
    private lateinit var client: ZhkuClient

    @Before
    fun setUp() {
        fake = FakeZhku()
        client = ZhkuClient(base = "http://127.0.0.1:${fake.port}")
    }

    @After
    fun tearDown() {
        fake.close()
    }

    // ------------------------------------------------------------ 用例

    @Test
    fun loginReturnsSessionCookie() = runBlocking {
        val cookie = client.login(ACCOUNT, PASSWORD)
        assertTrue("必须带回 JSESSIONID：$cookie", cookie.contains("JSESSIONID="))
        assertTrue("必须带回 SERVERID：$cookie", cookie.contains("SERVERID="))
        assertEquals("只应提交一次登录", 1, fake.loginPosts.get())
    }

    @Test
    fun wrongPasswordIsReported() = runBlocking {
        val e = runCatching { client.login(ACCOUNT, "not-the-password") }.exceptionOrNull()
        assertNotNull("错误密码必须失败", e)
        assertTrue("应是 AUTH 异常，实际：$e", e is ZhkuException)
        assertEquals(ZhkuException.Kind.AUTH, (e as ZhkuException).kind)
        assertTrue("应透出服务端提示，实际：${e.message}", e.message!!.contains("密码"))
    }

    @Test
    fun loadFetchesTimetable() = runBlocking {
        val result = client.load(ACCOUNT, PASSWORD, cachedCookie = "")
        assertEquals("应解析出 4 条课程", 4, result.timetable.courses.size)
        assertTrue(result.cookie.contains("JSESSIONID="))
    }

    @Test
    fun validCookieSkipsLogin() = runBlocking {
        val cookie = client.login(ACCOUNT, PASSWORD)
        // 新实例只带 cookie，密码故意给错：会话复用的路径不该再登录
        val fresh = ZhkuClient(base = "http://127.0.0.1:${fake.port}")
        val result = fresh.load(ACCOUNT, "wrong-on-purpose", cachedCookie = cookie)
        assertEquals(4, result.timetable.courses.size)
        assertEquals("不应重复登录", 1, fake.loginPosts.get())
    }

    @Test
    fun loadUsesServerWeek() = runBlocking {
        fake.weekInPage = 12
        val result = client.load(ACCOUNT, PASSWORD)
        assertEquals("应以教务系统返回的当前周为准", 12, result.timetable.currentWeek)
        assertEquals(WeekSource.SERVER, result.timetable.weekSource)
    }

    @Test
    fun loadFallsBackWhenServerHasNoWeek() = runBlocking {
        fake.weekInPage = null // 页面停在“全部”，服务端没标当前周
        val result = client.load(ACCOUNT, PASSWORD, fallbackWeek = 9)
        assertEquals("应退回上次推算的周次", 9, result.timetable.currentWeek)
        assertEquals(WeekSource.ESTIMATED, result.timetable.weekSource)
    }

    @Test
    fun encodeMatchesPython() {
        assertEquals(
            "26o0bj226e25a0d862756sE4y4go7232ol%5%4c%H0PJ2ac74syas2L3wYZ30rd!",
            client.encode(ACCOUNT, PASSWORD, SCODE, SXH)
        )
        assertEquals("A0112%%%b", client.encode("A1", "b", "0123456789", "12"))
    }

    // ------------------------------------------------------------ 假服务器

    private inner class FakeZhku : AutoCloseable {

        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort

        /** 会话表：sid -> (scode, sxh, 是否已登录) */
        private val sessions = HashMap<String, Triple<String, String, Boolean>>()
        val loginPosts = AtomicInteger(0)

        /** 让假课表页带上 zc 下拉，模拟教务系统标出的当前周；null = 停在“全部” */
        var weekInPage: Int? = null

        private val timetableHtml: String by lazy {
            javaClass.classLoader!!.getResourceAsStream("sample_kb.html")!!
                .bufferedReader(Charsets.UTF_8).readText()
        }

        private val worker = Thread {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { handle(it) }
                } catch (_: Exception) {
                    return@Thread
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            runCatching { socket.close() }
            worker.interrupt()
        }

        private fun handle(client: Socket) {
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            val head = requestLine.split(' ')
            if (head.size < 2) return
            val method = head[0]
            val target = head[1]

            val headers = HashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val buf = CharArray(length)
            var read = 0
            while (read < length) {
                val n = reader.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
            val body = String(buf, 0, read)

            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            val sid = headers["cookie"]
                ?.split(';')
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("JSESSIONID=") }
                ?.substringAfter('=')

            val resp: Reply = when {
                // 下发登录密钥：同时种下 JSESSIONID，并把密钥记在该会话下
                path == "/Logon.do" && query.contains("flag=sess") -> {
                    val id = "S" + System.nanoTime()
                    sessions[id] = Triple(SCODE, SXH, false)
                    Reply(200, "$SCODE#$SXH", listOf("Set-Cookie" to "JSESSIONID=$id; Path=/"))
                }

                path == "/Logon.do" && method == "POST" -> {
                    loginPosts.incrementAndGet()
                    val form = body.split('&').filter { it.contains('=') }.associate {
                        val i = it.indexOf('=')
                        URLDecoder.decode(it.substring(0, i), "UTF-8") to
                            URLDecoder.decode(it.substring(i + 1), "UTF-8")
                    }
                    val session = sid?.let { sessions[it] }
                    // 关键：没带会话 → 服务端根本找不到密钥，和真服务器一样回密码错误
                    if (session == null) {
                        Reply(200, LOGIN_PAGE)
                    } else if (form["userAccount"] == ACCOUNT &&
                        form["encoded"] == encode(ACCOUNT, PASSWORD, session.first, session.second)
                    ) {
                        sessions[sid] = Triple(session.first, session.second, true)
                        Reply(302, "", listOf("Location" to "/jsxsd/framework/xsMain.htmlx"))
                    } else {
                        Reply(200, LOGIN_PAGE)
                    }
                }

                path == "/jsxsd/framework/xsMain.htmlx" -> {
                    val authed = sid?.let { sessions[it]?.third } == true
                    Reply(200, if (authed) "<html><body>main</body></html>" else LOGIN_PAGE)
                }

                path == "/jsxsd/xskb/xskb_list.do" -> {
                    val authed = sid?.let { sessions[it]?.third } == true
                    Reply(200, if (authed) zcSelect() + timetableHtml else LOGIN_PAGE)
                }

                else -> Reply(200, "ok", listOf("Set-Cookie" to "SERVERID=nodeA; path=/"))
            }
            write(client, resp)
        }

        /** 与真实页面同构的周次下拉：服务端会在“当前周”那一项上打 selected */
        private fun zcSelect(): String {
            val current = weekInPage ?: return ""
            val options = (1..20).joinToString("") { w ->
                if (w == current) """<option value="$w" selected="selected">第${w}周</option>"""
                else """<option value="$w">第${w}周</option>"""
            }
            return """<select name="zc" id="zc"><option value="">(全部)</option>$options</select>"""
        }

        private fun write(client: Socket, reply: Reply) {
            val bytes = reply.body.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ${reply.code} X\r\n")
            sb.append("Content-Type: text/html;charset=utf-8\r\n")
            sb.append("Content-Length: ${bytes.size}\r\n")
            sb.append("Connection: close\r\n")
            reply.headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            sb.append("\r\n")
            val out = client.getOutputStream()
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        }
    }

    private data class Reply(
        val code: Int,
        val body: String,
        val headers: List<Pair<String, String>> = emptyList()
    )
}
