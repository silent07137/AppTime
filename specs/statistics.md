# Android 统计契约（1.1.0）

- metric：`android_foreground`；source：`android_usage_events`；quality：`partial`。
- UTC 时间戳与 64 位毫秒，左闭右开 `[start, end)`。
- 档案建立后首次取得授权时固定 `recordFromMs`，此前只预读边界，不计入累计。
- 48 小时回查 + 24 小时边界预读。无开始事件时不猜开始时间。
- 应用身份：本地 UUID；设备 UUID + 用户范围 + packageName 唯一。名称不参与合并。
- 会话键：UTF-8 的 `deviceId + NUL + profile + NUL + packageName + NUL + anchorMs + NUL + android_usage_events`，经 Java UUID.nameUUIDFromBytes（UUID v3）生成。anchorMs 为原始应用 resume 起点，startMs 裁剪到固定 recordFromMs。
- 组件按公开 API 的 className 跟踪；同包活动集合不为空时只有一条应用会话。公开 SDK 没有稳定 Activity 实例 ID，同一组件的多个实例可能无法完整区分，因此仍标记 partial，不调用隐藏 API。
- 组件全部暂停后，下一相关事件若是同包 1 秒内 resume，可连接为一条估算会话；非零间隔标记 transitionEstimated。该估算会计入极短组件过渡间隔。
- screen off、keyguard shown、shutdown 结束会话；startup 缺失结束信号时丢弃无法确认的开放尾段并给已有暂定记录写 deleted/revision。
- 未结束会话仅到本次 queryEnd，provisional=true；后续同键替换，可缩短。
- 非空事件不证明完整覆盖，一律 partial。空查询/撤权不清除已有会话、不移动成功检查点。超过回查窗口单独标记缺口。
- 同一应用的多个观测先做区间并集，再按固定报表时区的真实午夜切分；因此重叠观测不重复计时。跨应用累计是各应用之和，可大于墙钟时间。
- 保存更改、日缓存和成功检查点在一个事务内；同键同内容不会提高 revision。并发前台与 Worker 由进程内单例 Mutex 串行执行，Room 管理 SQLite 写事务。
- 不因系统不再返回旧事件而删除旧会话。本版未完成可靠的全范围所有权重算与备份传播，因此不能作为跨设备协议最终实现。

## 系统历史

首次授权自动查询最早可用汇总；即使事件为空也独立导入。请求从 UTC 时间戳 0 到建立日结束/当前时间中的较早者，使用 INTERVAL_BEST，让系统选择周期；有限天数导入仍用 INTERVAL_DAILY。已导入有限范围的档案会自动扩展。空历史记状态并允许设置中手动重试；权限不可用后可在恢复授权时自动重试。

source 为 android_usage_stats_best / android_usage_stats_daily，timezone 与 quality 均为 unknown。不裁剪或按比例分摊系统返回桶；跨越建立时间的完整桶归入历史估算，可能包含建立后的时长。保留请求与实际返回范围，返回起点是汇总边界，不代表首次使用。

身份/设备/用户/source/start 确定历史键，同键 upsert 修订，不累加。best-fit 汇总完全覆盖已有日桶时保留两份原始观测，查询只计较宽汇总；相同边界优先 best-fit。部分重叠且无法整体替代时保守跳过，不猜测剩余比例。会话原始记录保留，派生日报扣除历史拥有区间，避免再加一次。

建立后前 72 小时采集会尝试更新过渡汇总，防止建立当天冻结旧观测。系统可能扩展请求区间，始终保留完整返回桶，不声称精确拆分新旧。空结果或撤权不清除已有桶。

## 已记录与逐日时长

“已记录”是本机保存的前台事件按应用做区间并集、按档案时区切日后的累计；即使这些事件落在旧历史汇总的范围内，记录本身仍保留并显示。升级到 v1.1.0 时从已有会话重建此缓存；后续会话修订同步重建。它与“可追溯旧历史”可能重叠，不能直接相加。“已知总计”仍用扣除历史拥有范围后的事件缓存加有效历史汇总，避免重复累计。

首次同步单独请求系统 `INTERVAL_DAILY` 可用的全部逐日汇总，之后每次采集回查最近 31 天；手动“重新读取全部历史”可重读完整可用范围。同一应用/日期的重复查询覆盖原观测，不累加；短时更小的系统返回值不会清除已保存值。系统没有返回某应用某天时，逐日页面显示保存的事件日汇总。宽范围旧汇总不按比例拆成日期。设备变更时区后，旧系统日桶的原始时区无法确认，日期按档案报表时区标注，可能有偏差。

[官方 API](https://developer.android.com/reference/android/app/usage/UsageStatsManager#queryUsageStats(int,%20long,%20long)) 说明返回范围可能扩展到完整聚合周期，[INTERVAL_BEST](https://developer.android.com/reference/android/app/usage/UsageStatsManager#INTERVAL_BEST) 由系统选取适合范围的周期。

手动补记、不同平台 metric 尚未接入。安装状态固定 unknown，首次记录不能解释为安装日期。
