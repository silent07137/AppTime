# .atbackup v1 黄金样例

全部内容为合成测试数据：一个虚构 Android 设备，一个 `example.app` 应用，一条 60 秒时段。没有真实手机数据或签名材料。

公开测试口令：`AppTime-备份-golden-123`。

`windows-golden.atbackup` 由 Windows 客户端的真实导出代码生成，仅含合成 Windows 档案。两条不同会话重复墙钟范围：原始累计 80 秒，忽略范围扣除后 70 秒，加上有效补记 60 秒，总计 130 秒；含分类、隐藏、忽略区间与修正墓碑。用于两端来源隔离、回拨时钟和管理记录的往返测试，使用随机 salt/nonce。

`golden.zip` 为固定明文 ZIP；`golden.atbackup` 由 .NET 参考编码器生成，固定 salt 为 00–0f，nonce 为 10–1b。固定随机值只允许用于该测试样例，产品导出必须使用安全随机值。其余 JSON/JSONL 为可查看的明文基准。

```powershell
dotnet run --project specs/tools/BackupFixture/BackupFixture.csproj -- test-fixtures/backup-v1
```

末尾加 `generate` 可重新生成固定加密样例；JVM 与 Android 测试读取同一文件。格式见 [协议说明](../../specs/backup-v1-status.md)。参考工具只验证封装和 manifest，不具备客户端导入/采集功能。
