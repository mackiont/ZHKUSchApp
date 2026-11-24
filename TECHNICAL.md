# 技术细节 · ZHKU Schedule

写给想看实现的人。怎么用见 [README](README.md)。

---

## 1. 概览

| 项 | 值 |
|---|---|
| 语言 / UI | Kotlin + Jetpack Compose + Material 3，单 Activity |
| 架构 | `ViewModel` + `StateFlow` 单向数据流，无 DI 框架，依赖手工注入 |
| minSdk / targetSdk / compileSdk | 26（Android 8.0）/ 36 / 36 |
| 构建 | Gradle 8.13 + AGP + JDK 17，版本目录外的依赖全部显式写版本 |
| 主要依赖 | compose-bom 2025.05.00、datastore-preferences 1.1.7、work-runtime-ktx 2.10.1、navigation-compose 2.9.0、kotlinx-serialization-json 1.8.1 |
| 网络 | `HttpURLConnection` 手写，不引 OkHttp / Retrofit（只有一所学校、几个固定接口） |
| HTML 解析 | 正则 + 字符串切片，不引 Jsoup（页面结构稳定，且能精确控制容错） |
| 测试 | JUnit4 + Robolectric 4.14.1 + Compose UI Test，57 个用例（含 11 张界面截图） |
| 代码量 | `app/src/main` 约 4.5k 行 Kotlin |

不使用 Hilt / Koin：依赖图只有 `Prefs → ScheduleRepository → ZhkuClient`，手工传参比配注解更短。

---

## 2. 工程结构

```
app/src/main/java/cn/edu/zhku/schedule/
├── MainActivity.kt          单 Activity 入口，edge-to-edge + 主题
├── ZhkuApp.kt               Application：建通知渠道、排每日任务
├── ScheduleViewModel.kt     全部界面状态的唯一来源（StateFlow）
├── data/
│   ├── Models.kt            Course / Timetable / WeekSource / Settings / DataMode
│   ├── ZhkuClient.kt        教务系统 HTTP 客户端：登录、会话、抓课表
│   ├── TimetableParser.kt   课表 HTML → Course 列表
│   ├── ScheduleRepository.kt 抓取 + 缓存 + 本地模式的用例编排
│   ├── Prefs.kt             DataStore 读写（唯一持久化入口）
│   ├── SectionTimes.kt      节次时间预设（白云 / 海珠）与格式化
│   ├── ScheduleMath.kt      「当前这节课」「下一节课」「今天还剩几节」等派生计算
│   └── LocalImport.kt       课表 JSON 的解析与导出
├── ui/
│   ├── App.kt               导航骨架（登录 / 首页 / 课表 / 设置）
│   ├── HomeScreen.kt        首页大卡片 + 今日剩余课程
│   ├── WeekScreen.kt        7×12 周视图
│   ├── SettingsScreen.kt    设置全部条目
│   ├── LoginScreen.kt       登录 / 导入入口
│   ├── ImportDialogs.kt     导入确认、周次校正等弹窗
│   └── theme/               配色、课程调色板
├── notify/
│   ├── ClassNotifier.kt     上课提醒：通知渠道 + 精确闹钟排期
│   ├── ClassAlarmReceiver.kt 闹钟到点 → 发通知
│   └── BootReceiver.kt      开机 / 改时间后重排每日任务与提醒
├── work/
│   └── DailyWork.kt         每日定时获取（一次性任务自续排）
└── widget/
    ├── WidgetState.kt       小组件状态机（纯函数，可单测）
    ├── ScheduleWidget.kt    RemoteViews 渲染 + 闹钟调度
    ├── WidgetPin.kt         把小组件固定到桌面（requestPinAppWidget）
    ├── ScheduleWidgetProvider.kt  AppWidgetProvider 生命周期
    └── WidgetAlarmReceiver.kt     状态切换闹钟回调
```

`app/src/test` 下 8 个测试文件、10 个测试类，覆盖解析、周次、登录协议、导入、以及 UI / 小组件的真实渲染截图。

---

## 3. 数据模型

### Course

一个上课时间块，字段与教务系统课表单元格一一对应。

| 字段 | 说明 |
|---|---|
| `name` `teacher` `room` `building` `klass` | 展示用，均允许为空 |
| `weeksText` | 服务端原始写法，如 `5,7-18(周)`，原样保留 |
| `weeks` | 由 `weeksText` 展开的周次列表，用于 `activeIn(week)` 快速判断 |
| `sections` | 节次列表，如 `[1, 2]` |
| `weekday` | 1–7，周一为 1 |
| `id` | `name|weekday|firstSection|room|weeksText` 拼成的稳定标识，Compose 列表 key 与配色都用它 |

