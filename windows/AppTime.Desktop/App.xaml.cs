// SPDX-License-Identifier: GPL-2.0-only
using System;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Windows;
using AppTime.Core;
namespace AppTime.Desktop;

public partial class App : Application
{
    private Mutex? mutex;
    private EventWaitHandle? activate;
    private RegisteredWaitHandle? listener;
    private FileStream? writerLock;
    private Store? store;
    private Tracker? tracker;
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e); ShutdownMode=ShutdownMode.OnExplicitShutdown;
        string directory=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"AppTime");
        int dataIndex=Array.IndexOf(e.Args,"--data-dir");
        if (e.Args.Contains("--smoke-test") && dataIndex<0) { Shutdown(2); return; }
        if (dataIndex>=0 && dataIndex+1<e.Args.Length) directory=Path.GetFullPath(e.Args[dataIndex+1]);
        string token=Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(directory.ToUpperInvariant())))[..24];
        mutex=new Mutex(true,"Local\\AppTime-"+token,out bool first);
        activate=new EventWaitHandle(false,EventResetMode.AutoReset,"Local\\AppTime-open-"+token);
        if (!first) { activate.Set(); Shutdown(); return; }
        try
        {
            Directory.CreateDirectory(directory);
            writerLock=new FileStream(Path.Combine(directory,".writer.lock"),FileMode.OpenOrCreate,FileAccess.ReadWrite,FileShare.None);
            store=new Store(Path.Combine(directory,"archive.sqlite"));
            tracker=new Tracker(store);
            var window=new MainWindow(store,tracker); MainWindow=window;
            listener=ThreadPool.RegisterWaitForSingleObject(activate,(_,_)=>Dispatcher.BeginInvoke(window.Open),null,Timeout.Infinite,false);
            SessionEnding+=(_,_)=>tracker.Dispose();
            if (!e.Args.Contains("--tray")) window.Show();
            if (e.Args.Contains("--smoke-test")) { window.Show(); window.RunSmoke(directory); }
        }
        catch (Exception ex) when (ex is IOException or Microsoft.Data.Sqlite.SqliteException or InvalidDataException)
        { MessageBox.Show("无法打开 AppTime 档案。请检查数据目录、磁盘空间或升级应用。","AppTime",MessageBoxButton.OK,MessageBoxImage.Error); Shutdown(1); }
    }
    protected override void OnExit(ExitEventArgs e)
    {
        listener?.Unregister(null); tracker?.Dispose(); store?.Dispose(); writerLock?.Dispose(); activate?.Dispose();
        mutex?.Dispose(); base.OnExit(e);
    }
}
