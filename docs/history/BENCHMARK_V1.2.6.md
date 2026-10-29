# V1.2.6 模型速度测试修复

> 历史记录：以下内容描述该版本发布时的实现和验证结果。当前行为见[项目首页](../../README.md)，版本演进见[版本记录](../../CHANGELOG.md)。文中代码及 APK 路径均相对仓库根目录。

版本：versionName 1.2.6，versionCode 11。Room 保持 5，无 Migration。

## 原因与修复范围

旧实现执行 12 次串行逻辑请求，经过正常 AI 客户端还可能重试，每次连接/读取等待会累积；仅在整组结束后发布结果，没有独立测速 Job、实时进度与可靠离页取消，表现为长期加载。

该版本只调整 Debug Benchmark：一个固定复杂通知，Fast 两次、Strong 两次；不自动重试，单次网络加 JSON 处理上限 15 秒、整体上限 90 秒。正常 Smart Add 的 Prompt、路由、重试和超时策略不变。

不可变 BenchmarkState 统一表示运行状态、当前序号与已完成结果。逐次发布成功/失败、耗时、各模型当前平均值；平均包含失败请求，避免仅看成功请求低估等待。相同规范化模型 ID 提示“两个模型配置相同”，不请求。

SettingsViewModel 保存独立 Job，finally 恢复非运行状态；CancellationException 继续抛出。取消、返回、页面销毁、切后台、ViewModel 清理均取消当前协程；HTTP cancellation watcher 断开连接。代次校验防止迟到回调覆盖取消或下一轮状态，清理完成前不允许新一轮重叠。

诊断/结果不保存 Key、请求正文、AI 响应、DDL 或备忘。仅使用代码内固定样例，不读取已有任务。

## 核心修改

- `smartadd/ModelSpeedBenchmark.kt`：四次执行、状态快照、两级超时、异常隔离与逐次结果。
- `smartadd/AiApiClient.kt`：新增生产 HTTP 单次无重试调用，独立 15 秒 socket 参数；普通调用保持原样。
- `ui/settings/SettingsViewModel.kt`：专用 Job、取消、finally 与代次防护。
- `ui/settings/SettingsScreen.kt`：进度、结果、取消按钮与生命周期清理。
- `app/build.gradle.kts`、README：版本号与使用说明。

## 验证

完整 `testDebugUnitTest --offline`：293 项，0 失败、0 错误、0 跳过；相比 V1.2.5 新增 28 项。

新增 ModelSpeedBenchmarkTest（14）、SettingsBenchmarkTest（9）、BenchmarkStatusTest（4）、SettingsBenchmarkLifecycleTest（1）；更新原 TieredParserTest 的 Benchmark 用例为四次。测试使用模拟 HTTP/响应，不请求真实服务。

分别执行 `assembleDebug --offline`、`lintDebug --offline`，均 BUILD SUCCESSFUL；lint 0 Error、6 Warning。未连接手机/模拟器，未执行 connectedDebugAndroidTest；Compose UI 测试为 Robolectric，不代替真机验收。

默认 APK：`app/build/outputs/apk/debug/app-debug.apk`（相对项目根目录）。

归档 APK：`releases/DDL_Reminder_v1.2.6_debug.apk`，历史 APK 未覆盖。该版本原交付时两份 SHA-256 一致：`38C231C580990E32D9AD99EDF4652254A8793E9CE135676125F7E82BBF5891F9`；后续重编译不保证字节一致。

包名与 V1.2.5 相同、签名证书 SHA-256 相同（`0f1f3edbc94ff66334a5658a6f6e1986b696a9888a093ef70db4fb8971b7cece`），支持覆盖安装保留数据；不要先卸载。

## 真机验收清单

1. 不同模型：观察快速模型 1/4、2/4，高级模型 3/4、4/4；每次完成立即显示结果、耗时和平均值。
2. 测速中点击取消，确认立即停止加载、无后续请求；重新测试应正常工作。
3. 测速时返回、切页面或切后台，再进入：无残留加载、无旧响应覆盖。
4. 慢网/断网、HTTP 失败、无效 JSON：有失败结果，不无限加载、不重试；超时后可再次操作。
5. 两个相同模型 ID（含首尾空格）：显示“两个模型配置相同”，服务端无测速请求。
6. 正常 Smart Add、已有 DDL 和提醒做覆盖安装回归。

验证范围为模拟请求；未执行真实 API 测速。发布时尚未定位历史白屏的原始根因。
