# 开发与机制说明

[返回项目首页](../README.md)

## 技术与项目结构

- Kotlin、Jetpack Compose、Material 3
- Room 本地数据库
- DataStore Preferences 全局设置
- AlarmManager、BroadcastReceiver、Notification
- 简化 MVVM，不使用 Hilt 或多模块

```text
app/src/main/java/com/example/ddlreminder/
├── data/
│   ├── DdlEntity.kt          Room 表结构
│   ├── DdlDao.kt             数据库操作
│   ├── AppDatabase.kt        Room 数据库入口
│   └── DdlRepository.kt      数据与提醒的组合操作
├── settings/
│   ├── ReminderSettingsRepository.kt  DataStore 全局默认提醒
│   ├── ReminderPresetRepository.kt    内置/自定义提醒方案
│   └── AiSettingsRepository.kt        Keystore 加密 AI 配置
├── smartadd/
│   ├── NaturalLanguageParser.kt       可替换解析接口
│   ├── LocalNaturalLanguageParser.kt  离线中文规则解析
│   ├── FastPathDetector.kt            本地快路径分类
│   ├── TieredNaturalLanguageParser.kt 本地快路径→单一 AI→保守本地回退
│   ├── ParsePerformance.kt            安全耗时诊断
│   ├── ModelSpeedBenchmark.kt         Debug 固定样例主动测速
│   ├── AiNaturalLanguageParser.kt     严格 JSON 校验与 fallback
│   └── ScheduleCsvParser.kt           UTF-8 CSV 解析与逐行校验
├── logging/
│   ├── EventLogEntity.kt     匿名事件表及限定事件名
│   ├── EventLogger.kt        失败隔离、提醒/逾期去重
│   ├── EventCsvExporter.kt   UTF-8 CSV 编码
│   └── CsvExportManager.kt   FileProvider 标准分享
├── reminder/
│   ├── ReminderCalculator.kt 纯 Kotlin 提醒时间计算
│   ├── AndroidAlarmScheduler.kt
│   ├── AlarmReceiver.kt      闹钟触发后校验数据库并通知
│   ├── BootReceiver.kt       开机后恢复提醒
│   └── NotificationHelper.kt
├── ui/
│   ├── calendar/             月历和当天任务列表
│   ├── editor/               新建/编辑页
│   ├── settings/             默认提醒设置页
│   └── theme/
├── DdlReminderApplication.kt 手动组装依赖
└── MainActivity.kt           单 Activity 入口
```

## 提醒机制

保存任务后，`ReminderCalculator` 根据该任务自身保存的分钟 offset 计算时间，并过滤已经过去的结果。`AndroidAlarmScheduler` 以“任务 ID + 截止时间 + offsetMinutes”建立唯一 `PendingIntent`，因此自定义提醒也不会互相覆盖。

有精确闹钟权限时使用 `setExactAndAllowWhileIdle()`；没有时降级为 `setAndAllowWhileIdle()`，因此提醒可能延迟。闹钟触发后，`AlarmReceiver` 不直接相信 Intent，而是重新查询 Room，确认任务存在、未完成、未逾期且截止时间未被编辑，才发送高优先级 Notification。

完成、编辑和删除都会按任务旧截止时间和旧 offset 集合取消全部旧 `PendingIntent`。`BOOT_COMPLETED` 后 `BootReceiver` 查询所有未完成、未逾期任务并重新注册；App 每次启动也执行相同同步。

通知使用频道 `ddl_reminders_v2`，开启震动并使用短促模式 `0, 180, 120, 240 ms`。Android 8+ 的通知频道一旦创建，震动配置不能由代码原地修改，因此升级版本使用了新的 Channel ID。旧频道可能仍显示在系统设置中，但不会再用于新通知。

## 数据与升级