`place()` 优先返回教室、退化为教学楼，避免把「经管楼」重复显示两次。

### Timetable

一次抓取得到的完整快照。**关键设计：周次不依赖开学日期。**

```kotlin
val currentWeek: Int        // 基准时刻（fetchedAt）所在的教学周
val fetchedAt: Long         // 基准时刻
fun weekAt(date: LocalDate): Int =
    (currentWeek + weeksBetween(base, date)).coerceAtLeast(1)
```

`weeksBetween` 把两天都归一到所在周的周一再求差，因此跨周自然会 +1，离线放几周也照样准；结果只保证 ≥ 1，**不按 `weekCount` 截断** —— 手动把本周设成第 25 周，下周就该是第 26 周。

`weekSource` 记录这个周次是谁说的（教务系统 / 按上次推算 / 手动校正 / 导入文件），设置页据此给出解释文案。

### Settings

`Settings` 是只读快照，写入一律走 `Prefs`。含 `dataMode`（在线 / 本地）、校区预设与逐节时间、自动获取时刻（以「当天第几分钟」表示，07:30 → 450）、提醒开关与提前分钟数。

---

## 4. 教务系统接口

湖南强智科技 `jsxsd`，域名 `https://edu-admin.zhku.edu.cn`。

| 常量 | 值 |
|---|---|
| 主页 | `/jsxsd/framework/xsMain.htmlx` |
| 课表 | `/jsxsd/xskb/xskb_list.do?Ves632DSdyV=NEW_XSD_PYGL` |
| 默认 `kbjcmsid` | `3831EE04599D452CBFD0532E01DE52BA` |
| 超时 | connect 15s / read 25s |
| 重定向 | `instanceFollowRedirects = false`，手动跟最多 3 跳 |

### 登录四步

1. `GET /` —— 建立初始会话
2. `GET /Logon.do?method=logon&flag=sess` —— 返回 `scode#sxh`
3. 用 `scode` / `sxh` 混淆出 `encoded`
4. `POST /Logon.do?method=logon`，`userPassword` 传空、`encoded` 传上一步结果 → 302 到主页，跟过去拿 `/jsxsd` 路径下的会话 Cookie

### 混淆算法

```kotlin
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
```

即前 20 个字符逐个输出、每个字符后插入 `scode` 头部 `sxh[i]` 个字符并消耗掉，剩余原样拼接。

### 会话必须复用（最容易踩的坑）

`scode/sxh` **存在服务端会话里**，第 4 步必须带同一个 `JSESSIONID`。如果每个请求各建一条连接、各自开会话，服务端在第 4 步找不到密钥，只会回一句「该帐号不存在或密码错误」——哪怕账号密码完全正确。

因此 `ZhkuClient` 内部维护一个 `LinkedHashMap` Cookie 罐：

- `JSESSIONID` —— 登录密钥与登录态都挂在它上面，丢了必然「密码错误」
- `SERVERID` —— 负载均衡的粘性会话，丢了会被分到没有登录态的节点，表现为「登录成功又跳回登录页」

`login()` 开头会 `jar.clear()` 换新会话（旧会话里的密钥已作废）。

### 区分「密码错」和「会话没保持」

会话失效没有任何状态码信号（永远 200），只能靠页面特征判断：

| 现象 | 判定 |
|---|---|
| POST 返回登录页正文，或 `Location` 仍指向 `Logon.do` | 认证失败，取页面 `#showMsg` 文案 |
| POST 看似成功但回主页仍要求登录 | **会话未保持**，提示「登录没有生效（会话未能保持），请重试」而不是甩一句密码错误 |
| 课表页正文像登录页 | 会话过期 → 用保存的密码自动重登一次再重试 |

`load()` 的顺序是：复用缓存 Cookie → `sessionValid()` 探一次主页 → 无效则登录 → 抓课表；抓课表若被判 AUTH 且有密码，再重登一次重试（只重试一次）。

### 编码探测

强智的页面编码不稳定。`decode()` 依次尝试：`Content-Type` 声明 → 正文前 2048 字节的 `charset=` → UTF-8 → GBK → GB18030，取第一个不含 `\uFFFD` 的结果。

---

## 5. 课表解析

`TimetableParser.parse(html)` 从服务端渲染的 HTML 中取出：

