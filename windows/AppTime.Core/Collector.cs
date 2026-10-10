// SPDX-License-Identifier: GPL-2.0-only
namespace AppTime.Core;

public record AppIdentity(string Key, string Name);
public record Sample(long UtcMs, long MonotonicMs, long IdleMs, AppIdentity? App, string? Blocked = null);
public record Session(string Id, AppIdentity App, long StartMs, long EndMs, string Timezone, int OffsetSeconds, long Revision = 1);
public record Gap(long StartMs, long EndMs, string Reason);

// Pure state machine: the adapter supplies clocks and signals; no wall-clock subtraction for duration.
public sealed class Collector
{
    private Sample? previous;
    private Session? current;
    private readonly List<Session> pending = [];
    private readonly List<Gap> gaps = [];
    public long IdleThresholdMs { get; set; } = 300_000;
    public string Timezone { get; set; } = TimeZones.LocalIana;
    public HashSet<string> IgnoredKeys { get; set; } = [];
    public string Status { get; private set; } = "等待前台应用";
    public AppIdentity? Foreground { get; private set; }

    public void Poll(Sample next)
    {
        if (next.Blocked == null && next.App != null && IgnoredKeys.Contains(next.App.Key)) next = next with { Blocked = "已忽略" };
        bool active = next.Blocked == null && next.App != null && (IdleThresholdMs == 0 || next.IdleMs < IdleThresholdMs);
        Foreground = active ? next.App : null;
        Status = next.Blocked ?? (next.App == null ? "无法读取前台应用" : active ? "正在记录" : "空闲暂停");
        if (previous is { } last)
        {
            long delta = next.MonotonicMs - last.MonotonicMs;
            bool discontinuity = delta < 0 || delta > 5000 || Math.Abs((next.UtcMs - last.UtcMs) - delta) > 2000;
            if (discontinuity)
            {
                Close();
                if (next.UtcMs > last.UtcMs) gaps.Add(new(last.UtcMs, next.UtcMs, delta > 5000 ? "采样中断" : "系统时间变化"));
            }
            else if (last.Blocked == null && last.App != null && (IdleThresholdMs == 0 || last.IdleMs < IdleThresholdMs))
            {
                long allowed = delta;
                if (IdleThresholdMs > 0 && next.IdleMs >= IdleThresholdMs)
                    allowed = Math.Clamp(delta - (next.IdleMs - IdleThresholdMs), 0, delta);
                // Unknown/unreadable foreground cannot establish the intervening app ownership.
                if (next.App == null && next.Blocked == null) allowed = 0;
                if (allowed > 0)
                {
                    if (current == null || current.App.Key != last.App.Key || Math.Abs(current.EndMs - last.UtcMs) > 2000 || current.Timezone != Timezone)
                    {
                        Close();
                        var offset = TimeZones.Find(Timezone).GetUtcOffset(DateTimeOffset.FromUnixTimeMilliseconds(last.UtcMs));
                        current = new(Guid.NewGuid().ToString(), last.App, last.UtcMs, last.UtcMs, Timezone, (int)offset.TotalSeconds);
                    }
                    current = current with { EndMs = current.EndMs + allowed, Revision = current.Revision + 1 };
                    // Keep sessions bounded and split offset changes rather than silently flattening DST.
                    if (current.EndMs - current.StartMs >= 3_600_000) Close();
                }
                if (!active || next.App?.Key != last.App.Key) Close();
            }
            else Close();
            if (delta is > 0 and <= 5000 && last.Blocked != "已忽略" && (last.Blocked != null || last.App == null) && next.UtcMs > last.UtcMs)
                gaps.Add(new(last.UtcMs, next.UtcMs, last.Blocked ?? "无法读取前台应用"));
        }
        previous = next;
    }

    private void Close() { if (current is { } s && s.EndMs > s.StartMs) pending.Add(s); current = null; }
    public (List<Session> Sessions, List<Gap> Gaps) Drain()
    {
        var result = pending.ToList();
        if (current is { } s && s.EndMs > s.StartMs) result.Add(s);
        pending.Clear(); var coverage = gaps.ToList(); gaps.Clear();
        return (result, coverage);
    }
    public void Break() { Close(); previous = null; }
}

public static class TimeZones
{
    public static string LocalIana => TimeZoneInfo.TryConvertWindowsIdToIanaId(TimeZoneInfo.Local.Id, out var id) ? id : TimeZoneInfo.Local.Id;
    public static TimeZoneInfo Find(string id) => TimeZoneInfo.FindSystemTimeZoneById(id);
    public static string Iana(string id)
    {
        Find(id);
        if (TimeZoneInfo.TryConvertWindowsIdToIanaId(id, out var converted)) return converted;
        if (id == "UTC" || TimeZoneInfo.TryConvertIanaIdToWindowsId(id, out _)) return id;
        throw new ArgumentException("请使用 IANA 时区名称");
    }
    public static long Midnight(DateOnly day, string zone)
    {
        var tz = Find(zone); var local = day.ToDateTime(TimeOnly.MinValue, DateTimeKind.Unspecified);
        // Some zones change at midnight. Advance to the first valid local instant.
        while (tz.IsInvalidTime(local)) local = local.AddMinutes(1);
        if (tz.IsAmbiguousTime(local)) return new DateTimeOffset(local, tz.GetAmbiguousTimeOffsets(local).Max()).ToUnixTimeMilliseconds();
        return new DateTimeOffset(TimeZoneInfo.ConvertTimeToUtc(local, tz), TimeSpan.Zero).ToUnixTimeMilliseconds();
    }
    public static DateOnly Date(long utc, string zone) => DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(DateTimeOffset.FromUnixTimeMilliseconds(utc), Find(zone)).DateTime);
    public static IEnumerable<(DateOnly Date, long Start, long End)> Split(long start, long end, string zone)
    {
        while (start < end)
        {
            var day = Date(start, zone); long stop = Math.Min(end, Midnight(day.AddDays(1), zone));
            if (stop <= start) throw new InvalidDataException("时区日期边界无效");
            yield return (day, start, stop); start = stop;
        }
    }
    public static List<(long Start, long End)> Union(IEnumerable<(long Start, long End)> source)
    {
        var result = new List<(long Start, long End)>();
        foreach (var s in source.Where(s => s.End > s.Start).OrderBy(s => s.Start))
            if (result.Count == 0 || result[^1].End < s.Start) result.Add(s);
            else result[^1] = (result[^1].Start, Math.Max(result[^1].End, s.End));
        return result;
    }
}
