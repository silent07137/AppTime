// SPDX-License-Identifier: GPL-2.0-only
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using AppTime.Core;
using Microsoft.Win32;
using Forms=System.Windows.Forms;
namespace AppTime.Desktop;

public partial class MainWindow : Window
{
    private readonly Store store;
    private readonly Tracker tracker;
    private readonly Forms.NotifyIcon tray;
    private readonly Forms.ToolStripMenuItem trayPause;
    private readonly DispatcherTimer refresh=new() { Interval=TimeSpan.FromSeconds(4) };
    private Report? report;
    private long loadedVersion=-1;
    private bool loading,exiting,busy;
    private int page,previousPage;
    private string? selectedApp;
    private DateOnly selectedDay;
    private readonly List<Button> nav=[];
    private string search="";
    private string category="全部分类",appFilter="可见应用";
    private sealed record DeviceChoice(Device Device,string Label) { public override string ToString()=>Label; }
    private sealed record AppRow(string Id,string Name,string Category,string State,string Time);

    public MainWindow(Store store,Tracker tracker)
    {
        InitializeComponent(); this.store=store; this.tracker=tracker; ApplyTheme();
        string[] names=["总览","应用","趋势","设备","设置"];
        string[] glyphs=["\uE80F","\uE71D","\uE9D2","\uE770","\uE713"];
        for (int i=0;i<names.Length;i++)
        {
            int index=i;
            var content=new StackPanel { Orientation=Orientation.Horizontal };
            content.Children.Add(new TextBlock { Text=glyphs[i],FontFamily=new FontFamily("Segoe MDL2 Assets"),Margin=new Thickness(0,0,14,0),VerticalAlignment=VerticalAlignment.Center });
            content.Children.Add(new TextBlock { Text=names[i] });
            var button=new Button { Content=content,Margin=new Thickness(0,0,0,8),BorderThickness=new Thickness(0),Padding=new Thickness(16,14,12,14) };
            AutomationProperties.SetName(button,names[i]); button.Click+=async (_,_)=> { page=index; selectedApp=null; await Reload(true); Draw(true); };
            nav.Add(button); Navigation.Children.Add(button);
        }
        var resource=Application.GetResourceStream(new Uri("pack://application:,,,/Assets/AppTime.ico"))!;
        using var iconStream=resource.Stream; using var originalIcon=new System.Drawing.Icon(iconStream);
        tray=new Forms.NotifyIcon { Icon=(System.Drawing.Icon)originalIcon.Clone(),Text="AppTime",Visible=true };
        tray.DoubleClick+=(_,_)=>Dispatcher.Invoke(Open);
        var menu=new Forms.ContextMenuStrip(); tray.ContextMenuStrip=menu;
        menu.Items.Add("打开 AppTime",null,(_,_)=>Dispatcher.Invoke(Open));
        trayPause=new Forms.ToolStripMenuItem("暂停记录",null,(_,_)=>Dispatcher.Invoke(TogglePause)); menu.Items.Add(trayPause);
        menu.Items.Add("今日详情",null,(_,_)=>Dispatcher.Invoke(()=> { Open(); if (report!=null) OpenDay(TimeZones.Date(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),report.Device.Timezone)); }));
        menu.Items.Add("导出备份",null,(_,_)=>Dispatcher.Invoke(()=> { Open(); ExportBackup(); }));
        menu.Items.Add("开机启动",null,(_,_)=>Dispatcher.Invoke(()=> { Open(); page=4; Draw(true); }));
        menu.Items.Add(new Forms.ToolStripSeparator()); menu.Items.Add("退出",null,(_,_)=>Dispatcher.Invoke(Exit));
        refresh.Tick+=async (_,_)=>
        {
            UpdateStatus();
            if (IsVisible && !busy && !loading && Keyboard.FocusedElement is not TextBox && Keyboard.FocusedElement is not ComboBox && store.Version!=loadedVersion && page is 0 or 2 or 5) { await Reload(); Draw(false); }
        };
        refresh.Start(); Closing+=OnClosing;
        Loaded+=async (_,_)=> { await Reload(true); Draw(true); UpdateStatus(); };
    }
    public void Open() { Show(); WindowState=WindowState.Normal; Activate(); }
    private void UpdateStatus()
    {
        StatusText.Text=tracker.Status; ForegroundText.Text=tracker.Foreground;
        PauseButton.Content=trayPause.Text=tracker.Paused ? "继续记录" : "暂停记录";
        tray.Text="AppTime · "+tracker.Status;
    }
    private void TogglePause() { try { tracker.TogglePause(); UpdateStatus(); } catch (Exception e) when (e is IOException or Microsoft.Data.Sqlite.SqliteException) { Message("无法保存暂停状态，请检查磁盘空间"); } }
    private void PauseClicked(object sender,RoutedEventArgs e)=>TogglePause();
    private void OnClosing(object? sender,CancelEventArgs e)
    {
        if (exiting) return;
        e.Cancel=true; Hide();
        if (store.Setting("tray_hint")!="true") { tray.ShowBalloonTip(2500,"AppTime","应用继续在托盘记录",Forms.ToolTipIcon.Info); store.SetSetting("tray_hint","true"); }
    }
    private void Exit()
    {
        if (busy) { Message("请等待备份操作完成"); return; }
        if (MessageBox.Show(this,"退出后将停止记录。确定退出？","AppTime",MessageBoxButton.OKCancel,MessageBoxImage.Question)!=MessageBoxResult.OK) return;
        exiting=true; refresh.Stop(); tray.Visible=false; tray.Dispose(); Close(); Application.Current.Shutdown();
    }
    private string DeviceId => (DevicePicker.SelectedItem as DeviceChoice)?.Device.Id ?? store.LocalDeviceId;
    private async Task Reload(bool force=false)
    {
        if (loading) return; loading=true; DevicePicker.IsEnabled=false;
        try
        {
            string id=DeviceId;
            if (force)
            {
                var devices=await Task.Run(store.Devices); DevicePicker.Items.Clear();
                foreach (var d in devices)
                {
                    string label=d.Id==store.LocalDeviceId ? "本机 · 活跃前台" : d.Platform=="windows" ? "Windows · "+d.Id[..8]+" · 活跃前台" : "Android · "+d.Id[..8]+" · 应用前台";
                    var option=new DeviceChoice(d,label); DevicePicker.Items.Add(option); if (d.Id==id) DevicePicker.SelectedItem=option;
                }
                if (DevicePicker.SelectedItem==null) DevicePicker.SelectedIndex=0;
            }
            id=DeviceId; long version=store.Version; report=await Task.Run(()=>Statistics.Read(store,id)); loadedVersion=version;
        }
        catch (Exception e) when (e is IOException or Microsoft.Data.Sqlite.SqliteException or InvalidDataException) { Message("读取档案失败，请检查数据目录"); }
        finally { loading=false; DevicePicker.IsEnabled=true; }
    }
    private async void DeviceChanged(object sender,SelectionChangedEventArgs e)
    { if (loading || store==null) return; selectedApp=null; await Reload(); Draw(true); }
    private void BackClicked(object sender,RoutedEventArgs e) { page=previousPage; selectedApp=null; Draw(true); }
    private Brush Brush(string key)=>(Brush)FindResource(key);
    private TextBlock Text(string text,double size=14,string color="Foreground")=>new() { Text=text,FontSize=size,Foreground=Brush(color) };
    private Border Card(UIElement content)=>new() { Child=content,Style=(Style)FindResource("Card") };
    private StackPanel Stack(params UIElement[] children) { var s=new StackPanel(); foreach (var child in children) s.Children.Add(child); return s; }
    private Button Action(string label,Action action)
    { var b=new Button { Content=label,Margin=new Thickness(0,0,0,10) }; b.Click+=(_,_)=>action(); return b; }
    private void Draw(bool animate)
    {
        if (report==null) return;
        Body.Children.Clear(); BackButton.Visibility=page>=5 ? Visibility.Visible : Visibility.Collapsed;
        for (int i=0;i<nav.Count;i++) nav[i].Background=Brush(page==i ? "Tint" : "Surface");
        Notice.Visibility=Visibility.Collapsed;
        switch (page)
        {
            case 0: Overview(); break; case 1: Apps(); break; case 2: Trends(); break; case 3: Devices(); break; case 4: Settings(); break; case 5: Day(); break; case 6: AppDetail(); break;
        }
        if (animate && SystemParameters.ClientAreaAnimation)
        {
            Body.BeginAnimation(OpacityProperty,new DoubleAnimation(0,1,TimeSpan.FromMilliseconds(170)));
            var translation=new TranslateTransform(); Body.RenderTransform=translation; translation.BeginAnimation(TranslateTransform.YProperty,new DoubleAnimation(8,0,TimeSpan.FromMilliseconds(170)));
        }
    }
    private DateOnly Today => TimeZones.Date(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),report!.Device.Timezone);
    private UIElement Metric(string title,long value,bool tinted=false)
    {
        var label=Text(title,14,"Muted"); var number=Text(Statistics.Duration(value),30,tinted ? "Accent" : "Foreground"); number.FontWeight=FontWeights.SemiBold; number.Margin=new Thickness(0,12,0,2);
        var card=Card(Stack(label,number)); if (tinted) card.Background=Brush("Tint"); return card;
    }
    private void Overview()
    {
        PageTitle.Text="总览"; var grid=new Grid(); grid.ColumnDefinitions.Add(new ColumnDefinition()); grid.ColumnDefinitions.Add(new ColumnDefinition());
        var total=Metric("累计用时",report!.Total,true); var today=Metric("今日",report.Day(Today));
        ((FrameworkElement)total).Margin=new Thickness(0,0,10,18); ((FrameworkElement)today).Margin=new Thickness(10,0,0,18); Grid.SetColumn(today,1); grid.Children.Add(total); grid.Children.Add(today); Body.Children.Add(grid);
        Body.Children.Add(Card(Stack(Text("最近 7 天",17),DailyChart(7))));
        Body.Children.Add(Text("应用排行",18));
        var ranking=report.Apps.Where(a=>!a.Hidden && a.Total>0).Take(8).ToList();
        if (ranking.Count==0) { var empty=Text("开始使用应用后，记录会显示在这里",14,"Muted"); empty.Margin=new Thickness(0,20,0,20); Body.Children.Add(empty); }
        else Body.Children.Add(AppList(ranking,370));
    }
    private Chart DailyChart(int count,string? app=null)
    {
        var dates=report!.Dates(app);
        return new Chart { Margin=new Thickness(0,20,0,0),Bars=Enumerable.Range(0,count).Select(i=>
        {
            var day=Today.AddDays(i-count+1); return new Bar(day.ToString("MM/dd"),dates.Contains(day) ? report.Day(day,app) : null,()=>OpenDay(day,app));
        }).ToList() };
    }
    private UIElement AppList(List<AppUsage> apps,double height)
    {
        var list=new ListView { Height=Math.Min(height,Math.Max(72,apps.Count*48+38)),Margin=new Thickness(0,16,0,18),Background=Brush("Surface"),Foreground=Brush("Foreground"),BorderThickness=new Thickness(0),Padding=new Thickness(10),ItemsSource=apps.Select(a=>new AppRow(a.Id,a.Name,a.Category,a.Ignored ? "已忽略" : a.Hidden ? "已隐藏" : "",Statistics.Duration(a.Total))).ToList() };
        VirtualizingPanel.SetIsVirtualizing(list,true); VirtualizingPanel.SetVirtualizationMode(list,VirtualizationMode.Recycling);
        ScrollViewer.SetHorizontalScrollBarVisibility(list,ScrollBarVisibility.Disabled);
        var view=new GridView();
        foreach (var column in new[]{("应用","Name",Math.Max(170,ActualWidth-870)),("分类","Category",95d),("状态","State",75d),("累计","Time",155d)})
            view.Columns.Add(new GridViewColumn { Header=column.Item1,DisplayMemberBinding=new System.Windows.Data.Binding(column.Item2),Width=column.Item3 });
        list.View=view;
        void Select() { if (list.SelectedItem is AppRow row) { selectedApp=row.Id; previousPage=page; page=6; Draw(true); } }
        list.KeyDown+=(_,e)=> { if (e.Key==Key.Enter) { Select(); e.Handled=true; } };
        list.MouseLeftButtonUp+=(_,e)=>
        {
            var element=e.OriginalSource as DependencyObject;
            while (element!=null && element is not ListViewItem) element=element is Visual ? VisualTreeHelper.GetParent(element) : null;
            if (element is ListViewItem) Select();
        };
        AutomationProperties.SetName(list,"应用列表，点击或按回车查看详情"); return Card(list);
    }
    private void Apps()
    {
        PageTitle.Text="应用"; var query=new TextBox { Text=search,Margin=new Thickness(0,0,0,8),ToolTip="搜索应用" }; AutomationProperties.SetName(query,"搜索应用");
        var categories=new ComboBox { ItemsSource=new[]{"全部分类"}.Concat(report!.Apps.Select(a=>a.Category).Distinct().Order()),MinWidth=130,Margin=new Thickness(0,0,12,10) };
        categories.SelectedItem=category; if (categories.SelectedItem==null) categories.SelectedIndex=0;
        var filters=new ComboBox { ItemsSource=new[]{"可见应用","全部档案","已隐藏","已忽略"},SelectedItem=appFilter,MinWidth=130,Margin=new Thickness(0,0,0,10) };
        AutomationProperties.SetName(categories,"应用分类"); AutomationProperties.SetName(filters,"档案筛选");
        var holder=new ContentControl();
        void Update()
        {
            search=query.Text; category=categories.SelectedItem as string ?? "全部分类"; appFilter=filters.SelectedItem as string ?? "可见应用";
            holder.Content=AppList(report!.Apps.Where(a=>(appFilter switch { "可见应用"=>!a.Hidden,"已隐藏"=>a.Hidden,"已忽略"=>a.Ignored,_=>true }) && (category=="全部分类" || a.Category==category) && a.Name.Contains(search,StringComparison.CurrentCultureIgnoreCase)).ToList(),Math.Max(220,ActualHeight-320));
        }
        query.TextChanged+=(_,_)=>Update(); categories.SelectionChanged+=(_,_)=>Update(); filters.SelectionChanged+=(_,_)=>Update();
        Body.Children.Add(query); Body.Children.Add(new StackPanel { Orientation=Orientation.Horizontal,Children={categories,filters} }); Body.Children.Add(holder); Update();
    }
    private void Trends()
    {
        PageTitle.Text="趋势"; Body.Children.Add(Card(Stack(Text("最近 30 天",17),DailyChart(30))));
        Dates(null);
    }
    private void Dates(string? app)
    {
        var dates=report!.Dates(app).Reverse().Take(366).ToList();
        if (dates.Count==0) { Body.Children.Add(Card(Text("暂无每日记录",14,"Muted"))); return; }
        foreach (var day in dates)
        {
            var button=Action(day.ToString("yyyy-MM-dd")+"                 "+Statistics.Duration(report.Day(day,app))+"     ›",()=>OpenDay(day,app)); Body.Children.Add(button);
        }
    }
    private void OpenDay(DateOnly day,string? app=null)
    {
        previousPage=page is 5 or 6 ? (app==null ? 2 : 1) : page; selectedDay=day; selectedApp=app; page=5; Draw(true);
    }
    private void AppDetail()
    {
        var app=report!.Apps.SingleOrDefault(a=>a.Id==selectedApp);
        if (app==null) { page=1; Draw(false); return; }
        PageTitle.Text=app.Name; Body.Children.Add(Metric("累计用时",app.Total,true));
        if (report.Device.Id==store.LocalDeviceId)
        {
            var hidden=new CheckBox { Content="隐藏应用",IsChecked=app.Hidden,Margin=new Thickness(0,8,0,12) };
            hidden.Click+=(_,_)=> { bool value=hidden.IsChecked==true; Change(()=>store.UpdatePreference(app.Id,hidden:value),"显示设置已保存"); };
            var ignored=new CheckBox { Content="忽略采集",IsChecked=app.Ignored,ToolTip="从现在起停止记录；已有历史保留",Margin=new Thickness(0,0,0,12) };
            ignored.Click+=(_,_)=> { bool value=ignored.IsChecked==true; Change(()=>tracker.SetIgnored(app.Id,value),value ? "已停止记录此应用，历史保留" : "已恢复记录，忽略时段不会补计"); };
            Body.Children.Add(new Expander { Header="应用管理 · "+app.Category,Margin=new Thickness(0,0,0,18),Content=Stack(Action("修改分类",()=>EditCategory(app)),hidden,ignored,Action("修正今日用时",()=>OpenDay(Today,app.Id))) });
        }
        Body.Children.Add(Card(Stack(Text("最近 7 天",17),DailyChart(7,app.Id)))); Dates(app.Id);
    }
    private void Day()
    {
        PageTitle.Text="每日详情";
        var chooser=new Grid(); chooser.ColumnDefinitions.Add(new ColumnDefinition { Width=GridLength.Auto }); chooser.ColumnDefinitions.Add(new ColumnDefinition()); chooser.ColumnDefinitions.Add(new ColumnDefinition { Width=GridLength.Auto });
        var left=Action("←",()=> { selectedDay=selectedDay.AddDays(-1); Draw(true); }); var right=Action("→",()=> { selectedDay=selectedDay.AddDays(1); Draw(true); }); right.IsEnabled=selectedDay<Today;
        var picker=new DatePicker { SelectedDate=selectedDay.ToDateTime(TimeOnly.MinValue),DisplayDateEnd=Today.ToDateTime(TimeOnly.MinValue),Width=180,Foreground=Brush("Foreground"),Background=Brush("Surface"),HorizontalAlignment=HorizontalAlignment.Center,Margin=new Thickness(0,0,0,14) }; AutomationProperties.SetName(picker,"统计日期");
        picker.SelectedDateChanged+=(_,_)=> { if (picker.SelectedDate is DateTime d && DateOnly.FromDateTime(d)!=selectedDay) { selectedDay=DateOnly.FromDateTime(d); Draw(true); } };
        Grid.SetColumn(picker,1); Grid.SetColumn(right,2); chooser.Children.Add(left); chooser.Children.Add(picker); chooser.Children.Add(right); Body.Children.Add(chooser);
        if (selectedApp!=null) Body.Children.Add(Text(report!.Apps.Single(a=>a.Id==selectedApp).Name,16,"Muted"));
        long daily=report!.Day(selectedDay,selectedApp); bool exists=report.Dates(selectedApp).Contains(selectedDay);
        Body.Children.Add(exists ? Metric("当天用时",daily,true) : Card(Text("当天暂无记录",18,"Muted")));
        if (selectedApp!=null && report.Device.Id==store.LocalDeviceId)
        {
            string id=selectedApp; DateOnly date=selectedDay;
            var corrections=Stack(Action("修正用时",()=>EditAdjustment(id,date)));
            foreach (var adjustment in store.Adjustments(id,date))
            {
                var item=adjustment;
                corrections.Children.Add(Action((item.DeltaMs>0 ? "+" : "−")+Statistics.Duration(Math.Abs(item.DeltaMs))+(item.Note.Length==0 ? "" : " · "+item.Note)+"    撤销",()=>Change(()=>store.UndoAdjustment(item.Id),"修正已撤销")));
            }
            Body.Children.Add(new Expander { Header="手动修正",Content=corrections,Margin=new Thickness(0,0,0,18) });
        }
        var hours=Statistics.Hours(report,selectedDay,selectedApp);
        var chart=new Chart { Margin=new Thickness(0,20,0,0),Bars=hours.Select(h=>new Bar(h.Label,h.Duration==0 && !exists ? null : h.Duration)).ToList() };
        Body.Children.Add(Card(Stack(Text("时间分布",17),chart)));
        long recorded=hours.Sum(h=>h.Duration);
        if (report.Device.Platform=="android" || daily!=recorded)
        {
            var note=new Expander { Header="分布说明",Margin=new Thickness(0,0,0,18),Content=Text("时段已记录 "+Statistics.Duration(recorded)+"。系统汇总和手动调整没有可分配的事件时段。",13,"Muted") }; Body.Children.Add(note);
        }
        var apps=report.Apps.Where(a=>(selectedApp==null || a.Id==selectedApp) && a.Days.GetValueOrDefault(selectedDay)>0).OrderByDescending(a=>a.Days.GetValueOrDefault(selectedDay));
        foreach (var app in apps) Body.Children.Add(Action(app.Name+"                 "+Statistics.Duration(app.Days[selectedDay]),()=> { selectedApp=app.Id; Draw(true); }));
        var timeline=new StackPanel(); int shown=0;
        long start=TimeZones.Midnight(selectedDay,report.Device.Timezone),end=TimeZones.Midnight(selectedDay.AddDays(1),report.Device.Timezone);
        foreach (var item in report.Apps.Where(a=>selectedApp==null || a.Id==selectedApp).SelectMany(a=>a.Spans.Select(s=>new { App=a,Span=s })).Where(i=>i.Span.Start<end && i.Span.End>start).OrderBy(i=>i.Span.Start).Take(200))
        {
            long from=Math.Max(start,item.Span.Start),to=Math.Min(end,item.Span.End);
            string Local(long t)=>TimeZoneInfo.ConvertTime(DateTimeOffset.FromUnixTimeMilliseconds(t),TimeZones.Find(report.Device.Timezone)).ToString("HH:mm:ss zzz");
            var row=Text(Local(from)+" – "+Local(to)+"    "+item.App.Name+"    "+Statistics.Duration(to-from),13,"Muted"); row.Margin=new Thickness(0,8,0,8); timeline.Children.Add(row); shown++;
        }
        if (shown>0) Body.Children.Add(new Expander { Header="时段明细（最多 200 段）",Content=timeline,Margin=new Thickness(0,12,0,0) });
    }
    private void Devices()
    {
        PageTitle.Text="设备";
        foreach (DeviceChoice choice in DevicePicker.Items)
        {
            var panel=Stack(Text(choice.Device.Id==store.LocalDeviceId ? Environment.MachineName : choice.Device.Platform=="android" ? "Android" : "Windows",19),Text(choice.Device.Id==store.LocalDeviceId ? "本机" : "导入档案",13,"Muted"));
            var info=new Expander { Header="档案信息",Margin=new Thickness(0,18,0,0),Content=Text(choice.Device.Id+"\n"+choice.Device.Timezone+"\n档案创建："+TimeZones.Date(choice.Device.CreatedAt,choice.Device.Timezone).ToString("yyyy-MM-dd"),12,"Muted") }; panel.Children.Add(info);
            panel.Children.Add(Action("查看用时",()=>DevicePicker.SelectedItem=choice)); Body.Children.Add(Card(panel));
        }
    }
    private void Settings()
    {
        PageTitle.Text="设置";
        var idle=new ComboBox { Margin=new Thickness(0,10,0,18),ItemsSource=new[]{"关闭","1 分钟","3 分钟","5 分钟","10 分钟","15 分钟"} };
        int[] values=[0,1,3,5,10,15]; int current=int.TryParse(store.Setting("idle_minutes"),out int minutes) ? minutes : 5; idle.SelectedIndex=Array.IndexOf(values,current); AutomationProperties.SetName(idle,"空闲暂停阈值");
        idle.SelectionChanged+=(_,_)=> { if (idle.SelectedIndex>=0) { store.SetSetting("idle_minutes",values[idle.SelectedIndex].ToString()); tracker.Configure(); } };
        var auto=new CheckBox { Content="登录 Windows 时启动",IsChecked=StartupEnabled() };
        auto.Click+=(_,_)=>
        {
            try { using var key=Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run"); if (auto.IsChecked==true) key.SetValue("AppTime","\""+Environment.ProcessPath+"\" --tray"); else key.DeleteValue("AppTime",false); }
            catch (Exception e) when (e is UnauthorizedAccessException or System.Security.SecurityException or IOException) { auto.IsChecked=StartupEnabled(); Message("无法修改开机启动设置"); }
        };
        Body.Children.Add(Card(Stack(Text("记录",18),Text("空闲暂停",14),idle,auto)));
        var themes=new ComboBox { ItemsSource=new[]{"跟随系统","浅色","深色"},SelectedIndex=store.Setting("theme") switch { "light"=>1,"dark"=>2,_=>0 },Margin=new Thickness(0,12,0,12) }; AutomationProperties.SetName(themes,"应用主题");
        themes.SelectionChanged+=(_,_)=> { store.SetSetting("theme",themes.SelectedIndex switch { 1=>"light",2=>"dark",_=>"system" }); ApplyTheme(); Draw(false); };
        Body.Children.Add(Card(Stack(Text("外观",18),themes)));
        var zone=new TextBox { Text=store.LocalTimezone,Margin=new Thickness(0,12,0,12) }; AutomationProperties.SetName(zone,"本机报表时区，IANA 名称");
        Body.Children.Add(Card(Stack(Text("本机报表时区",18),zone,Action("应用时区",()=>
        {
            try { tracker.Flush(); store.ChangeTimezone(zone.Text.Trim()); tracker.Configure(); loadedVersion=-1; Message("时区已更新，报表会重新计算"); }
            catch (Exception e) when (e is TimeZoneNotFoundException or InvalidTimeZoneException or ArgumentException) { Message("时区无效，例如 Asia/Shanghai"); }
        }))));
        Body.Children.Add(Card(Stack(Text("数据",18),Action("导出加密备份",ExportBackup),Action("导入备份",ImportBackup),Action("打开数据目录",()=>Launch(store.DirectoryPath)))));
        Body.Children.Add(Card(Stack(Text("关于 AppTime",20),Text("Windows "+typeof(MainWindow).Assembly.GetName().Version?.ToString(3),13,"Muted"),Action("GitHub 仓库",()=>Launch("https://github.com/silent07137/AppTime")),Action("开源许可证 · GPLv2",()=>License("LICENSE.txt","GPL-2.0-only")),Action("第三方许可证",()=>License("THIRD_PARTY_NOTICES.txt","第三方许可")))));
    }
    private async void Change(Action action,string success)
    {
        if (busy) return; busy=true;
        try { await Task.Run(action); await Reload(); Draw(false); Message(success); }
        catch (InvalidDataException e) { await Reload(); Draw(false); Message(e.Message); }
        catch (Exception e) when (e is IOException or Microsoft.Data.Sqlite.SqliteException) { await Reload(); Draw(false); Message("保存失败，请检查数据目录和磁盘空间"); }
        finally { busy=false; }
    }
    private Window Editor(string title,StackPanel content)=>new() { Owner=this,Title=title,Width=380,SizeToContent=SizeToContent.Height,ResizeMode=ResizeMode.NoResize,WindowStartupLocation=WindowStartupLocation.CenterOwner,Background=Brush("Background"),Foreground=Brush("Foreground"),Content=new Border { Padding=new Thickness(24),Child=content } };
    private void EditCategory(AppUsage app)
    {
        var input=new TextBox { Text=app.Category,MaxLength=24,Margin=new Thickness(0,12,0,18) }; AutomationProperties.SetName(input,"分类名称");
        var body=Stack(Text("分类名称",16),input); var window=Editor("修改分类",body);
        body.Children.Add(Action("保存",()=> { if (string.IsNullOrWhiteSpace(input.Text)) return; window.DialogResult=true; }));
        if (window.ShowDialog()==true) { string value=input.Text; Change(()=>store.UpdatePreference(app.Id,category:value),"分类已保存"); }
    }
    private void EditAdjustment(string id,DateOnly date)
    {
        var minutes=new TextBox { Margin=new Thickness(0,10,0,18) }; var note=new TextBox { MaxLength=120,Margin=new Thickness(0,10,0,18) };
        AutomationProperties.SetName(minutes,"修正分钟，正数补记，负数扣减"); AutomationProperties.SetName(note,"修正备注");
        var warning=Text("",13,"Muted"); warning.TextWrapping=TextWrapping.Wrap;
        var body=Stack(Text(date.ToString("yyyy-MM-dd"),18),Text("分钟（正数补记，负数扣减）"),minutes,Text("备注（可选）"),note,warning); var window=Editor("修正用时",body); long delta=0;
        body.Children.Add(Action("保存",()=>
        {
            if (!long.TryParse(minutes.Text,out long value) || value==0 || value is < -1440 or >1440) { warning.Text="请输入 −1440 至 1440 内的非零整数"; return; }
            delta=value*60000; window.DialogResult=true;
        }));
        if (window.ShowDialog()==true) { string value=note.Text; Change(()=>store.AddAdjustment(id,date,delta,value,DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()),"修正已保存，原始时段保留"); }
    }
    private bool StartupEnabled() { using var key=Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run"); return key?.GetValue("AppTime") is string; }
    private void ApplyTheme()
    {
        string? preference=store.Setting("theme"); bool dark=preference=="dark";
        if (preference==null || preference=="system") { using var key=Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"); dark=key?.GetValue("AppsUseLightTheme") is int i && i==0; }
        string[] keys=["Background","Surface","Foreground","Muted","Accent","Tint","Line"];
        string[] colors=dark ? ["#171923","#222532","#EDF0FA","#A7ADC5","#B0AAFF","#333050","#3A3E50"] : ["#F7F8FC","#FFFFFF","#202337","#73788E","#6861D8","#ECEBFB","#E4E6F0"];
        for (int i=0;i<keys.Length;i++) Application.Current.Resources[keys[i]]=new SolidColorBrush((Color)ColorConverter.ConvertFromString(colors[i]));
        foreach (var key in new[]{SystemColors.WindowBrushKey,SystemColors.ControlBrushKey}) Application.Current.Resources[key]=Application.Current.Resources["Surface"];
        foreach (var key in new[]{SystemColors.WindowTextBrushKey,SystemColors.ControlTextBrushKey}) Application.Current.Resources[key]=Application.Current.Resources["Foreground"];
    }
    private void License(string file,string title)
    {
        var text=new TextBox { Text=File.ReadAllText(Path.Combine(AppContext.BaseDirectory,file)),IsReadOnly=true,TextWrapping=TextWrapping.Wrap,VerticalScrollBarVisibility=ScrollBarVisibility.Auto };
        new Window { Owner=this,Title=title,Width=660,Height=580,Content=text,WindowStartupLocation=WindowStartupLocation.CenterOwner }.ShowDialog();
    }
    private void Launch(string target)
    { try { Process.Start(new ProcessStartInfo(target) { UseShellExecute=true }); } catch (Win32Exception) { Message("无法打开，请检查系统默认程序"); } }
    private void Message(string text) { Notice.Text=text; Notice.Visibility=Visibility.Visible; }
    private async void ExportBackup()
    {
        if (busy) return;
        var dialog=new PasswordDialog(this,true); if (dialog.ShowDialog()!=true) return;
        var file=new SaveFileDialog { Filter="AppTime 备份|*.atbackup",FileName="AppTime-Windows-"+DateTime.Now.ToString("yyyy-MM-dd")+".atbackup" };
        if (file.ShowDialog(this)!=true) return;
        string password=dialog.Value;
        busy=true; Message("正在加密备份…");
        try { tracker.Flush(); await Task.Run(()=>Backup.Export(store,file.FileName,password)); Message("备份已保存，请保管口令"); }
        catch (Exception e) when (e is IOException or InvalidDataException or CryptographicException or Microsoft.Data.Sqlite.SqliteException or UnauthorizedAccessException) { Message("备份失败："+(e is InvalidDataException ? e.Message : "请检查文件权限和磁盘空间")); }
        finally { busy=false; }
    }
    private async void ImportBackup()
    {
        if (busy) return;
        var file=new OpenFileDialog { Filter="AppTime 备份|*.atbackup" }; if (file.ShowDialog(this)!=true) return;
        var dialog=new PasswordDialog(this,false); if (dialog.ShowDialog()!=true) return;
        string password=dialog.Value;
        busy=true; Message("正在验证备份…");
        try
        {
            var plan=await Task.Run(()=>Backup.Read(file.FileName,password));
            if (MessageBox.Show(this,$"{plan.Devices} 台设备 · {plan.Apps} 个应用 · {plan.Sessions} 条会话\n\n合并到当前档案？将先保存恢复前的加密备份。","恢复预览",MessageBoxButton.OKCancel,MessageBoxImage.Question)!=MessageBoxResult.OK) { Message("已取消导入"); return; }
            tracker.Flush();
            string directory=Path.Combine(store.DirectoryPath,"Backups"); Directory.CreateDirectory(directory);
            string protection=Path.Combine(directory,"before-restore-"+DateTime.Now.ToString("yyyyMMdd-HHmmss")+"-"+Guid.NewGuid().ToString("N")[..8]+".atbackup");
            int added=await Task.Run(()=> { Backup.Export(store,protection,password); return store.Merge(plan.Data); });
            tracker.Configure();
            await Reload(true); Draw(false); Message($"导入完成 · {added} 条新增或更新；保护备份已保存到 Backups");
        }
        catch (CryptographicException) { Message("口令不正确或备份已损坏，档案未修改"); }
        catch (Exception e) when (e is IOException or InvalidDataException or JsonException or FormatException or InvalidOperationException or KeyNotFoundException or Microsoft.Data.Sqlite.SqliteException or ArgumentException or OverflowException or UnauthorizedAccessException or TimeZoneNotFoundException or InvalidTimeZoneException)
        { Message(e is InvalidDataException ? e.Message : "无法导入此备份，档案未修改"); }
        finally { busy=false; }
    }
    // Render our own test window, without capturing other applications or importing user data.
    public async void RunSmoke(string directory)
    {
        try
        {
            store.SetSetting("idle_minutes","0"); tracker.Configure();
            await Task.Delay(1000); await Reload(true);
            if (report==null) throw new Exception("Report unavailable");
            // Dedicated test archive only. Synthetic rows never enter a normal user archive.
            if (store.Snapshot()["sessions"].Count==0)
            {
                var rows=new List<Session>();
                for (int i=0;i<7;i++)
                {
                    long t=TimeZones.Midnight(Today.AddDays(-i),store.LocalTimezone)+10*3600000;
                    int offset=(int)TimeZones.Find(store.LocalTimezone).GetUtcOffset(DateTimeOffset.FromUnixTimeMilliseconds(t)).TotalSeconds;
                    rows.Add(new(Guid.NewGuid().ToString(),new AppIdentity("selftest:editor","编辑器"),t,t+(25+i*7)*60000,store.LocalTimezone,offset));
                    rows.Add(new(Guid.NewGuid().ToString(),new AppIdentity("selftest:browser","浏览器"),t+4*3600000,t+4*3600000+(16+i*4)*60000,store.LocalTimezone,offset));
                }
                store.Save((rows,[])); await Reload(true);
            }
            for (int i=0;i<5;i++) { page=i; Draw(false); UpdateLayout(); await Task.Delay(100); Render(Path.Combine(directory,$"page-{i}.png")); }
            selectedDay=Today; page=5; Draw(false); UpdateLayout(); Render(Path.Combine(directory,"day.png"));
            selectedApp=report.Apps.First().Id; page=6; Draw(false);
            foreach (var expander in Body.Children.OfType<Expander>()) expander.IsExpanded=true;
            UpdateLayout(); Render(Path.Combine(directory,"app-management.png"));
            page=5; Draw(false);
            foreach (var expander in Body.Children.OfType<Expander>()) expander.IsExpanded=true;
            UpdateLayout(); Render(Path.Combine(directory,"corrections.png")); selectedApp=null;
            Activate(); await Task.Delay(300);
            var native=new NativeSource().Read();
            var adapter=new NativeSource(); var self=adapter.ReadWindow(new System.Windows.Interop.WindowInteropHelper(this).Handle);
            if (self==null || !self.Key.StartsWith("exe:apptime.exe:")) throw new Exception("Native process identity could not resolve test window");
            bool foregroundVerified=native.App?.Key==self.Key;
            await Task.Delay(1600); tracker.Flush();
            var data=store.Snapshot();
            if (data["sessions"].Count==0) throw new Exception("No persisted foreground interval");
            var realIds=data["app_identities"].Where(r=>Store.S(r,"packageName")==self.Key).Select(r=>Store.S(r,"identityId")).ToHashSet();
            long liveMs=data["sessions"].Where(r=>realIds.Contains(Store.S(r,"identityId"))).Sum(r=>Store.N(r,"durationMs"));
            if (foregroundVerified && liveMs<=0) throw new Exception("Native foreground polling did not persist the test application");
            Width=900; Height=600; page=0; Draw(false); UpdateLayout(); Render(Path.Combine(directory,"compact.png"));
            store.SetSetting("theme","light"); ApplyTheme(); Draw(false); UpdateLayout(); Render(Path.Combine(directory,"light.png"));
            store.SetSetting("theme","dark"); ApplyTheme(); page=4; Draw(false); UpdateLayout(); var scroller=(ScrollViewer)Body.Parent; scroller.ScrollToEnd(); UpdateLayout(); Render(Path.Combine(directory,"about.png"));
            Hide(); var process=Process.GetCurrentProcess(); var cpu=process.TotalProcessorTime; var clock=Stopwatch.StartNew(); await Task.Delay(15000); process.Refresh();
            File.WriteAllText(Path.Combine(directory,"smoke.json"),JsonSerializer.Serialize(new { pages=8,nativeWindowIdentity=true,foregroundSamplingVerified=foregroundVerified && liveMs>0,liveForegroundMilliseconds=liveMs,sessions=data["sessions"].Count,workingSetBytes=process.WorkingSet64,cpuPercent=(process.TotalProcessorTime-cpu).TotalMilliseconds/clock.Elapsed.TotalMilliseconds*100,window=new { width=ActualWidth,height=ActualHeight } }));
            exiting=true; refresh.Stop(); tray.Dispose(); Close(); Application.Current.Shutdown();
        }
        catch (Exception e) { File.WriteAllText(Path.Combine(directory,"smoke-error.txt"),e.ToString()); Application.Current.Shutdown(1); }
    }
    private void Render(string file)
    {
        var bitmap=new RenderTargetBitmap((int)ActualWidth,(int)ActualHeight,96,96,PixelFormats.Pbgra32); bitmap.Render(this); var encoder=new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap)); using var stream=File.Create(file); encoder.Save(stream);
    }
}
