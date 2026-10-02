# .atbackup v1

Android v1.3.0 实现手动加密导出、预览、合并与替换恢复。协议独立于 Room 数据库版本；不包含数据库二进制、WAL、凭据、SAF URI、图标缓存或派生每日缓存。

## 加密封装

所有整数使用大端序。公开头部共 54 字节，完整作为 AES-GCM 的 AAD 认证。

| 偏移 | 长度 | 内容 |
| --- | --- | --- |
| 0 | 8 | 魔数 `41 54 42 4b 0d 0a 1a 0a` |
| 8 | 2 | 格式版本，uint16，固定 1 |
| 10 | 1 | KDF ID，1 = PBKDF2-HMAC-SHA256 |
| 11 | 1 | 加密 ID，1 = AES-256-GCM |
| 12 | 1 | 压缩 ID，1 = ZIP |
| 13 | 1 | 保留标志，固定 0 |
| 14 | 4 | PBKDF2 迭代次数，固定 600000 |
| 18 | 16 | 随机 salt |
| 34 | 12 | 随机 GCM nonce |
| 46 | 8 | 密文及认证 tag 总长度，int64 |
| 54 | 可变 | 加密 ZIP，末尾为 16 字节认证 tag |

口令按 UTF-8 编码，不做 Unicode 规范化，长度为 8–256 个 UTF-16 代码单元。密钥 32 字节，每次导出使用独立安全随机 salt 与 nonce。头部、密文或 tag 改动均不可通过认证；未知算法、参数与版本直接拒绝。600000 次参数参考 [OWASP PBKDF2 建议](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#pbkdf2)，测试机执行解密、恢复前备份加密及回读验证共约 8.5 秒，均在后台线程处理。

## ZIP 与 JSONL

ZIP 恰好含 `manifest.json` 与下表的 12 个 `.jsonl` 文件。只接受 STORED/DEFLATED；禁止目录、重复文件、未知名称和路径穿越。JSONL 每行一个 UTF-8 JSON 对象，以 LF 分隔；空表为空文件，整数与布尔值字段使用 JSON 整数，布尔值为 0/1，SQL NULL 使用 JSON null。对象键顺序不影响兼容性。

| 文件（省略 .jsonl） | 字段（必须全部出现） |
| --- | --- |
| devices | deviceId, platform, createdAt, reportTimezone |
| app_identities | identityId, deviceId, profileScope, packageName, displayName |
| sessions | sessionId, originDeviceId, identityId, anchorMs, startMs, endMs, durationMs, timezone, utcOffsetSeconds, metric, source, provisional, transitionEstimated, quality, revision, deleted |
| historical_buckets | bucketId, originDeviceId, identityId, startMs, endMs, usageMs, source, timezone, quality, revision |
| coverage | coverageId, deviceId, startMs, endMs, status, reason |
| system_daily_usage | identityId, reportDate, timezone, durationMs, bucketStartMs, bucketEndMs, observedAtMs |
| app_preferences | identityId, category, hidden, ignored, revision |
| ignore_periods | periodId, identityId, startMs, endMs, revision |
| manual_adjustments | adjustmentId, identityId, reportDate, timezone, deltaMs, note, createdAtMs, revision, deleted |
| app_observations | identityId, status, signingDigest, signingChanged, observedAtMs |
| collection_state | source, recordFromMs, checkpointMs, lastSuccessMs, enabled, status, detail |
| history_import_state | source, requestedStartMs, requestedEndMs, returnedStartMs, returnedEndMs, acceptedBuckets, skippedBuckets, lastAttemptMs, status, detail |

字段类型、主键、NULL 约束见 [Room v5 schema](../android/app/schemas/io.github.silent07137.apptime.data.AppDatabase/5.json)。本协议字段由显式清单固定，未来数据库内部新增列不会自动导出。时间为 UTC 毫秒；日期为 ISO 日期，时区为 IANA ID，历史未知时区为 `unknown`。仅接受 Android 来源：事件 metric/source 为 `android_foreground` / `android_usage_events`；导入进度 source 为 `android_usage_stats_daily` 或 `android_usage_stats_best`。

manifest 的 `format_version` 与 `schema_version` 均为整数 1，包含 `snapshot_id`、`created_at`、`exporting_device_id`、`device_coverage` 及 `files`。每个文件的元数据包含 `rows`、`bytes`、`sha256`（小写十六进制），对解压后的原始文件字节计算。设备覆盖条目含 `device_id`、`start_utc_ms`、`end_utc_ms`、`quality`；当前仅报告保存会话的外包范围，quality 始终为 partial，不能把其间缺口视为已覆盖。

资源限制：密文及 tag ≤16 MiB；解压内容合计 ≤64 MiB；manifest ≤128 KiB；JSONL 每行 ≤16 KiB；总记录 ≤200000；设备 ≤32；本地进度表各 ≤1 行；单条会话 ≤7 天。字段字符串通常 ≤512 个 UTF-16 单元，note/detail/reason ≤2048；revision 为 1–1000000000，UTC 时间为 0–253402300799999，时区偏移为 ±64800 秒。非法字段、行数、摘要、外键及应用/设备归属均在独立暂存数据库校验，目标档案尚未写入。

## 合并与恢复

主键保持来源不变。session、历史桶、应用设置、忽略区间和手动修正采用 revision；系统逐日/安装观测采用 observedAtMs，覆盖记录采用 endMs。相同内容不写入；旧版本不覆盖新版本；更高版本更新；同版本不同内容拒绝整个恢复并回滚。应用名称变化保留目标已有名称；设备/应用身份及会话锚点不可改写。删除会话和撤销修正保留 tombstone，旧备份不能将其恢复计入。

合并保留本机身份与采集进度，不导入另一台手机的本地检查点。替换时只有勾选“这是原来的手机”才沿用备份来源身份/进度；否则保留原始来源设备，创建独立本机采集身份。导入应用不会使用本机安装状态覆盖其原设备观测。

每次恢复前，先加密当前一致性快照，再解密回读验证，保存为应用私有目录中的恢复前备份；口令为本次输入的备份口令。替换还需二次确认。数据写入、活动采集身份更新、每日缓存重建在同一 Room 事务完成。恢复前备份可在设置中另行导出，下一次恢复会替换该保护文件；应用卸载会删除私有保护文件。导出到 SAF 后回读长度与 SHA-256，验证通过才显示成功。

原始时段与各设备来源时区保留，每日汇总保留来源设备报表日期；多设备总计为设备之和，暂不估算跨设备并集。没有逻辑应用映射、Windows GUI/采集、自动备份、WebDAV 或作业历史。

## 跨运行时样例

[黄金样例](../test-fixtures/backup-v1/README.md)只包含合成数据，固定 salt/nonce 仅用于测试。JVM 与真实 Android 解密 .NET 生成样例；.NET 参考工具验证认证、中文口令、文件摘要与随机封装往返。该工具验证文件互通，不是 Windows 客户端。
