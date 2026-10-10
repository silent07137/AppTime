// SPDX-License-Identifier: GPL-2.0-only
using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Automation;
namespace AppTime.Desktop;

public sealed class PasswordDialog : Window
{
    private readonly PasswordBox password=new() { Margin=new Thickness(0,10,0,14),MinHeight=40 };
    private readonly PasswordBox confirm=new() { Margin=new Thickness(0,10,0,14),MinHeight=40 };
    public string Value => password.Password;
    public PasswordDialog(Window owner,bool export)
    {
        Style=(Style)Application.Current.FindResource(typeof(Window)); Owner=owner; Title=export ? "备份口令" : "解锁备份"; Width=390; SizeToContent=SizeToContent.Height; ResizeMode=ResizeMode.NoResize; WindowStartupLocation=WindowStartupLocation.CenterOwner;
        var body=new StackPanel { Margin=new Thickness(26) }; Content=body;
        body.Children.Add(new TextBlock { Text="口令",FontSize=18 }); body.Children.Add(password); AutomationProperties.SetName(password,"备份口令");
        if (export) { body.Children.Add(new TextBlock { Text="确认口令" }); body.Children.Add(confirm); AutomationProperties.SetName(confirm,"确认备份口令"); }
        var error=new TextBlock { Margin=new Thickness(0,0,0,12) }; body.Children.Add(error);
        var button=new Button { Content="继续",IsDefault=true,HorizontalContentAlignment=HorizontalAlignment.Center };
        button.Click+=(_,_)=>
        {
            if (password.Password.Length is <8 or >256) { error.Text="请使用 8–256 个字符"; return; }
            if (export && password.Password!=confirm.Password) { error.Text="两次口令不一致"; return; }
            DialogResult=true;
        };
        body.Children.Add(button); Loaded+=(_,_)=>password.Focus();
    }
}
