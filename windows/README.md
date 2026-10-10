# AppTime Windows 0.1.0

Windows 11 x64 原生客户端。运行 AppTime.exe 即开始记录活跃前台应用，默认空闲 5 分钟暂停；锁屏、睡眠和采样中断不补计。关闭窗口后留在托盘，可暂停、继续或退出。

支持累计、应用排行、逐日小时分布、深浅主题、登录启动，以及手动加密备份和合并恢复。数据保存在 `%LOCALAPPDATA%\AppTime`；卸载或迁移前导出 `.atbackup` 并保管口令。只记录启动后的用时，不读取窗口标题、网页或输入内容。

GitHub Actions 的 `Windows checks` 生成两个包：普通包需要 [.NET 10 Desktop Runtime x64](https://dotnet.microsoft.com/download/dotnet/10.0)，`standalone` 包包含运行时。两种包使用同一份本机档案。整目录解压后运行；配置开机启动后请保留程序所在路径。

可导入 Android v1.3 的备份，按来源设备查看。Windows 与 Android 的统计口径单独展示；Android v1.3.1 尚不能导入含 Windows 设备的备份。此初版不包含 WebDAV、自动备份、跨设备应用映射、分类/手动调整编辑器或安装器，完整阶段验收见 [VALIDATION.md](VALIDATION.md)。

构建需要固定 .NET SDK 10.0.401。在本目录运行：

```powershell
dotnet restore AppTime.Tests --locked-mode
dotnet run --project AppTime.Tests -- ../test-fixtures/backup-v1
dotnet publish AppTime.Desktop -c Release -r win-x64 --self-contained false -o out/light
```

GPL-2.0-only；程序内提供仓库、开源和第三方许可证。
