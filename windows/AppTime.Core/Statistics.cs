// SPDX-License-Identifier: GPL-2.0-only
using static AppTime.Core.Store;
namespace AppTime.Core;

public record Span(long Start, long End);
public record AppUsage(string Id, string Name, string Key, long Total, Dictionary<DateOnly,long> Days, List<Span> Spans, bool Hidden);
public record Report(Device Device, List<AppUsage> Apps)
{
    public long Total => Apps.Sum(a=>a.Total);
    public long Day(DateOnly date, string? app=null) => Apps.Where(a=>app==null || a.Id==app).Sum(a=>a.Days.GetValueOrDefault(date));
    public SortedSet<DateOnly> Dates(string? app=null) => new(Apps.Where(a=>app==null || a.Id==app).SelectMany(a=>a.Days.Keys));
}
public static class Statistics
{
    public static Report Read(Store store, string deviceId)
    {
        var data=store.Snapshot();
        var d=data["devices"].Single(r=>S(r,"deviceId")==deviceId);
        var device=new Device(deviceId,S(d,"platform"),S(d,"reportTimezone"),N(d,"createdAt"));
        var apps=new List<AppUsage>();
        var sessions=data["sessions"].Where(s=>S(s,"originDeviceId")==deviceId && N(s,"deleted")==0).ToLookup(s=>S(s,"identityId"));
        var history=data["historical_buckets"].Where(s=>S(s,"originDeviceId")==deviceId).ToLookup(s=>S(s,"identityId"));
        var system=data["system_daily_usage"].ToLookup(s=>S(s,"identityId"));
        var adjustments=data["manual_adjustments"].Where(s=>N(s,"deleted")==0).ToLookup(s=>S(s,"identityId"));
        var ignores=data["ignore_periods"].ToLookup(s=>S(s,"identityId"));
        var preferences=data["app_preferences"].ToDictionary(s=>S(s,"identityId"));
        foreach (var i in data["app_identities"].Where(i=>S(i,"deviceId")==deviceId))
        {
            string id=S(i,"identityId");
            var observed=sessions[id].Select(s=>(Start:N(s,"startMs"),End:N(s,"endMs")));
            // Windows episodes have unique IDs and monotonic durations. A clock rollback can
            // repeat a wall-clock range; unioning those distinct episodes would lose real usage.
            var spans=device.Platform=="windows" ? observed.OrderBy(s=>s.Start).ToList() : TimeZones.Union(observed);
            foreach (var ignore in ignores[id]) spans=Subtract(spans,[(N(ignore,"startMs"),ignore["endMs"]==null ? long.MaxValue : N(ignore,"endMs"))]);
            var days=new Dictionary<DateOnly,long>();
            foreach (var span in spans)
                foreach (var part in TimeZones.Split(span.Start,span.End,device.Timezone)) days[part.Date]=days.GetValueOrDefault(part.Date)+part.End-part.Start;
            // Only true calendar-day observations can replace the event-derived daily value.
            foreach (var row in system[id])
            {
                var date=DateOnly.ParseExact(S(row,"reportDate"),"yyyy-MM-dd");
                long start=TimeZones.Midnight(date,device.Timezone),end=TimeZones.Midnight(date.AddDays(1),device.Timezone);
                if (N(row,"bucketStartMs")==start && N(row,"bucketEndMs")>start && N(row,"bucketEndMs")<=end && !ignores[id].Any(g=>N(g,"startMs")<end && (g["endMs"]==null || N(g,"endMs")>start)))
                    days[date]=N(row,"durationMs");
            }
            var buckets=history[id].OrderByDescending(h=>N(h,"endMs")-N(h,"startMs")).ThenByDescending(h=>S(h,"source")=="android_usage_stats_best").ToArray();
            var accepted=new List<Dictionary<string,object?>>();
            foreach (var h in buckets)
                if (!accepted.Any(a=>N(a,"startMs")<N(h,"endMs") && N(a,"endMs")>N(h,"startMs")) && !ignores[id].Any(g=>N(g,"startMs")<N(h,"endMs"))) accepted.Add(h);
            long total=Subtract(spans,accepted.Select(h=>(N(h,"startMs"),N(h,"endMs")))).Sum(s=>s.End-s.Start)+accepted.Sum(h=>N(h,"usageMs"));
            foreach (var row in adjustments[id])
            {
                var date=DateOnly.ParseExact(S(row,"reportDate"),"yyyy-MM-dd");
                days[date]=Math.Max(0,days.GetValueOrDefault(date)+N(row,"deltaMs")); total+=N(row,"deltaMs");
            }
            apps.Add(new(id,S(i,"displayName"),S(i,"packageName"),Math.Max(0,total),days,spans.Select(s=>new Span(s.Start,s.End)).ToList(),preferences.TryGetValue(id,out var pref) && N(pref,"hidden")==1));
        }
        return new(device,apps.OrderByDescending(a=>a.Total).ThenBy(a=>a.Name).ToList());
    }
    public static List<(long Start,long End)> Subtract(IEnumerable<(long Start,long End)> spans, IEnumerable<(long Start,long End)> excluded)
    {
        var result=spans.ToList();
        foreach (var cut in TimeZones.Union(excluded))
        {
            var next=new List<(long Start,long End)>();
            foreach (var s in result)
                if (s.End<=cut.Start || s.Start>=cut.End) next.Add(s);
                else { if (s.Start<cut.Start) next.Add((s.Start,cut.Start)); if (s.End>cut.End) next.Add((cut.End,s.End)); }
            result=next;
        }
        return result;
    }
    public static List<(long Start,long End,string Label,long Duration)> Hours(Report report, DateOnly day, string? app=null)
    {
        long start=TimeZones.Midnight(day,report.Device.Timezone),end=TimeZones.Midnight(day.AddDays(1),report.Device.Timezone);
        var result=new List<(long,long,string,long)>();
        for (long t=start;t<end;t+=3600000)
        {
            long stop=Math.Min(t+3600000,end); var local=TimeZoneInfo.ConvertTime(DateTimeOffset.FromUnixTimeMilliseconds(t),TimeZones.Find(report.Device.Timezone));
            string label=local.ToString("HH:mm zzz");
            long duration=report.Apps.Where(a=>app==null || a.Id==app).SelectMany(a=>a.Spans).Sum(s=>Math.Max(0,Math.Min(s.End,stop)-Math.Max(s.Start,t)));
            result.Add((t,stop,label,duration));
        }
        return result;
    }
    public static string Duration(long ms)
    {
        if (ms<60000) return $"{Math.Max(0,ms/1000)} 秒";
        long minutes=ms/60000; return minutes<60 ? $"{minutes} 分" : minutes%60==0 ? $"{minutes/60} 小时" : $"{minutes/60} 小时 {minutes%60} 分";
    }
}