- 课程块：`table#timetable` 内每个 `td` 的课程名、教师、地点、周次文本、节次
- 学期：`xnxq01id` 下拉的选中项
- 当前周：`zc` 下拉的选中项（服务端帮我们标好的那一周）
- `kbjcmsid`：课表模板 id，下次抓取沿用

解析是「正则 + 切片」而非 DOM：页面结构几十年没变，正则更快；同时所有取值都做了空值与越界兜底，某一格结构异常不会让整张课表失败。

`looksLikeLogin(html)` 是判定「这是不是登录页」的唯一依据，登录校验、会话校验、抓取校验都复用它。

---

## 6. 教学周

周次只有一个基准：「某个时刻是第几周」，其余日期按自然周算差值。

| 场景 | 周次来源 |
|---|---|
| 在线获取 | 课表页 `zc` 下拉中被选中的那一周，每次抓取重新校准（`WeekSource.SERVER`） |
| 在线但服务端没标周次（停在「全部」） | 用上次周次按自然周推算（`ESTIMATED`） |
| 本地导入 | 导入确认框里指定的那一周（`IMPORT`） |
| 手动校正 | 设置 →「教学周」或课表页标题下拉 →「校正」（`MANUAL`） |

手动校正会同时把 `fetchedAt` 换成当前时刻，之后照常自然递增。在线模式下手动校正只影响本地推算，下次成功获取仍以教务系统为准。

---

## 7. 节次时间

取自教务处《课堂教学时间及课间休息时间表》，存为 `List<SectionTime>`，可在设置里逐节微调。

| 节次 | 白云校区 | 海珠校区 |
|---|---|---|
| 1 | 08:40–09:20 | 08:00–08:40 |
| 2 | 09:30–10:10 | 08:50–09:30 |
| 3 | 10:30–11:10 | 09:50–10:30 |
| 4 | 11:20–12:00 | 10:40–11:20 |
| 5 | （无，占位 12:10–12:50） | 11:30–12:10 |
| 6 | 13:30–14:10 | 14:30–15:10 |
| 7 | 14:20–15:00 | 15:20–16:00 |
| 8 | 15:20–16:00 | 16:20–17:00 |
| 9 | 16:10–16:50 | 17:10–17:50 |
| 10 | 19:00–19:40 | 19:30–20:10 |
| 11 | 19:50–20:30 | 20:20–21:00 |
| 12 | 20:40–21:20 | 21:10–21:50 |

白云校区第 5 节实际没有排课，为了保持「第 N 节」与服务端节次号对齐，用 12:10–12:50 占位。课表视图按真实时间比例摆块，所以这个占位不会影响排版。

---

## 8. 本地模式（免登录）

`LocalImport.parse(raw)` 的兼容策略：

- `courses[].weeks`：字符串 `"5,7-18(周)"` 或整数数组 `[5,7,8,...]` 都收
- `courses[].sections`：`[1,2]` 或 `"1,2"` 都收
- `teacher` / `room` / `building` / `klass` 缺失不影响导入
- `weekdays` 与 `params.zc` 用于推导周次基准

格式与 `zhku_schedule.py -o schedule.json` 完全一致，也与本应用「导出课表 JSON」互逆（`LocalImport.encode`）。

**导入时必须让用户确认周次**：JSON 里只有导出那一刻的周次、没有导出日期，这是唯一无法自证的信息。`parse()` 不落盘，先返回 `PendingImport`，用户确认周次后才 `commitLocal()` 写入并把 `fetchedAt` 设为当前时刻。

---

## 9. 存储

单一 DataStore：`preferencesDataStore(name = "zhku_schedule")`，以 `Context` 扩展属性实现进程内单例。

| 键 | 内容 |
|---|---|
| `account` / `password` / `cookie` | 凭据与会话 |
| `timetable_json` | 课表快照（kotlinx.serialization 序列化） |
| `campus` / `sections` | 校区与节次时间 |
| `data_mode` | `ONLINE` / `LOCAL` |
| 其余 | 自动获取开关与时刻、提醒开关与提前量、最近一次成功 / 失败时间 |

所有写盘统一收敛到一个内部函数：

```kotlin
private suspend fun write(block: suspend (MutablePreferences) -> Unit) {
    appContext.dataStore.edit(block)
    ScheduleWidget.requestRefresh(appContext)   // 写盘即刷新小组件
}
```

这样「登录、抓取、导入、改节次、退出登录」这些路径不需要各自记得去刷新桌面小组件 —— 漏一处就会出现「课表变了、小组件还停在旧课」的问题。

