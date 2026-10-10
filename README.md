# AppTime

离线记录应用使用时长。支持 Android 10 及以上；[Windows 原生客户端](windows/README.md)已提供 0.2.0。采用 GPLv2 许可。

[下载最新安装包](https://github.com/silent07137/AppTime/releases/latest)

安装后打开 AppTime，授予“使用情况访问”。应用会尝试导入系统仍保留的最早历史，并继续记录使用时长；可查看累计、逐日用时和有记录的小时分布；支持日期筛选、应用分类、隐藏、忽略与手动修正。

数据保存在本机，应用不申请网络权限。系统可能已清理更早的记录。设置中支持加密备份与恢复；卸载前请导出备份并保管口令。

## 自行构建

安装 JDK 21、Android SDK Platform 37 和 Build Tools 36.0.0，配置 `ANDROID_HOME` 后运行：

```powershell
cd android
.\gradlew.bat :app:assembleDebug
```

统计口径见 [specs/statistics.md](specs/statistics.md)，测试与正式签名说明见 [docs/TESTING.md](docs/TESTING.md) 和 [docs/RELEASING.md](docs/RELEASING.md)。

源码采用 [GPL-2.0-only](LICENSE)；第三方许可见 [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md)。
