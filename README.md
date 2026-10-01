# AppTime

离线记录 Android 应用的累计使用时长。支持 Android 10 及以上，采用 GPLv2 许可。

[下载 v1.0.0 安装包](https://github.com/silent07137/AppTime/releases/tag/v1.0.0)

安装后打开 AppTime，授予“使用情况访问”。应用会尝试导入系统仍保留的最早历史，并继续记录使用时长；首页可查看累计、应用排行和最近 7 天趋势。

数据保存在本机，应用不申请网络权限。系统可能已清理更早的记录。**v1.0.0 暂无备份；卸载应用或清除数据会丢失档案。**

## 自行构建

安装 JDK 21、Android SDK Platform 37 和 Build Tools 36.0.0，配置 `ANDROID_HOME` 后运行：

```powershell
cd android
.\gradlew.bat :app:assembleDebug
```

统计口径见 [specs/statistics.md](specs/statistics.md)，测试与正式签名说明见 [docs/TESTING.md](docs/TESTING.md) 和 [docs/RELEASING.md](docs/RELEASING.md)。

源码采用 [GPL-2.0-only](LICENSE)；第三方许可见 [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md)。