---

## 10. 后台任务与提醒

### 每日获取：一次性任务自续排

`DailyFetchWorker` 执行完自己的活后调用 `DailyWork.scheduleNext()` 排下一次，链式自我延续。

不用 `PeriodicWorkRequest` 的原因：它只能保证最小间隔，不能保证落在指定钟点（7:30 就是 7:30）。

- 唯一任务名 `zhku_daily_task` + `ExistingWorkPolicy.REPLACE`，避免重复排队
- 约束 `NetworkType.CONNECTED`
- 失败 `Result.retry()`（`runAttemptCount < 2`），退避 `EXPONENTIAL` 从 30 分钟起
- 即使关闭自动获取，这个任务仍会执行 —— 它同时负责续排上课提醒

### 上课提醒

`ClassNotifier.reschedule()` 按课表与提前分钟数排 `AlarmManager` 精确闹钟，`ClassAlarmReceiver` 收到后发通知。Android 12+ 没有 `SCHEDULE_EXACT_ALARM` 时降级为非精确。

### 开机 / 改时间

`BootReceiver` 监听 `BOOT_COMPLETED`、`TIME_SET`、`TIMEZONE_CHANGED`、`MY_PACKAGE_REPLACED`，重新排每日任务、上课提醒与小组件状态闹钟。

---

## 11. 桌面小组件

固定 2×2（`minWidth/minHeight = 110dp`、`resizeMode = none`、`targetCellWidth/Height = 2`），大约 110dp 见方。

### 状态机

`WidgetState.compute(timetable, sections, now)` 是纯函数，可直接单测：

| phase | 触发条件 | 倒计时 |
|---|---|---|
| `EMPTY` | 还没有课表 | 无 |
| `IN_CLASS` | `ScheduleMath.current()` 命中 | 「还剩」到下课 |
| `UPCOMING` | 21 天内有下一节课 | 「还有」到上课 |
| `IDLE` | 21 天内都没课 | 无（避免出现几十小时的倒计时） |

同时给出：

- `countdownText(now)` —— 只到分钟的文案（`还有 1 小时 20 分`），向上取整，剩 30 秒也显示「1 分」
- `nextChange` —— 状态会变的时刻（上课、下课、跨天三者取最近）
- `nextRefreshAt(now)` —— 下一次需要重绘的时刻，取 `nextChange` 与**下一个整分**中更早的那个

### 「准时」怎么做到

倒计时只显示到分钟，所以刷新分两类闹钟：

1. **状态切换**：`nextChange` 那一刻用 `setExactAndAllowWhileIdle(RTC_WAKEUP, ...)` 唤醒一次；
   Android 14+ 无精确闹钟权限时退化为 `setAndAllowWhileIdle`，只影响状态切换的时刻。
2. **整分刷新**：有倒计时时才排，落在下一个整分，用普通 `set(RTC_WAKEUP, ...)` ——
   不进 Doze 白名单，屏幕亮着时基本准点，灭屏被合批，屏幕一亮就补上，
   不值得为它每分钟唤醒 CPU。
3. 兜底 `updatePeriodMillis = 1800000`（30 分钟），闹钟被系统清掉也不会一直停在旧状态。

两类闹钟用不同的 `requestCode`（1001 / 1003），避免共用 `PendingIntent` 互相覆盖。
文本是算好的静态文案，直接 `setTextViewText`，不依赖桌面能不能跑 `Chronometer`
（1.4.0 及之前用 Chronometer 做逐秒跳动，1.5.0 起改为分钟级静态文案）。

### 2×2 的排版约束

110dp 塞四行是极限，基准字号（110dp 盒子下）：

| 行 | 字号 | 约束 |
|---|---|---|
| 状态（`下一节课 · 今天`） | 10sp | 单行 |
| 课程名 | 13sp | 最多两行，`ellipsize = end` |
| 时间 · 地点 | 9.5sp | 单行 |
| 倒计时 | 11sp | 单行，超出会省略 |

**字号不能交给 autoSize。** 早期版本把课程名写成 `autoSizeTextType="uniform"`（上限 18sp）
并给 `layout_weight = 1` 吸收剩余高度，思路是「小盒子缩字、大盒子不变」。实际相反：
部分国产桌面（HyperOS 等）会把 2×2 渲染到 150dp 以上，`layout_weight` 把多出来的高度
全给了课程名那一行，autoSize 就一路顶到 18sp，课程名比状态行大好几倍 —— 用户看到的就是
「字体比例不正常」。