- Room 数据库当前版本为 5。
- `MIGRATION_1_2` 为旧 DDL 增加 `reminderTypesCsv` 字段。
- `MIGRATION_2_3` 只新增独立 `event_logs` 表及索引，不修改或重建 `ddls`。
- `MIGRATION_3_4` 新增 `reminderOffsetsCsv`，把旧枚举无损映射为分钟 offset；旧列、DDL 和 `event_logs` 均保留。
- `MIGRATION_4_5` 为 DDL 增加可空的 `notes` 字段，不重建表，不修改提醒或日志。
- 从 V1.0 升级时 Room 会依次执行 `1 → 2 → 3 → 4 → 5`，已有任务不会丢失。
- 已有任务自动继承 V1.0 的四个提醒：5 天、3 天、1 天、12 小时。
- migration 使用 `ALTER TABLE`，不会删除或重建旧任务表。
- 全局默认提醒保存在 DataStore，只在新建任务或点击“恢复默认设置”时读取。
- 修改全局默认值不会修改已经创建的 DDL。

## 匿名本地日志与 CSV

任务与提醒事件：

```text
ddl_created, ddl_edited, ddl_completed, ddl_restored, ddl_deleted,
reminder_scheduled, reminder_fired, reminder_clicked, ddl_overdue,
app_opened, three_day_item_clicked
```

`reminder_scheduled` 按任务、截止时间和提醒节点去重，启动同步同一个 Alarm 不会反复写日志。`ddl_overdue` 按任务 ID 只记录一次。日志写入异常会在 `EventLogger` 内部隔离，不会阻止 DDL 保存、删除、完成或通知。

设置页的“数据与隐私”区域可以：

- 使用 `FileProvider + ACTION_SEND` 导出 UTF-8 CSV，不申请存储权限。
- 只清空 `event_logs`，不会删除任何 DDL。
- 用户自行选择文件管理器、微信、QQ、邮箱或网盘等接收方式；App 不会后台上传。

智能添加事件仅记录状态、数量、布尔或枚举信息：

```text
smart_add_opened, natural_language_parse_requested,
natural_language_parse_succeeded, natural_language_parse_failed,
voice_input_started, voice_input_succeeded, voice_input_failed,
csv_import_started, csv_import_previewed, csv_import_completed,
batch_ddl_created, custom_reminder_used, reminder_preset_used
```

这些事件不记录任务标题、原始自然语言、语音识别文本或 CSV 内容。

## 智能解析配置与隐私

设置页的“智能解析”区域提供 API 地址、API Key、AI 模型三个配置输入框，以及隐私确认、保存与连接测试。默认模型为 `gpt-6-luna`，可填写中转实际 Model ID；协议不按模型字符串写分支。V1.2.6 升级时按旧 `fastModel` → 旧 `modelName` → 默认值选择，保留 Base URL、Key 和隐私确认。旧 strongModel 仅保留为兼容数据，不参与正常解析。

App 规范配置首尾空白和 Base URL 外层引号、尾部斜杠，并追加 `/chat/completions`，不会自动增加 `/v1`。API Key 正常路径使用 Android Keystore 支撑的加密本地存储，不写入源码、BuildConfig、资源、日志或 CSV；现有 Keystore 初始化失败分支会退回普通 SharedPreferences，不能承诺异常情况下也加密，请使用可撤销、限额的演示 Key。可测试当前模型的连接/耗时；连接成功不等于日程解析成功。

保存和请求前均清理 Base URL、Key、Model 的首尾空白；Base URL 另外去掉一对包裹整个字符串的单/双引号，不替换内部内容。`https://api.example.com/v1` 和末尾带 `/` 的输入都请求 `https://api.example.com/v1/chat/completions`。

“设置 → 高级诊断”中的最近一次 AI 请求显示状态码、真实耗时、最终请求 Endpoint 和具体异常类（如 `UnknownHostException`、`ConnectException`、`SSLHandshakeException`、`SocketTimeoutException`）。URL 验证失败显示 `URL_INVALID`，请求构建阶段失败显示 `REQUEST_BUILD_FAILED` 并附异常类。计时使用单调时钟；已尝试连接但不足 1ms 的请求按 1ms 展示，未知耗时不伪装成 0ms。诊断不保存 API Key、Authorization、用户输入/响应正文或异常 message。

URL 必须包含 http/https scheme 和非空 host；不接受内嵌凭证、query 或 fragment。请求不自动跟随重定向，防止凭证转发并确保显示的 Endpoint 就是请求地址。请填写最终 API 地址。http URL 虽通过格式校验，仍可能被 Android 明文网络策略拒绝；推荐 https，不全局放开明文流量。

