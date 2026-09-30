# v1.0.0 实际验证记录

2026-09-30，Android 本机统计阶段；不代表开发手册全部阶段完成。

## 环境

Windows 主机、Zulu JDK 21、SDK Platform 37.0、Build Tools 36.0.0；测试设备 22021211RC，Android 16 / API 36。手机 PIN、设备标识和签名口令不进入仓库。

## 已执行

- `:core:test`：21 项通过，0 失败、0 跳过。包含状态机、时区/并集、历史桶策略。
- 直接 ADB runner：17 项通过，0 失败、0 跳过。14 项 Repository/SQLite、1 项 v1→v2 迁移、2 项真实系统来源；模拟来源测试与真实系统读取分别验证。
- `:app:lintDebug` / `:app:lintRelease`：0 错误、6 警告。分别为 targetSdk 36 不是最高版本、4 项依赖更新提示、1 项 Bitmap KTX 建议；继续保持已实测的固定版本。
- Debug、Preview（界面初轮）、最终 Release 和 AndroidTest APK 构建成功。最终命令包含核心测试、两种 lint、Debug、AndroidTest 和 Release。
- 最终 Release 签名验证通过（APK Signature Scheme v2），2048 位 RSA。覆盖安装成功，保留原来的档案建立起点与权限；设备报告 versionName=1.0.0、versionCode=10000，flags 不包含 DEBUGGABLE。
- 初轮真实前台实验：系统设置使用约 72 秒，累计增长约 72 秒；10 次刷新保持已结束应用时长不重复增长。撤权后档案保留，重新授权可继续。
- 默认导入从时间戳 0 查询，使用真实系统 best-fit 汇总。此设备实际返回起点为 2026-09-25 18:05，结束为当次查询时间。不能据此保证此前没有使用或其他设备也保留这些历史。
- 旧有限范围记录升级为默认最早查询；测试验证较宽汇总替代完全覆盖的旧日汇总，原始观测保留、总计不相加。
- 真实 UI 检查总览、应用图标/排行、详情返回、趋势与缺失日期、设置折叠信息；浅色、深色及 1.3 字体倍率可读，随后恢复原始字体和深色设置。
- Release 启动后未发现 AppTime 的 FATAL EXCEPTION。

前期曾执行 Gradle connected 测试，其设备清理卸载了目标 App 并移除当时的测试样本；随后改用直接 ADB runner，所有后续覆盖安装保留现有主库。没有把这段历史隐瞒为完整档案。

## 发布产物

`AppTime-v1.0.0.apk`，2,602,615 字节（约 2.6 MB）。

SHA256：`f4cfba5abbb89a2143df6340a74360bf8a2c354a27187db5a686210a079b3c96`。

## 未验收

长期 6 小时后台执行、厂商省电/强停、多日真实重启、所有字体/小屏/TalkBack、性能指标、完整包可见性与商店政策。备份、Windows 和跨设备尚未实现，不列为通过。APK 的汇总是系统可用前台估算，不能称为终身精确历史。
