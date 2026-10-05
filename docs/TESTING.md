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

v1.1.0 结果见 [验证记录](VALIDATION-2026-10-01.md)。Windows、长期可靠性不列为通过。

## v1.2.0 补充

设备测试新增分类/隐藏筛选、手动修正与撤销、负数边界、忽略/恢复与系统快照衔接、重启尾段、签名确认；v1/v2→v4 迁移验证原档案。小时分布核心测试覆盖应用并集、跨午夜、23/25 小时日。

实机检查每日柱图/列表进入详情、小时选择和时间线、日期范围/分类筛选、前进返回与页状态、关于页许可入口、大字体/浅深色，以及同签名覆盖升级和强停重开。旧汇总不能还原具体时段，不列为小时覆盖通过。

## v1.3.0 补充

核心加密测试覆盖随机 salt/nonce、中文口令互通、错误口令/密文/AAD 篡改、版本/KDF/长度限额与截断/尾部数据。设备备份测试覆盖全部原始表、重复导入零改动、高版本修订和删除标记、同版本冲突后的事务回滚、会话所有权不可变、来源设备外键、原机/换机恢复、本机采集身份隔离、来源时区、恢复前保护、解压炸弹/路径穿越/格式与摘要拒绝，以及诊断默认隐私。迁移新增 v4→v5 管理数据和 v1/v2→v5 保留验证。

跨运行时样例验证（需 .NET SDK 9.0.311）：

```powershell
dotnet run --project specs/tools/BackupFixture/BackupFixture.csproj -- test-fixtures/backup-v1
```

## v1.3.1 补充

新增错位系统桶（17:35 至次日 17:35 / 当晚未结束）、来源时区与 23/25 小时日校验；设备测试验证每日汇总与小时图取同一午夜范围、原始系统桶保留、旧备份恢复/重复合并及 v5→v6 撤权后的离线重建。v1/v2 升级链延伸至 v6；v4→v5 仍单独验证历史 schema。

实机另检查 SAF 保存与回读、口令输入/取消/错误、恢复预览与替换确认、诊断导出、浅深色与大字体。CI 检查 .NET 样例、核心测试、debug/release lint 与压缩构建，再使用原有证书签名。真实手机差异仍须获取该手机诊断后核对，不能凭另一台测试机声称已确认原因。