现在的做法：

- XML 里写 110dp 基准字号，`ScheduleWidget.buildViews(context, state, now, scale)` 用
  `RemoteViews.setTextViewTextSize()` 按 `scale` 统一放大四行字号，内边距同步缩放
- `scale` 由 `scaleFor(manager, id)` 用 `getAppWidgetOptions(id)` 的
  `OPTION_APPWIDGET_MIN_WIDTH/HEIGHT` 算出：`短边 / 110`，夹在 `1.0..1.6`
- 比例因此恒定：课程名与状态行永远是 `13 : 10`，盒子变大只是整体变大，不会只肥一行

`WidgetScreenshotTest` 会断言每个控件都在 110dp 边界内、倒计时不被截断，
并在 110dp / 160dp 两种尺寸下断言 `课程名/状态行` 的字号比一致 —— 防止再次引入
「跟着盒子吃高度」的写法。

### 添加方式：绕过系统的小组件列表

`WidgetPin.request()` 用的不是"打开小组件选择器"（系统没有这个 API），而是 Android 8.0 的
`AppWidgetManager.requestPinAppWidget()`：由系统直接弹「是否将小组件添加到桌面」，
用户确认即固定，完全不经过列表。这条路径对小米 / HyperOS 特别有用 ——
它们的「添加工具」列表里不一定列出第三方应用。

入口只有长按图标的静态快捷方式：`res/xml/shortcuts.xml`，action
`cn.edu.zhku.schedule.action.ADD_WIDGET`；`MainActivity` 消费该 intent
（`onCreate` 只在首次创建时消费，避免旋转重建重复弹窗）后交给 Compose。

> 1.4.0 曾在设置页放过一个「添加桌面小组件」入口，1.5.0 按反馈移除 ——
> 设置页只放配置项，添加小组件统一走桌面长按。

桌面不支持时 `isRequestPinAppWidgetSupported()` 返回 false，回退到说明手动路径的对话框。

配套还有两处兼容性细节：

- `widget_schedule_info.xml` 同时声明 `previewImage`（静态 PNG）与 `previewLayout`（API 31+）——部分国产桌面只认前者，缺了会显示空白预览
- `widget_label` 带上应用名（`仲恺课表 · 最近一节课`），便于在系统列表里按「仲恺」搜到

### 刷新触发点

- 任何 `Prefs.write()`（登录 / 抓取 / 导入 / 改节次 / 退出）
- `WidgetAlarmReceiver` 到点
- `AppWidgetProvider.onUpdate` / `onEnabled`
- `BootReceiver`（开机、改时间）
- 30 分钟系统兜底

---

## 12. 构建

```bash
# 调试包
./gradlew assembleDebug

# 发布包（复用 debug 签名，见下）
./gradlew assembleRelease

# 单元测试 + 界面截图
./gradlew testDebugUnitTest
```

需要 JDK 17 与 Android SDK Platform 36。

**签名**：`release` 复用 `signingConfigs.getByName("debug")`，CI 因此不需要注入 keystore，`assembleRelease` 开箱可用。代价是 APK 没有正式证书，只能个人侧载；上架应用商店需替换为正式 keystore。

**不做代码混淆**：`isMinifyEnabled = false`。项目大量依赖反射（kotlinx.serialization、DataStore），关掉 R8 换取「装上去就能跑」的确定性，体积差异只有几 MB。

---

## 13. CI 与发版

### 两个工作流

| 文件 | 触发 | 内容 |
|---|---|---|
| `.github/workflows/build-release.yml` | push `main`、push `v*`、PR、手动 | `assembleRelease` → 按 `versionName` 重命名 APK → 上传 artifact；打 `v*` 标签时额外建 Release 并附 APK |
| `.github/workflows/ci.yml` | push `main`、PR、手动 | `testDebugUnitTest`，上传截图与测试报告，失败用例打成 annotation |

两个工作流都用 `gradle/actions/setup-gradle` 提供的 `gradle` 而非 `./gradlew`：wrapper 的 `distributionUrl` 指向腾讯云镜像（为国内网络优化），海外 runner 上反而慢。

### 发版步骤

```bash
# 1. 改版本号（versionCode 必须递增）
#    app/build.gradle.kts: versionCode / versionName
# 2. 提交
git commit -am "release: 1.3.0"
# 3. 打标签并推送（顺序重要：标签触发的是该 commit 上的工作流）
git tag -a v1.3.0 -m "仲恺课表 1.3.0"
git push origin main && git push origin v1.3.0
```