只有勾选隐私确认后才会使用网络 AI。每次请求只包含当前用户主动提交的文字、当前本地时间和统一结构化输出提示。AI 失败或达到 30 秒 AI 链路上限后停止网络请求，转保守本地解析并明确提示；外部取消继续传播，不当作 AI 失败回退。所有结果经 Preview 确认才写 Room，原输入保留供修改或重试。

高级诊断还显示最近一次解析方式（LOCAL / AI / LOCAL_FALLBACK）、AI 模型、HTTP 和 JSON 处理耗时、总耗时、网络重试和失败阶段。HTTP 包含中转和模型等待，不能据此拆出模型真实推理耗时。只在同页同输入同配置且 Preview 存在时复用预览；改输入/离页失效，不保存持久 AI 缓存。

ModelSpeedBenchmark 为独立开发工具，设置页没有测速入口。相关代码和测试仍保留。AI 临时网络失败使用同一模型最多重试一次。Smart Add 解析期间显示圆形进度动画与“正在识别日程…”，约 1.5 秒后显示“正在理解通知内容…”，失败后恢复操作，保留输入和固定顶栏。

## 测试

单元测试及 Robolectric UI 测试主要覆盖：

- 提醒计算、取消与重建、未来 72 小时筛选、日期切换和逾期状态。
- Room 显式迁移、备忘保存及日志与任务的隔离。
- AI 请求诊断、JSON 提取、逐事件校验、本地回退、有限重试与协程取消。
- Smart Add 状态、滚动布局、页面恢复，以及设置页的模型配置和高级诊断。
- CSV 转义、匿名日志内容与去重、测速超时和生命周期。

本地执行：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
```

依赖已缓存时可追加 `--offline`。设备测试需连接手机或模拟器后运行 `connectedDebugAndroidTest`。自动化测试使用模拟网络响应，不能测出真实模型准确率；键盘适配和通知投递仍需实机验证。

发布前检查：覆盖安装后已有 DDL 和设置保留；手动添加、智能解析和取消可用；高级诊断可展开与收起；开机后提醒能够恢复。Debug 首页的“1 分钟后测试通知”可用于检查系统通知及 AlarmManager。

## 故障排查

“设置 → 高级诊断”保留最近请求的 HTTP 状态、Endpoint、耗时、解析阶段及异常类。HTTP 成功与解析成功分别记录；诊断不保存 API Key、Authorization、正文或完整响应。收起面板不会删除诊断记录。

Debug 构建通过 `DDLStability` 标签记录路由、UI 状态、生命周期和异常类；最近 80 条保存在设备本地。记录只接收固定字段，不接收任务标题、备忘、输入正文或凭证。

连接设备后取得进程 PID：

```powershell
adb shell pidof -s com.example.ddlreminder
```

将输出替换下面的 `12345`，在复现前开始抓取；按 Ctrl+C 停止：

```powershell
adb logcat --pid=12345 -v threadtime -s DDLStability:I AndroidRuntime:E > ddl_reminder_stability.txt
adb shell run-as com.example.ddlreminder cat shared_prefs/stability_diagnostics.xml > ddl_reminder_breadcrumbs.xml
```

进程重启后 PID 会变化。遇到页面无响应时，请保留操作步骤、发生时间及前后台切换情况；日志没有异常栈不代表未发生卡死。提交 Issue 前检查日志脱敏，不要通过卸载或清除数据排查，以免丢失任务。

## 发布与兼容

应用标识为 `com.example.ddlreminder`，签名与 applicationId 一致时可覆盖安装。改名应区分 Kotlin 包名与应用标识，不能为了调整源码目录而改变已发布 App 的安装身份。

每个安装包使用独立版本号，归档名为 `DDL_Reminder_v<versionName>_debug.apk`。三项 Gradle 检查通过后复制到 `releases/`；目标文件已存在时停止，不覆盖旧包。默认构建产物不自动归档。使用 `apksigner verify --print-certs` 对比证书，并生成 SHA-256 校验值。

数据库结构变化时递增 Room version 并增加显式 Migration；仅 UI 或文档更新不改变数据库版本。AI 配置的旧字段及匿名日志 metadata 键属于兼容格式，不随局部变量清理而改名。

旧版技术记录位于 [history/](history/README.md)。后续可改进本地任务备份、系统时间变化同步、搜索筛选及实机测试覆盖。
