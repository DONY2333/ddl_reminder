# V1.2.5 解析路由与性能报告

> 历史记录：以下内容描述该版本发布时的实现和验证结果。当前行为见[项目首页](../../README.md)，版本演进见[版本记录](../../CHANGELOG.md)。文中代码及 APK 路径均相对仓库根目录。

版本 `1.2.5` / versionCode `10`。主要调整本地快路径与双模型路由，Room 版本为 **5**，无新增迁移。

## 路由与配置

`FastPathDetector` 只分类 LOCAL / AI，不计算日期、不生成事件。采用白名单结构：开头单个今天/明天/后天/星期/月日表达、可选明确时间、单个常见事件词及简短标题；检查单日期、至多单时间、单事件线索。不是只按文本长度判断。

军理长通知、多个事件、取消/改期/延期/暂定、群公告、聊天记录、标点分句、说明、提醒、时长/题型/地点、并列和上下文依赖等交 AI。仅“上午”无具体时间不走快路径。本地候选仍须通过现有 Parser 校验，非法日期、非法具体时间不能被误认为快路径成功。

以下类型命中本地：

- 明天下午3点开班会。
- 后天晚上8点交高数作业。
- 10月25日小王生日（日期明确、无时间，保持原 23:59 默认及警告）。
- 周五23:59交论文。
- 下周一上午9点体测。

这些示例对应的测试输入不含句末句号；额外标点可能使文本转入 AI 路由。

本地成功零 HTTP；否则复杂文本先 Fast。输出只要有可靠日程（包括需人工确认具体时间的时间段），就不再请求 Strong。所有 AI 调用严格串行。

设置页可配置 API Base URL、API Key、快速解析模型和高级兜底模型。请求使用填写的模型 ID，仅去掉首尾空白；界面提示不作为保存值。兼容协议的其他模型可直接通过配置切换。

旧加密 SharedPreferences 的 `model` 在 `fast_model` / `strong_model` key 缺失时兼容为两者；Base URL / Key / 隐私确认不变。保存后两个 key 独立，显式空值不回填。旧 `model` 保留为单次 HTTP 调用和兼容字段；该版本不涉及 Room 迁移。升级后如仍使用同一旧模型，复杂文本不会自动变快，需要手动配置真正更快的模型。

## Fast → Strong 与重试

允许升级：不可恢复 JSON / 内容 / schema / 关键日期时间 / 全部映射失败；明确有日程线索但无任何事件；中转明确的 model not found / model unavailable（可含 HTTP 404）；429/500/502/503/504 最终失败；SocketTimeoutException 最终失败。只有 404 状态、没有模型错误证据时，可能是 Endpoint 配错，不盲目升级。

禁止盲目升级：401/403、URL_INVALID、REQUEST_BUILD_FAILED、UnknownHostException、ConnectException、SSLHandshakeException、SSLException、SecurityException 等整体配置/连接问题。普通未知网络错误也不升级。相同 Fast/Strong ID 不重复换模型调用。

code fence、少量说明、可选字段缺失先使用 V1.2.4 extraction/defaults 容错；部分事件可用不升级。notes/warnings 为空不是失败理由。双方均失败后保守本地 fallback，保留明确提示；结果不会自动存入 Room。

同模型网络 retry 继续只有一次：429/500/502/503/504、SocketTimeoutException、ConnectException；401/403/404、JSON/schema 不进行同模型 retry。最多 Fast 两次、Strong 两次，且受同一个 **30 秒解析链路总超时**限制。配置读取、预览默认提醒/重复校验是本地准备工作，不计入 HTTP/JSON 计时；不可响应取消的底层厂商网络实现仍需设备观察。超时停止、不再 fallback、显示错误并保留输入；离页取消正常传播，不生成旧页面结果。

## Prompt 和输出参数

Fast/Strong 共享 `ScheduleExtractionPrompt`，业务规则与输出字段一致；附当前本地时间和设备时区。保留整段理解、补充信息进 notes、取消/改期/暂定、不得编造日期、时间段确认、默认 23:59、简短标题和 JSON-only 规则。

Prompt 长度按 Kotlin `text.length`（去缩进、LF 换行，**不是 token 数**）从 **780 → 635 字符**，减少约 **18.6%**。压缩的是说明文字，不删除产品字段，不包含 Room/日志/Android/重试等实现细节，不要求 reasoning/originalText 等冗余输出。

