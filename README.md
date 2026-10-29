# DDL Reminder

一个记录课程截止日期和待办事项的 Android 应用。以月历查看任务，在截止前接收系统提醒；支持备忘、自然语言添加和 CSV 导入。

[下载 APK](https://github.com/DONY2333/ddl_reminder/releases/tag/v1.2.8) · [构建检查](https://github.com/DONY2333/ddl_reminder/actions/workflows/android.yml) · [问题反馈](https://github.com/DONY2333/ddl_reminder/issues) · [Apache-2.0](LICENSE)

## 界面预览

<table>
  <tr><th>月历与任务</th><th>智能添加</th><th>备忘与提醒</th></tr>
  <tr>
    <td valign="top"><img src="docs/images/home-calendar.jpg" alt="月历、未来三天与当日任务" width="240"></td>
    <td valign="top"><img src="docs/images/smart-add-preview.jpg" alt="解析结果确认" width="240"></td>
    <td valign="top"><img src="docs/images/ddl-editor.jpg" alt="编辑备忘、截止时间与提醒" width="240"></td>
  </tr>
</table>

<details>
<summary>数据与隐私</summary>

<img src="docs/images/data-privacy.jpg" alt="本地记录的导出与清空" width="480">

</details>

截图来自 V1.2.7 实机运行，作业编号已遮挡。首页的测试通知按钮仅在 Debug 构建中显示。

## 功能

- **月历与临近任务**：按日期查看 DDL，标记有任务的日期，集中展示未来 72 小时内的未完成任务。
- **任务管理**：添加、编辑、完成、恢复和删除；每项任务可填写备忘，逾期任务保留状态标记。
- **独立提醒**：支持常用提醒节点、自定义分钟数和提醒方案。全局默认只影响新任务，完成或删除任务会取消剩余提醒。
- **智能添加**：支持中文文本、系统语音识别和 CSV 导入，所有结果经预览确认后保存。
- **可选 AI 解析**：简单句使用本地规则，复杂文本调用配置的单一 AI 模型，失败后回退本地解析。
- **本地使用记录**：匿名日志保存在设备上，可手动导出 CSV 或单独清空。

## 安装

当前版本：**1.2.8 / versionCode 13**，支持 **Android 8.0（API 26）及以上**。

从 [Releases](https://github.com/DONY2333/ddl_reminder/releases) 下载 `DDL_Reminder_v1.2.8_debug.apk`，传到手机后打开安装。首次使用需允许通知；精确提醒需要额外开启系统“闹钟和提醒”权限。

也可通过 USB 安装：

```powershell
adb install -r ".\releases\DDL_Reminder_v1.2.8_debug.apk"
```

安装包为 **Debug / Beta** 构建。同包名、同签名的旧版可覆盖安装并保留数据；卸载或清除应用数据会删除本地任务。历史安装包保存在 [releases/](releases/)，校验值见 [SHA256SUMS.txt](releases/SHA256SUMS.txt)。

## AI 配置与隐私

未配置 AI 时，仍可手动添加任务或使用本地解析。在“设置 → 智能解析”填写 API 地址、API Key 和模型 ID，并确认数据发送说明后启用 AI。

服务需兼容 OpenAI Chat Completions 协议。例如 Base URL 为 `https://api.example.com/v1` 时，请求地址为 `https://api.example.com/v1/chat/completions`。默认模型 ID 为 `gpt-6-luna`，具体名称以服务商提供的 ID 为准。

任务、备忘、设置和匿名日志保存在本机，Android 云备份已关闭。AI 请求只发送当前主动提交的解析文字，不会自动上传已有任务或日志。系统语音识别的数据处理方式取决于手机安装的识别服务。

API Key 通常由 Android Keystore 加密存储；Keystore 初始化失败时会回退到普通 SharedPreferences。建议使用可撤销、限额的 Key。技术错误详情可在“设置 → 高级诊断”展开查看。

## 开发

技术栈：Kotlin、Jetpack Compose、Material 3、Room、DataStore、AlarmManager。单模块，使用 ViewModel 和手动依赖组装。

准备 **JDK 17**、**Android SDK 35**，用 Android Studio 打开仓库并完成 Gradle Sync。

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
```

macOS / Linux 使用 `./gradlew`。默认构建产物位于 `app/build/outputs/apk/debug/app-debug.apk`。版本归档由发布流程另行复制到 `releases/`，不会由 Gradle 自动覆盖。

有设备或模拟器时，可运行 `connectedDebugAndroidTest`。GitHub Actions 自动执行单元测试、Debug 构建和 lint，并保留报告及构建产物。

```text
app/src/main/java/com/example/ddlreminder/
├── data/          Room、迁移和任务操作
├── reminder/      提醒计算、Alarm 与系统通知
├── settings/      默认提醒、提醒方案和 AI 配置
├── smartadd/      本地解析、AI 客户端和 CSV 导入
├── logging/       匿名日志与 CSV 导出
├── diagnostics/   本地故障诊断
└── ui/            Compose 页面与 ViewModel
```

Room 当前版本为 **5**，包含显式迁移链 `1 → 2 → 3 → 4 → 5`。V1.2.8 不改变数据库结构。应用标识沿用 `com.example.ddlreminder`，以保持覆盖安装兼容。

更多说明：

- [开发与机制说明](docs/DEVELOPMENT.md)：提醒调度、迁移、AI 诊断及测试范围。
- [版本记录](CHANGELOG.md)
- [历史技术报告](docs/history/README.md)

## 已知限制

- 厂商省电策略、通知频道设置或系统“强行停止”可能影响提醒；悬浮通知由系统控制。
- 开机和 App 启动时会恢复提醒，修改系统时间、时区或更新安装后的专门同步尚未实现。
- 本地解析主要覆盖常见中文表达，复杂内容和未给出具体时间的结果需要在预览中确认。
- 匿名日志导出不包含完整任务，不能用来恢复 DDL；目前没有完整任务备份和云同步。
- AI 服务的可用性、费用和数据处理政策取决于所配置的服务商。
- 自动化测试不能替代实机通知、键盘、生命周期和真实 AI 输出验证。

此前白屏问题已解决，本地诊断与页面恢复机制继续保留。若再次遇到异常，请在 Issue 中提供复现步骤及脱敏后的 Logcat，排查方法见[开发文档](docs/DEVELOPMENT.md#故障排查)。

## 许可证

Copyright 2026 DONY2333。项目采用 [Apache License 2.0](LICENSE)，第三方依赖适用各自许可证。
