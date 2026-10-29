# V1.2.4 稳定性修复报告

版本：versionName `1.2.4`，versionCode `9`。本次不新增产品功能，不增加服务器、统计 SDK 或数据库迁移。

## 三个 P0 的证据与处理

### Bug 1：连接测试成功，但日程解析失败

已确认代码问题：连接测试只验证简单 `ok` 响应，不经过日程 schema 和 mapping。原解析器将包装提取、JSON、字段校验、事件映射异常统一降为 INVALID_CONTENT；单个事件的非法 reminder offset 会抛异常，拖垮整个批次。缺少跨设置页/Smart Add 共享的 pipeline 诊断，无法知道真实用户那一次失败属于哪一步。

修复：不修改业务 Prompt；分别记录 transportSuccess / parseSuccess。支持整个 content、code fence、前缀说明后的第一个完整对象，扫描时识别字符串、转义和嵌套数组/对象。可选字段缺失用默认值；关键字段/日期/时间异常逐事件隔离，保留其他有效日程并显示检查数量。无效可选提醒忽略并提示检查。解析响应有大小、深度和事件数量上限，避免极端输出导致内存/栈问题。

诊断阶段：NETWORK_ERROR、HTTP_ERROR、EMPTY_RESPONSE、EMPTY_CHOICES、EMPTY_CONTENT、CONTENT_EXTRACTION_FAILED、JSON_EXTRACTION_FAILED、JSON_PARSE_FAILED、SCHEMA_VALIDATION_FAILED、EVENT_MAPPING_FAILED、TIME_PARSE_FAILED、DATE_PARSE_FAILED、UNKNOWN_PARSE_ERROR、COMPLETE。

设置页最近诊断显示 HTTP、耗时、Endpoint、Model、请求/解析各自成功与否、阶段、异常类、请求次数。只保存安全诊断，不保留原文、标题、备忘、Key、Authorization 或响应正文。JSON 异常仅取类名，不取可能含响应片段的 message。

仅 429/500/502/503/504、SocketTimeoutException、ConnectException 短暂等待后自动重试一次，最多两次 HTTP 尝试。401/403/404、URL_INVALID、JSON/schema 错误不重试。离页取消会尝试断开底层连接，并禁止过期响应回写。

已执行 20 次连续**模拟**解析格式回归，不是第三方真实模型压测。没有使用真实 Key 请求外部服务；真实服务的失败率/阶段仍需手机端记录。

### Bug 2：Smart Add 顶部遮挡

已确认原布局的 Insets 归属混用：外层 Scaffold 的 padding 又传给整页 Column，内层 TopAppBar 仍采用默认系统栏 Insets；滚动 Column 也没有自己的 Scaffold innerPadding 消费约定及 IME 处理。仅凭代码无法确认各厂商手机上所有遮挡表现的唯一原因。

修复为本页独立 Scaffold：固定 topBar、唯一 LazyColumn，body 只消费一次 innerPadding；不再传入外层 Scaffold padding。使用语义 WindowInsets/imePadding 和 adjustResize，无硬编码生产顶部像素。大量预览不再一次性组合成巨大 Column。

Compose 测试检查模拟状态栏 Insets 下标题/首项完整、TopAppBar 与首项不重叠、滚到底再返回、快速拖动、缩小/恢复视口及所有预期状态有内容。Robolectric 的键盘视口测试是**约束模拟**；真实系统 IME/刘海/旋转仍需设备测试。

### Bug 3：偶发整页白屏

**已增加防护与诊断，但尚未捕获原始白屏根因，不能声称彻底修复。**

确认并保护的风险路径：

- Smart Add 异常未收尾，parsing 可一直为 true；CSV 文件读取原先在主线程回调内。
- DataStore/Room Flow 在 stateIn 前没有错误隔离，部分 ViewModel 初始化和操作协程没有错误 UI。
- 无条件 popBackStack 的重复返回可清空首页；加入路由校验、原路由匹配、首页恢复和可见错误页。测试验证重复返回/空栈恢复。
- 解析、CSV、创建均同步设置忙碌状态，finally 清理；显式 Idle/Parsing/Preview/ParseFailed/Creating/CreateSucceeded 状态，每种都有可见内容。
- 离页取消 + generation 校验，防止旧结果覆盖；SavedState 恢复输入但不自动发请求。超长输入超过 64,000 字符不写入 SavedState Bundle，当前页面内存内容仍完整，避免 Binder 大小异常；该极端输入在进程死亡后不能保证恢复。
- Keystore 初始化延后到 IO 调用；JSON/本地解析在 Default，文件/批量操作在 IO。
- 可恢复异常只捕获 Exception，CancellationException 正常抛出，不吞 Error。Debug 未捕获异常仍结束异常进程，记录类名和源码帧，不用普通 try/catch 包裹 Compose。

## 本地白屏诊断

