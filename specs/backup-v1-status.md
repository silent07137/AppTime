# .atbackup v1：待固化

本轮没有提供备份编码器或恢复入口；没有生成符合产品 v1 的备份文件。

下一轮须完整定义并实现：公开认证头部、AES-256-GCM、PBKDF2-HMAC-SHA256 参数基准、ZIP/JSONL 内容、manifest 校验、来源设备与 revision/tombstone 合并、资源限制及恢复前保护。

在格式与黄金样例通过测试前，不使用自定义简化格式占用 `.atbackup` 扩展名。不得导出凭据、SAF URI、设备密钥或手机测试信息。
