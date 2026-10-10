// SPDX-License-Identifier: GPL-2.0-only
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using AppTime.Core;
using Microsoft.Win32.SafeHandles;
namespace AppTime.Desktop;

public sealed class NativeSource
{
    private uint lastPid;
    private IntPtr lastWindow;
    private AppIdentity? lastApp;
    private long readAt;
    private bool lastHosted;
    public AppIdentity? ReadWindow(IntPtr window) { GetWindowThreadProcessId(window,out uint pid); return Resolve(window,pid,out _); }
    public uint ForegroundPid { get { GetWindowThreadProcessId(GetForegroundWindow(),out uint pid); return pid; } }
    public Sample Read(string? blocked=null)
    {
        long mono=(long)(Stopwatch.GetTimestamp()*1000.0/Stopwatch.Frequency);
        var info=new LASTINPUTINFO { cbSize=(uint)Marshal.SizeOf<LASTINPUTINFO>() };
        if (!GetLastInputInfo(ref info)) return new(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),mono,0,null,"无法读取空闲状态");
        long idle=unchecked((uint)Environment.TickCount-info.dwTime);
        // On the secure desktop there is no ordinary foreground window. Never read its contents.
        IntPtr window=GetForegroundWindow(); GetWindowThreadProcessId(window,out uint pid);
        if (pid!=lastPid || window!=lastWindow || mono-readAt>30000 || lastApp==null || lastHosted)
        {
            lastPid=pid; lastWindow=window; readAt=mono; lastApp=Resolve(window,pid,out lastHosted);
        }
        return new(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),mono,idle,lastApp,blocked);
    }
    private static AppIdentity? Resolve(IntPtr window,uint pid,out bool hosted)
    {
        hosted=false;
        try
        {
            string? path=Image(pid);
            if (path==null) return null;
            if (Path.GetFileName(path).Equals("ApplicationFrameHost.exe",StringComparison.OrdinalIgnoreCase))
            {
                hosted=true; var children=new HashSet<uint>(); uint hostPid=pid;
                EnumWindowsCallback callback=(child,_)=> { GetWindowThreadProcessId(child,out uint candidate); if (candidate!=hostPid && candidate!=0) children.Add(candidate); return true; };
                EnumChildWindows(window,callback,IntPtr.Zero); GC.KeepAlive(callback);
                var candidates=children.Select(p=>new { Pid=p,Family=Family(p),Path=Image(p) }).Where(p=>p.Family.Length>0 && p.Path!=null).ToList();
                if (candidates.Select(p=>p.Family).Distinct().Count()!=1) return null;
                pid=candidates[0].Pid; path=candidates[0].Path!;
            }
            var metadata=FileVersionInfo.GetVersionInfo(path);
            string name=string.IsNullOrWhiteSpace(metadata.ProductName) ? Path.GetFileNameWithoutExtension(path) : metadata.ProductName;
            string family=Family(pid);
            // Path is only a conservative identity clue: same-name applications are not merged.
            string key=family.Length>0 ? "pfn:"+family : "exe:"+Path.GetFileName(path).ToLowerInvariant()+":"+Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(Path.GetFullPath(path).ToUpperInvariant())));
            return new(key,name.Length>512 ? name[..512] : name);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or ArgumentException or System.ComponentModel.Win32Exception) { return null; }
    }
    private static string? Image(uint pid)
    {
        using var handle=OpenProcess(0x1000,false,pid); if (handle.IsInvalid) return null;
        var buffer=new StringBuilder(32768); uint size=32768;
        return QueryFullProcessImageName(handle,0,buffer,ref size) ? buffer.ToString() : null;
    }
    private static string Family(uint pid)
    {
        using var handle=OpenProcess(0x1000,false,pid); if (handle.IsInvalid) return "";
        uint length=0; if (GetPackageFamilyName(handle,ref length,null)!=122 || length is 0 or >512) return "";
        var buffer=new StringBuilder((int)length); return GetPackageFamilyName(handle,ref length,buffer)==0 ? buffer.ToString() : "";
    }
    [StructLayout(LayoutKind.Sequential)] private struct LASTINPUTINFO { public uint cbSize,dwTime; }
    private delegate bool EnumWindowsCallback(IntPtr window,IntPtr data);
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr window,out uint pid);
    [DllImport("user32.dll")] [return:MarshalAs(UnmanagedType.Bool)] private static extern bool GetLastInputInfo(ref LASTINPUTINFO info);
    [DllImport("user32.dll")] [return:MarshalAs(UnmanagedType.Bool)] private static extern bool EnumChildWindows(IntPtr window,EnumWindowsCallback callback,IntPtr data);
    [DllImport("kernel32.dll",SetLastError=true)] private static extern SafeProcessHandle OpenProcess(uint access,[MarshalAs(UnmanagedType.Bool)] bool inherit,uint pid);
    [DllImport("kernel32.dll",EntryPoint="QueryFullProcessImageNameW",CharSet=CharSet.Unicode,SetLastError=true)] [return:MarshalAs(UnmanagedType.Bool)] private static extern bool QueryFullProcessImageName(SafeProcessHandle handle,uint flags,StringBuilder path,ref uint size);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode)] private static extern int GetPackageFamilyName(SafeProcessHandle handle,ref uint length,StringBuilder? name);
}
