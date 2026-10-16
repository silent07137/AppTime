// SPDX-License-Identifier: GPL-2.0-only
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using AppTime.Core;

namespace AppTime.Desktop;

public sealed class AppIcons
{
    private readonly Store store;
    private readonly object gate=new();
    private readonly Dictionary<string,Entry> cache=[];
    private sealed record Entry(AppAppearance Appearance,ImageSource Image);
    public sealed record Value(AppIcons Owner,string Id) { public ImageSource Source => Owner.Read(Id); }
    public ImageSource Fallback { get; }=Bitmap(System.Drawing.SystemIcons.Application);

    public AppIcons(Store store) { this.store=store; }
    public Value For(string id)=>new(this,id);
    public Binding Binding(string path)=>new(path) { IsAsync=true,Mode=BindingMode.OneWay,FallbackValue=Fallback,TargetNullValue=Fallback };
    public Image Image(string id,double size)
    {
        var image=new Image { Width=size,Height=size,Stretch=Stretch.Uniform,DataContext=For(id),VerticalAlignment=VerticalAlignment.Center };
        image.SetBinding(System.Windows.Controls.Image.SourceProperty,Binding(nameof(Value.Source)));
        return image;
    }
    // WPF requests Source asynchronously for visible rows. Extraction is serialized and cached.
    public ImageSource Read(string id)
    {
        try
        {
            var appearance=store.Appearance(id);
            lock (gate)
            {
                if (cache.TryGetValue(id,out var old) && old.Appearance.ExecutablePath==appearance.ExecutablePath &&
                    (old.Appearance.CustomIcon==null && appearance.CustomIcon==null || old.Appearance.CustomIcon!=null && appearance.CustomIcon!=null && old.Appearance.CustomIcon.AsSpan().SequenceEqual(appearance.CustomIcon)))
                    return old.Image;
                ImageSource image=Fallback;
                try
                {
                    if (appearance.CustomIcon is byte[] png) image=Decode(png);
                    else if (appearance.ExecutablePath is string path) image=Executable(path);
                }
                catch (Exception e) when (Unreadable(e)) { }
                if (cache.Count>=256) cache.Remove(cache.Keys.First());
                cache[id]=new(appearance,image);
                return image;
            }
        }
        catch (Exception e) when (e is Microsoft.Data.Sqlite.SqliteException or InvalidOperationException) { return Fallback; }
    }
    public void Choose(string id,string path)
    {
        var image=Executable(path);
        var encoder=new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(image));
        using var output=new MemoryStream(); encoder.Save(output); store.SetAppIcon(id,output.ToArray());
    }
    public static BitmapSource Executable(string path)
    {
        if (!Path.IsPathFullyQualified(path) || !Path.GetExtension(path).Equals(".exe",StringComparison.OrdinalIgnoreCase) || !File.Exists(path))
            throw new InvalidDataException("请选择有效的 EXE 文件");
        try
        {
            // Read the resource without loading or starting the executable.
            using var icon=System.Drawing.Icon.ExtractIcon(path,0,64);
            return icon==null ? throw new InvalidDataException("此程序没有可读取的图标") : Bitmap(icon);
        }
        catch (Exception e) when (Unreadable(e)) { throw new InvalidDataException("无法读取此程序的图标",e); }
    }
    private static BitmapSource Bitmap(System.Drawing.Icon icon)
    {
        var image=Imaging.CreateBitmapSourceFromHIcon(icon.Handle,Int32Rect.Empty,BitmapSizeOptions.FromEmptyOptions());
        image.Freeze(); return image;
    }
    private static BitmapSource Decode(byte[] png)
    {
        using var input=new MemoryStream(png,false);
        var decoder=new PngBitmapDecoder(input,BitmapCreateOptions.PreservePixelFormat,BitmapCacheOption.OnLoad);
        var image=decoder.Frames[0]; image.Freeze(); return image;
    }
    private static bool Unreadable(Exception e)=>e is IOException or FileFormatException or UnauthorizedAccessException or ArgumentException or NotSupportedException or ExternalException or InvalidOperationException;
}