Debug 专用，Release 不记录本节面包屑。仅固定 route 模板（不含任务 ID/日期/标题）、前一路由、状态类型、时间、生命周期节点、异常 simpleName 和安全栈帧。最近 80 条在本地 SharedPreferences 中保留，不上传，也不写 event_logs。默认 fatal handler 收到脱敏异常，仍保留源码栈和真正异常类名，不输出原始 message/cause。

白屏时**先不要强制关闭或清空数据**，连接电脑：

```powershell
adb devices
adb shell pidof -s com.example.ddlreminder
```

把输出 PID 替换下面示例的 `12345`，开始抓该进程（Ctrl+C 停止）：

```powershell
adb logcat --pid=12345 -v threadtime -s DDLStability:I AndroidRuntime:E > ddl_reminder_stability.txt
```

尽量在复现前开始抓取；进程重启后 PID 会变化。Debug 包还能提取最近本地面包屑（没有用户正文）：

```powershell
adb shell run-as com.example.ddlreminder cat shared_prefs/stability_diagnostics.xml > ddl_reminder_breadcrumbs.xml
```

如表现为卡死而非异常退出，日志没有异常栈也不能视为正常；保留最后 route/state/生命周期和准确发生步骤，继续定位主线程/系统 ANR。分享日志前仍应自行检查脱敏，不发送 API Key 或用户输入/响应正文。不要通过卸载或清空 App 数据“修复”白屏，那会删除 DDL。

## 构建与测试

命令（网络仓库不可达时本次使用现有缓存 `--offline`，不是跳过测试）：

```powershell
.\gradlew.bat testDebugUnitTest --offline
.\gradlew.bat compileDebugAndroidTestKotlin --offline
.\gradlew.bat assembleDebug --offline
.\gradlew.bat lintDebug --offline
# 连接设备/模拟器后执行；无设备时不宣称执行过：
.\gradlew.bat connectedDebugAndroidTest
```

本次实际结果：

- `testDebugUnitTest`：通过，198 项，0 失败、0 错误、0 跳过；相对原 127 项新增 71 项。其中 10 项为 Robolectric Compose 布局测试，键盘视口为模拟约束。
- `assembleDebug`：通过。
- `lintDebug`：通过，0 Error、6 Warning。
- `assembleDebugAndroidTest`：通过，设备测试 APK 完整构建成功。
- `connectedDebugAndroidTest`：未执行；`adb devices -l` 无设备/模拟器。

另新增 3 项设备测试（不计入已通过的 198 项）。设备套件 `SmartAddDeviceStabilityTest` 已提供真实 IME 开/关、30 次上下滑动、20 次首页/Smart Add 往返、Activity recreation、后台/前台测试；编译不等于设备执行。

## 真机发布前压测清单（尚未执行，不能用模拟测试代替）

| 场景 | 操作 | 需记录/检查 |
| --- | --- | --- |
| A | 连续 20 次真实 AI 智能解析 | 成功数、失败数、每次 failure stage、HTTP/耗时；不能出现原因不明失败 |
| B | Smart Add 快速上下滑动 30 次、键盘开关 10 次 | 无顶栏遮挡/半截内容，能回顶部，键盘关闭后恢复；再测旋转、锁屏解锁 |
| C | 首页 → Smart Add → 设置 → 首页 → 编辑 DDL → Smart Add，循环 20 次 | 按实际导航路径返回/进入，不能白屏，旧 DDL/备忘/提醒仍存在 |
| D | AI 请求过程中后台 → 前台，重复 5 次 | 不白屏、不重复请求，结束后状态可见 |
| E | AI 请求过程中返回 → 再进 Smart Add | 旧请求不覆盖新输入/页面；可重新解析 |

这不是“可以上线”的证明。真实服务仍有不明失败、顶部仍遮挡、出现可复现白屏、lint Error、测试/构建失败或 DDL 丢失时，均不能通过发布门槛。

## 文件与数据兼容性

- 解析/诊断：AiApiClient、AiNaturalLanguageParser、AiDiagnostics、ScheduleDraft（仅内存解析结果，不是数据库表）。
- Smart Add：SmartAddViewModel、SmartAddScreen。
- 页面/导航：DdlApp、NavigationSafety、RecoveryPage、Settings/Editor/Calendar ViewModel 及 SettingsScreen。
- 辅助容错：StabilityDiagnostics、MainActivity、DdlReminderApplication、DdlRepository、EventLogger、CsvExportManager、AlarmReceiver/BootReceiver/AndroidAlarmScheduler、AiSettingsRepository。
- Manifest 仅补 adjustResize；Gradle 版本更新和测试依赖；新增回归/设备测试。
- AppDatabase、DdlEntity、ScheduleExtractionPrompt 与修改前文件哈希相同。Room 保持 5，无 destructive migration；旧显式迁移链仍保留。
- 历史 releases APK 不覆盖；本次目标为 `releases/DDL_Reminder_v1.2.4_debug.apk`。