未连接真实中转验证，因此默认**不发送**可能不兼容的 `max_tokens` / `max_completion_tokens`。`HttpAiApiClient(maxOutputTokens = 1000)` 是仅供已验证兼容集成启用的可选参数，上限 1200；当前 App 装配未启用。该参数需在确认服务兼容后启用。temperature 保持 0。输出仍有原有响应大小/深度/事件数量安全上限。

## 缓存、状态、耗时与隐私

- 同页、完全相同输入、配置和解析模式，且上次成功 Preview 仍存在：复用当前编辑过的 Preview，不发 HTTP。只在内存保留去重 key，不写文本/响应缓存。
- 修改输入（即使再改回）、离页、CSV 导入、配置改变时失效。失败/超时不缓存，重试可重新请求。
- 不因 recomposition、恢复 Activity 或 Preview 刷新自动请求；正在解析时同步 busy guard 禁止连点；Loading 文字“正在识别日程…”保持可见且可返回。
- 保留 V1.2.4 显式状态、离页取消/代次校验、SavedState 输入恢复、单滚动容器/Insets、NavigationSafety、RecoveryPage、StabilityDiagnostics 和 CancellationException 传播。
- 最近解析诊断是进程内结构化值：routeUsed（LOCAL/FAST_AI/STRONG_AI/FAST_THEN_STRONG/LOCAL_FALLBACK）、实际使用模型、两阶段各自 HTTP/JSON 耗时、总链路耗时、已完成请求网络 retry 数、fallback、事件数和超时。正在进行即被取消的请求不伪造耗时，标为未完成。
- HTTP 计时包含 DNS/TCP/TLS、服务端排队、中转、模型等待和重试 delay；**不能单凭本机计时拆开网络与模型推理**。JSON 计时独立。total 是 Parser 路由链路，非 UI 点击至绘制的端到端帧耗时。
- EventLogger 复用已有事件，只增固定枚举/计数/布尔：parse_route、latency_bucket、fast_model_success、strong_fallback_used、event_count；不记模型 ID、用户输入、标题、notes、Key、Authorization 或响应正文。

## Debug Benchmark

设置 → 智能解析 → 配置两个实际模型 ID、确认隐私 → “模型速度测试（Debug）” → 确认。

使用固定“明天下午3点开会”和模拟学校通知；**两个模型每样例各 3 次，共 12 次串行逻辑调用**，网络 retry 另计，可能付费。直接请求指定模型，不经过快路径/页面缓存，以便公平比较。显示每组成功/3、平均、最快、最慢，耗时统计包含失败请求。成功指结构化结果可用，不代表准确率已人工验收。不读用户 DDL，不保存模型 response。

返回离开设置会取消所属 ViewModel 工作；后台切换并非主动离页，不自动重新开始。Release 没有测速按钮，普通解析只由用户主动操作触发。

## 自动化与设备验证

新增 FastPathDetector、TieredParser、双模型配置兼容、Settings 双模型连接、Benchmark 固定输入、页面缓存失效测试。所有 V1.2.4 回归（Insets、Activity 重建、取消/旧请求、导航、错误状态等）继续运行。

军理原始长通知在固定 2026-10-15 的回归时钟下，仍是一个军事理论课考试、10月17日、上午、需确认时间，notes 保留 A/B卷、闭卷、1小时和题型；本地语义回归和模拟 AI 路由回归均需通过。该回归使用模拟响应，未测量真实模型准确率。

执行命令（本机使用缓存 `--offline`）：

```powershell
.\gradlew.bat testDebugUnitTest --offline
.\gradlew.bat assembleDebug --offline
.\gradlew.bat lintDebug --offline
# 有设备时执行：
.\gradlew.bat connectedDebugAndroidTest
```

验证结果：

| 检查 | 实际结果 |
| --- | --- |
| testDebugUnitTest | 265 项通过，新增 67 项，0 失败/错误/跳过 |
| FastPathDetector | 17 项通过；本地候选、拒绝复杂通知、零 HTTP、10 次本地模拟计时 |
| TieredParser | 35 项通过；路由、重试、鉴权/配置阻断、模型不可用、超时、取消、Prompt、诊断、Benchmark 等 |
| 旧军理/中文语义回归 V121ParserRegression | 9 项通过，军理仍单事件、时间段确认、完整备忘 |
| 双模型存储兼容 | 5 项通过；旧配置、双模型、显式清空和稳定保存 |
| Settings 双模型测试 | 4 项通过 |
| 页面状态/缓存 | 21 项通过，含新增 6 项页面缓存测试 |
| assembleDebug | BUILD SUCCESSFUL |
| lintDebug | BUILD SUCCESSFUL，0 Error、6 Warning |
| assembleDebugAndroidTest | BUILD SUCCESSFUL；只构建设备测试 APK，不等于执行设备测试 |
| connectedDebugAndroidTest | 无设备/模拟器，未执行 |

