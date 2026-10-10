// SPDX-License-Identifier: GPL-2.0-only
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using AppTime.Core;
namespace AppTime.Desktop;

public record Bar(string Label,long? Value,Action? Click=null);
public sealed class Chart : FrameworkElement
{
    public List<Bar> Bars { get; set; }=[];
    public Chart() { Height=170; Focusable=true; MouseMove+=Moved; MouseLeftButtonUp+=Clicked; }
    protected override void OnRender(DrawingContext dc)
    {
        base.OnRender(dc); if (Bars.Count==0) return;
        var accent=(Brush)FindResource("Accent"); var muted=(Brush)FindResource("Muted"); var line=(Brush)FindResource("Line");
        double width=ActualWidth/Bars.Count,baseline=ActualHeight-30;
        long max=Math.Max(60000,Bars.Max(b=>b.Value ?? 0));
        dc.DrawLine(new Pen(line,1),new Point(0,baseline),new Point(ActualWidth,baseline));
        for (int i=0;i<Bars.Count;i++)
        {
            var bar=Bars[i]; double h=bar.Value==null ? 0 : Math.Max(bar.Value>0 ? 3 : 0,(baseline-10)*bar.Value.Value/max);
            if (h>0) dc.DrawRoundedRectangle(accent,null,new Rect(i*width+width*0.19,baseline-h,width*0.62,h),4,4);
            if (Bars.Count<=10 || i%4==0 || i==Bars.Count-1)
            {
                string label=bar.Label.Split(' ')[0];
                var text=new FormattedText(label,CultureInfo.GetCultureInfo("zh-CN"),FlowDirection.LeftToRight,new Typeface("Segoe UI"),11,muted,VisualTreeHelper.GetDpi(this).PixelsPerDip);
                dc.DrawText(text,new Point(i*width+(width-text.Width)/2,baseline+9));
            }
        }
        if (Bars.All(b=>b.Value==null))
        {
            var text=new FormattedText("暂无记录",CultureInfo.GetCultureInfo("zh-CN"),FlowDirection.LeftToRight,new Typeface("Microsoft YaHei UI"),14,muted,VisualTreeHelper.GetDpi(this).PixelsPerDip);
            dc.DrawText(text,new Point((ActualWidth-text.Width)/2,55));
        }
    }
    private int Index(MouseEventArgs e) => Bars.Count==0 || ActualWidth<=0 ? -1 : Math.Clamp((int)(e.GetPosition(this).X/ActualWidth*Bars.Count),0,Bars.Count-1);
    private void Moved(object sender,MouseEventArgs e)
    {
        int index=Index(e); if (index<0) return;
        var b=Bars[index]; ToolTip=b.Label+" · "+(b.Value==null ? "暂无记录" : Statistics.Duration(b.Value.Value)); Cursor=b.Click!=null ? Cursors.Hand : Cursors.Arrow;
    }
    private void Clicked(object sender,MouseButtonEventArgs e) { int index=Index(e); if (index>=0) Bars[index].Click?.Invoke(); }
}
