// SPDX-License-Identifier: GPL-2.0-only
using System;
using System.Diagnostics;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using AppTime.Core;
using Microsoft.Win32;
namespace AppTime.Desktop;

public sealed class Tracker : IDisposable
{
    private readonly Store store;
    private readonly Collector collector=new();
    private readonly NativeSource source=new();
    private readonly object gate=new();
    private readonly Timer timer;
    private bool locked,sleeping,disposed,paused;
    private long checkpoint;
    private (List<Session> Sessions,List<Gap> Gaps)? retry;
    public string Status { get; private set; }="等待前台应用";
    public string Foreground { get; private set; }="";
    public bool Paused => paused;
    public string? Error { get; private set; }
    public Tracker(Store store)
    {
        this.store=store; Configure();
        paused=store.Setting("paused")=="true";
        SystemEvents.SessionSwitch+=SessionChanged; SystemEvents.PowerModeChanged+=PowerChanged;
        timer=new Timer(_=>Tick(),null,0,1000);
    }
    public void Configure()
    {
        lock (gate)
        {
            collector.Break(); Flush();
            collector.Timezone=store.LocalTimezone;
            collector.IdleThresholdMs=long.TryParse(store.Setting("idle_minutes"),out var minutes) ? minutes*60000 : 300000;
            collector.IgnoredKeys=store.IgnoredKeys();
        }
    }
    public void SetIgnored(string id,bool ignored)
    {
        lock (gate)
        {
            SampleBoundary(); collector.Break(); Flush();
            store.SetIgnored(id,ignored,DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            collector.IgnoredKeys=store.IgnoredKeys(); Tick();
        }
    }
    public void TogglePause()
    {
        lock (gate) { SampleBoundary(); collector.Break(); Flush(); paused=!paused; store.SetSetting("paused",paused ? "true" : "false"); Tick(); }
    }
    public void Flush()
    {
        lock (gate)
        {
            var fresh=collector.Drain();
            var batch=(Sessions:(retry?.Sessions ?? []).Concat(fresh.Sessions).GroupBy(s=>s.Id).Select(g=>g.MaxBy(s=>s.Revision)!).ToList(),Gaps:(retry?.Gaps ?? []).Concat(fresh.Gaps).ToList());
            try { store.Save(batch); retry=null; Error=null; checkpoint=Environment.TickCount64; }
            catch { retry=batch; throw; }
        }
    }
    private void Tick()
    {
        if (!Monitor.TryEnter(gate)) return;
        try
        {
            if (disposed) return;
            if (Error!=null) { Flush(); collector.Break(); }
            string? blocked=paused ? "已暂停" : locked ? "锁屏暂停" : sleeping ? "睡眠暂停" : null;
            string old=Foreground; collector.Poll(source.Read(blocked));
            Status=collector.Status; Foreground=collector.Foreground?.Name ?? "";
            if (old!=Foreground || Environment.TickCount64-checkpoint>=15000) Flush();
            Error=null;
        }
        catch (Exception e) when (e is Microsoft.Data.Sqlite.SqliteException or System.IO.IOException)
        { Error="无法保存记录，请检查数据目录和磁盘空间"; Status=Error; collector.Break(); }
        finally { Monitor.Exit(gate); }
    }
    private void SampleBoundary() { collector.Poll(source.Read(paused || locked || sleeping ? "已暂停" : null)); }
    private void SessionChanged(object sender,SessionSwitchEventArgs e)
    {
        lock (gate)
        {
            if (e.Reason is SessionSwitchReason.SessionLock or SessionSwitchReason.RemoteDisconnect or SessionSwitchReason.ConsoleDisconnect)
            { SampleBoundary(); collector.Break(); locked=true; SaveSignalBoundary(); }
            else if (e.Reason is SessionSwitchReason.SessionUnlock or SessionSwitchReason.RemoteConnect or SessionSwitchReason.ConsoleConnect) { locked=false; collector.Break(); }
        }
    }
    private void PowerChanged(object sender,PowerModeChangedEventArgs e)
    {
        lock (gate)
        {
            if (e.Mode==PowerModes.Suspend) { SampleBoundary(); collector.Break(); sleeping=true; SaveSignalBoundary(); }
            else if (e.Mode==PowerModes.Resume) { sleeping=false; collector.Break(); }
        }
    }
    private void SaveSignalBoundary()
    {
        try { Flush(); }
        catch (Exception e) when (e is Microsoft.Data.Sqlite.SqliteException or System.IO.IOException)
        { Error="无法保存记录，请检查数据目录和磁盘空间"; Status=Error; }
    }
    public void Dispose()
    {
        lock (gate)
        {
            if (disposed) return; disposed=true; timer.Dispose();
            SystemEvents.SessionSwitch-=SessionChanged; SystemEvents.PowerModeChanged-=PowerChanged;
            SampleBoundary(); collector.Break();
            try { Flush(); } catch (Exception e) when (e is Microsoft.Data.Sqlite.SqliteException or System.IO.IOException) { Error="最后的检查点保存失败"; }
        }
    }
}
