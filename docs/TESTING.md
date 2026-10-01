# 验证说明

核心 JVM 测试覆盖事件状态机、组件重叠、短过渡、缺失边界、暂定修订、锁屏/重启、身份、并集、午夜与 DST、共享 TSV、历史桶筛选和所有权扣除。

设备测试使用真实 Room / SQLite，覆盖重复刷新、空查询/撤权保留、尾段修订、并集、重启墓碑、身份续接、时钟回拨、并发串行、事务回滚、历史过渡去重、自动最早导入/授权/重试、宽汇总替代日汇总、已记录和系统逐日数据持久化。此外验证 v1→v3、v2→v3 迁移和真实系统事件、最早汇总、逐日汇总。

## 执行

测试机需要 USB 调试、AppTime 使用情况访问权限，以及最近实际打开过应用。两个真实来源测试未授权时会跳过，不能宣称通过来源验证。

```powershell
cd android
.\gradlew.bat :core:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell appops set io.github.silent07137.apptime.debug GET_USAGE_STATS allow
adb shell am instrument -w -r io.github.silent07137.apptime.debug.test/androidx.test.runner.AndroidJUnitRunner
```

输出必须含 `OK (...)` 且没有跳过来源测试。`INSTRUMENTATION_CODE: -1` 是 runner 结束码，本身不代表测试结果。

Debug 使用独立的 `.debug` 包名，可与正式版共存；固定事件测试使用内存数据库，迁移使用独立命名库，不修改正式版档案。不过 Gradle `connectedDebugAndroidTest` 的设备清理可能卸载目标应用，仅在允许重置的设备使用。这里直接安装匹配的两个 APK 并运行 ADB runner。正式版覆盖升级仍要求签名一致。

## 人工检查

1. 未授权显示引导，未取得数据不伪装为零。授权返回后自动请求最早汇总。
2. 有限天数历史升级后扩展最早范围；宽汇总完全覆盖的旧日数据只计一次。
3. 使用普通应用约一分钟，再回到 AppTime，检查“已记录”增长；强停重开后仍保留。
4. 多次刷新不重复累计；应用详情逐日列表显示系统每日来源；撤权后保留，重授权继续。
5. 检查总览、搜索、详情返回、趋势、设置→关于的仓库和许可入口、页面切换动画、浅/深色和大字体。
6. 后续长期验证：锁屏/重启、强停数小时、厂商后台、时区变化、低内存与性能。

v1.1.0 结果见 [验证记录](VALIDATION-2026-10-01.md)。未实现的备份、Windows、长期可靠性不列为通过。
