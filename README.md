# AppTime

精简、离线的应用累计使用时长档案。**Android v1.0.0 · GPLv2**。

[下载安装包](https://github.com/silent07137/AppTime/releases/tag/v1.0.0)

首次开启使用情况访问后，自动尝试导入系统保留的最早历史，再持续记录新的前台事件。首页展示已知总计、已记录与可追溯旧历史；应用档案支持真实图标、累计排行、搜索和详情，趋势展示最近 7 天的细粒度记录。浅色与深色主题跟随系统，技术说明可展开查看。

原生 Kotlin / Compose / Room / WorkManager。没有网络权限、账号、广告、分析 SDK、常驻服务或演示数据。打开应用时采集，后台每 6 小时尝试一次。

## 使用与口径

安装 APK → 打开 AppTime → 开启“使用情况访问”。自动导入完成后即可查看累计，不需要手动选择天数。设置中的“重新读取全部历史”可以再次查询。

- **已记录**：建立后取得的前台会话，扣除历史汇总拥有的重叠范围。
- **可追溯旧历史**：系统返回的完整汇总，可能覆盖建立后的时间，原始时区与完整性未知。
- **已知总计**：上述两部分的不重叠总和。分屏可同时累计多个应用，前台时间不等于实际操作时间。

默认从时间戳 0 查询，使用系统 `INTERVAL_BEST` 选择汇总周期。返回起点是系统汇总边界，不能解释为安装日期或首次使用日。不裁剪或按比例分摊汇总，不叠加同范围的日/年汇总；旧观测保留，完全覆盖的旧日汇总由较宽汇总代替计入。系统未返回数据时显示未采集/未取得，不清除已有档案。见 [统计契约](specs/statistics.md)。

## 构建

Android 10+（minSdk 29），targetSdk 36。JDK 21、SDK Platform 37.0、Build Tools 36.0.0。Gradle 9.3.1、AGP 9.1.1、Kotlin 2.2.10 与依赖已固定，提交依赖锁文件。

配置 `ANDROID_HOME` 或忽略的 `android/local.properties`：

```powershell
cd android
.\gradlew.bat :core:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r io.github.silent07137.apptime.test/androidx.test.runner.AndroidJUnitRunner
```

macOS/Linux 使用 `./gradlew`。直接 ADB 测试可保留目标应用数据；Gradle `connectedDebugAndroidTest` 的设备清理可能卸载目标应用，只应在可重置设备执行。见 [验证说明](docs/TESTING.md)。

发布构建启用代码与资源压缩，通过 `APPTIME_SIGNING_PROPERTIES` 读取仓库外签名配置。构建后运行 `android/sign-release.ps1` 生成含公开证书轮换证明的最终 APK；无配置时 Gradle 构建未签名包。见 [签名与发布](docs/RELEASING.md)。`:app:assemblePreview` 使用本机调试签名，只供开发验证。

## 已知限制与后续阶段

- **尚无备份。卸载 AppTime 或清除数据会丢失本机档案。** 加密备份/恢复是下一阶段重点。
- Windows、WebDAV、跨设备同步、分类与手动调整尚未实现。本版本交付 Android 本机统计，不代表开发手册全部阶段完成。
- 系统保留范围、权限和事件完整性限制可取得的数据，不能保证全部过去历史。WorkManager 不是精确闹钟，强停后需重开；长期后台可靠性尚未验收。
- 报表时区固定为档案建立时的时区，尚无重建设置。旧系统汇总无法无损拆到趋势图。
- 包名与设备/用户范围作为身份，被统计应用卸载后保留档案；名称/图标不可解析时显示包名和占位图标。签名辅助身份、安装状态观测待实现。
- 公开 API 无稳定 Activity 实例 ID。极短组件切换（≤1 秒）可能连成估算区间，同包观测按并集计时，不同应用可重叠。
- 48 小时回查加 24 小时预读；不能因事件不再返回而删除旧会话，窗口之外的缺失边界无法保证完整纠正。
- Room v2 保留显式 v1→v2 迁移，禁止破坏性升级。

## 源码与许可

`android/core` 为可测试计算；`android/app` 为客户端；`specs`、`test-fixtures`、`docs` 为契约、样例与验收；`windows` 为后续客户端位置。

[任务清单](docs/TASKS.md) · [设计说明](docs/DESIGN.md) · [实测记录](docs/VALIDATION-2026-09-30.md) · [原始手册](AppTime_Codex开发文档.md)

原创源码 **GPL-2.0-only**，完整许可见 [LICENSE](LICENSE)，第三方见 [THIRD_PARTY_NOTICES](docs/THIRD_PARTY_NOTICES.md)。源码不包含手机 PIN、凭据或签名私钥。