纯 JVM 的 10 次 LOCAL Parser 样本：整数毫秒显示 P50=0ms（即不足 1ms）、最大 2ms；不是手机点击至 Preview 的端到端耗时，也不是冷启动/真实 API benchmark。

验证未连接设备，也未请求真实中转；真实 AI 延迟、真机性能及白屏根因不在该验证结果内。诊断方法见 [V1.2.4 稳定性报告](STABILITY_V1.2.4.md)。

## 真机性能验收表（待执行）

| 项目 | 操作 | 记录与验收 |
| --- | --- | --- |
| A | 简单句“明天下午3点开班会”10次 | 每次离开再进入或修改输入使缓存失效；全部 LOCAL、零 HTTP，记录 P50/最大耗时；目标 P50<100ms、尽量<300ms |
| B | 军理长通知，Fast 配置下10次 | 每次新页面，检查标题/日期/上午确认/完整备忘；记录成功数、平均/最快/最慢、JSON成功率、Strong fallback率。若日期已过去，应确认逾期警告，不允许模型编造新日期 |
| C | 清空 Fast，仅配置 Strong，同一通知5次 | 新页面逐次请求，对比同样的语义准确率和耗时；结束恢复原配置 |
| D | Fast 改为中转提供的 Gemini Flash-Lite实际 ID，同批通知10次 | 对比准确率、平均/最大耗时、JSON成功率、fallback率；不依品牌改规则 |
| E | 打开 Debug 固定样例测速 | 两模型每样例3次，无并发；比较各自平均/最快/最慢和成功数 |
| 稳定性 | 30次快速滚动、10次键盘开关、20轮页面切换、请求中后台/返回 | 无顶部遮挡/白屏/旧结果覆盖；检查已有DDL与提醒不变 |

同一页面点击相同输入会复用 Preview，不能把缓存命中当成第二次 HTTP 性能样本。真实性能受第三方中转与所选模型影响，待以上表格实测后才能确认达成目标。

## APK 归档

- 默认：`app/build/outputs/apk/debug/app-debug.apk`（相对项目根目录）
- 归档：`releases/DDL_Reminder_v1.2.5_debug.apk`
- 两份 SHA-256：`FF72C4C58DE116FB25FC097F7619EFC8866FDFE8E8ED26B87CD11BB761ABAABA`
- 已核对 APK versionName 1.2.5 / versionCode 10，与 V1.2.4 调试签名相同。历史 APK 均保留；数据库实体与升级前哈希一致，支持覆盖安装保留 DDL/备忘/本地设置。

```powershell
adb install -r ".\releases\DDL_Reminder_v1.2.5_debug.apk"
```

不要先卸载或清空数据。

## 核心改动文件

- `settings/AiSettingsRepository.kt`：兼容旧配置、双模型保存。
- `smartadd/FastPathDetector.kt`、`TieredNaturalLanguageParser.kt`：分类与串行路由/总超时。
- `smartadd/AiApiClient.kt`、`AiNaturalLanguageParser.kt`：模型不可用布尔类别、可选 token 参数、JSON 耗时；保留阶段诊断和网络 retry。
- `smartadd/ParsePerformance.kt`、`AiDiagnostics.kt`、`ScheduleDraft.kt`：内存诊断与结果附加字段（非 Room）。
- `smartadd/ScheduleExtractionPrompt.kt`、`LocalNaturalLanguageParser.kt`：统一精简 Prompt、简单句标题/时间边界修正。
- `smartadd/ModelSpeedBenchmark.kt`：主动固定样例串行测速。
- `ui/smartadd/SmartAddViewModel.kt`、`SmartAddScreen.kt`：接入路由/页面去重、通用 Loading 文字；Insets 不变。
- `ui/settings/SettingsViewModel.kt`、`SettingsScreen.kt`：双模型配置、分别连接测试、安全诊断、Debug 测速。
- Gradle 版本号、README、新增/调整回归测试。该版本无新依赖。