Release 正文模板写在 `build-release.yml` 里，末尾由 `generate_release_notes` 自动附 Full Changelog。

### 两个坑

**Robolectric 在 runner 上拉不到 android-all**：Robolectric 默认在测试进程内自己下载 `android-all-instrumented`，CI 上会被限流（`Failed to fetch maven artifact`）。改为让 Gradle 正常解析、测试走本地目录：

```kotlin
val robolectricSdk34 by configurations.creating { isTransitive = false }
val robolectricSdk35 by configurations.creating { isTransitive = false }
// 拷到 build/robolectric-deps，测试设 robolectric.offline=true + robolectric.dependency.dir
```

必须拆成**两个** configuration：两个包同属 `org.robolectric:android-all-instrumented`，放在同一个 configuration 里 Gradle 会按版本冲突只保留最高的 35，导致 `14-*.jar` 根本不进目录，报 `Path is not a file`。两个都需要：34 是 `@Config(sdk = [34])` 的测试沙箱，35 是解析 manifest / 资源时用的 compile SDK（targetSdk 36 超出 4.14.1 支持上限，它取 35）。

**ubuntu-latest 预装了 platform 36 / build-tools 36.0.0，但 `sdkmanager` 不在 PATH**：工作流里不能直接调 `sdkmanager`，用绝对路径 `$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager` 兜底接受 license。

---

## 14. 测试

57 个用例，全部不需要账号、不需要真机、不联网。

| 测试类 | 数量 | 内容 |
|---|---|---|
| `TimetableParserTest` | 8 | 课表 HTML 解析：常规、跨节、无地点、异常单元格 |
| `ZhkuClientLoginTest` | 7 | 混淆算法、会话复用、错误分类（密码错 / 会话丢失） |
| `LocalImportTest` | 7 | JSON 兼容：字符串/数组周次、缺字段、导出往返 |
| `WeekMathTest` + `TimetableWeekTest` + `ScheduleMathTest` | 12 | 周次推算、跨年跨学期、今天/下一节 |
| `LocalModeStoreTest` | 5 | 本地模式写入与切换 |
| `ScreenshotTest` | 7 | 首页 / 课表 / 设置 / 登录 / 深色的真实渲染截图 |
| `WidgetStateTest` | 6 | 小组件四种状态 + `nextChange` 时刻 |
| `WidgetScreenshotTest` | 3 | 2×2 浅色 / 深色渲染、控件不越界、倒计时不被截断、两种尺寸下字号比一致 |

界面截图走 Robolectric `@GraphicsMode(NATIVE)` + Compose UI Test，输出到 `app/build/screenshots/`，CI 作为 artifact 上传。仓库里的 `screenshots/` 就是从这些产物里挑出来提交的。

---

## 15. 隐私与安全

- 学号、密码、课表快照只写在应用私有目录（DataStore），不上传任何第三方服务器，也没有统计 / 埋点 SDK
- 网络请求只发往 `edu-admin.zhku.edu.cn`
- `android:allowBackup="false"`，避免凭据随云备份外泄
- 本地模式完全不联网
- 密码以明文存于应用私有目录（未加密）：这是「自动续期会话」的代价。设备已 root 或备份被导出时存在风险，介意可在用完后退出登录（会清除凭据）

---

## 16. 已知限制

- `JSESSIONID` 是会话 Cookie，失效后需重新登录；应用保存了密码，因此能自动续期
- 强智单点登录会挤掉浏览器端会话，反之亦然
- 「白云校区时间节次」在服务端返回空表（与 Python 版一致），所以只用「默认校区时间节次」
- 本地课表没有导出日期，跨学期沿用同一个文件会不准，用「当前第 N 周」校正或重新导入
- 小组件在无精确闹钟权限时，状态切换最多延迟几分钟；倒计时每分钟刷新，灭屏进 Doze 后可能慢一拍

---

## 17. 致谢与许可

- 课表抓取逻辑逆向自 [ZHKUClassSchedule](https://github.com/mackiont/ZHKUClassSchedule)，接口与 JSON 格式保持兼容
- 代码主要由 [WorkBuddy](https://www.workbuddy.cn) 辅助开发

本项目是个人学习作品，与仲恺农业工程学院及教务系统厂商无关，非官方客户端。使用时请遵守学校相关规定，仅抓取自己的课表数据，因使用本应用造成的任何后果由使用者自行承担。

[GNU General Public License v3.0](LICENSE)
