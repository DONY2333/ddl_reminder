# V1.2.7 单模型配置与 UI 简化

版本：versionName 1.2.7，versionCode 12。

## 最终链路

简单明确文本 → Local Fast Path → Preview（0 HTTP）。

复杂文本 → 当前单一 AI 模型 → 成功进入 Preview；失败转 LocalNaturalLanguageParser 保守回退。临时 HTTP/连接错误仍由原 HttpAiApiClient 同模型最多重试一次，不会切换第二个模型；原 30 秒 AI 链路总上限触发后停止网络请求并本地回退。外部取消继续抛出，不触发失败回退。

AI 默认值位于 `settings/AiSettingsRepository.kt` 的 `AiSettings.DEFAULT_MODEL = "gpt-6-luna"`。`currentModel` 按旧 fastModel、旧 modelName、默认值选择；不根据模型字符串切换协议。加密配置读取时通过 `forSingleModel()` 兼容旧值，保存单模型时同时写回 model/fast_model，保留旧 strong_model，但正式解析不使用它。Base URL、API Key、隐私确认保持；设置页支持修改中转实际 ID。

## UI 与稳定性

- 普通设置页仅有 API 地址、API Key、AI 模型三个 AI 配置输入框，保留原隐私确认、保存与单一连接测试。
- 包括 Debug 包在内，普通设置页不再显示双模型输入或 Benchmark。ModelSpeedBenchmark、SettingsViewModel 的开发测速方法、BenchmarkStatus 及既有测试保留。
- 解析诊断显示 LOCAL / AI / LOCAL_FALLBACK、AI 模型、HTTP/JSON/总耗时、网络重试和失败阶段，不再显示双模型分组。
- 复用 Parsing 状态的 CircularProgressIndicator，增加约 1.5 秒后的“正在理解通知内容…”；没有假百分比。保留输入、固定 TopAppBar、返回、忙碌时禁用解析、取消与旧响应隔离。
- JSON 容错、具体网络诊断、NavigationSafety、RecoveryPage、StabilityDiagnostics、重复点击防护和 Preview 缓存保留。
- Room 版本 5，未修改数据库及 Migration。ScheduleExtractionPrompt 文件与 V1.2.6 SHA-256 相同，未修改业务语义。

## 修改文件

主代码：`AiSettingsRepository.kt`、`TieredNaturalLanguageParser.kt`、`ParsePerformance.kt`、`SettingsViewModel.kt`、`SettingsScreen.kt`、`SmartAddScreen.kt`；版本：`app/build.gradle.kts`；文档：README、本报告。

测试：更新 AiModelSettingsTest、TieredParserTest、SettingsAiPerformanceTest、SmartAddLayoutTest、SmartAddStateStabilityTest，新增 SettingsSingleModelUiTest。新增 10 项测试，原双模型升级测试按新单模型预期更新，未删减 Benchmark 测试。

## 验证与交付

- `testDebugUnitTest --offline`：303 项，0 失败、0 错误、0 跳过。
- `assembleDebug --offline`：BUILD SUCCESSFUL。
- `lintDebug --offline`：BUILD SUCCESSFUL，0 Error、6 Warning。
- adb 无设备，未执行 connectedDebugAndroidTest；Compose UI 测试使用 Robolectric。
- 军理通知在模拟响应的 Parser/真实 ViewModel 状态回归中保持 1 个事件、备忘完整、进入 Preview，未请求旧 Strong。未使用用户真实 API 进行模型验收，不将固定响应测试等同于模型真实准确率。

默认 APK：`app/build/outputs/apk/debug/app-debug.apk`（相对项目根目录）。

版本归档：`releases/DDL_Reminder_v1.2.7_debug.apk`，历史 APK 全部保留。

归档 SHA-256：`F1C1A732C71D527ED39EC8EC6ACBF3080D80244DD242AF6199DA94B9774DA3F3`。原交付时默认 APK 与归档一致，后续重编译不保证字节一致。

包名 `com.example.ddlreminder` 与签名均沿用 V1.2.6，支持覆盖安装保留现有任务和配置，不要卸载后再安装。

```powershell
adb install -r ".\releases\DDL_Reminder_v1.2.7_debug.apk"
```

## 真机重点验证

1. 从 V1.2.6 覆盖安装，已有 DDL/提醒不变；原 fastModel 显示在单一 AI 模型框，地址、Key、隐私确认保留。
2. 简单句“明天下午3点开班会”直接预览，复杂军理通知只请求当前模型；必要时修改中转实际 Model ID。
3. 观察解析时动画、1.5 秒提示、输入与返回仍存在；取消、离页再进入正常，无旧响应覆盖。
4. 401、断网或慢网失败有可见提示、本地回退，无第二模型请求；失败后可重试。
5. 设置中无高级模型或测速入口；连接测试只测试当前字段的模型。

历史偶发白屏的原始根因仍未捕获；本次没有修改相关防护，也不宣称彻底解决历史白屏。
