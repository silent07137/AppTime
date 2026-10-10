// SPDX-License-Identifier: GPL-2.0-only
using System.Security.Cryptography;
using System.IO.Compression;
using System.Text.Json;
using AppTime.Core;
using static AppTime.Core.Store;

int passed=0;
void Check(bool value,string detail="assertion failed") { if (!value) throw new Exception(detail); }
void Test(string name,Action test) { test(); passed++; Console.WriteLine("PASS "+name); }
var a=new AppIdentity("exe:test-a","A"); var b=new AppIdentity("exe:test-b","B");
const long epoch=1767225600000;
Collector Engine()=>new() { Timezone="Asia/Shanghai",IdleThresholdMs=0 };
List<Session> Run(Collector engine,int seconds,Func<int,AppIdentity> app)
{ for (int s=0;s<=seconds;s++) engine.Poll(new(epoch+s*1000,s*1000,0,app(s))); engine.Break(); return engine.Drain().Sessions; }
string root=Path.Combine(Path.GetTempPath(),"AppTime-tests-"+Guid.NewGuid().ToString("N")); Directory.CreateDirectory(root);
const string password="AppTime-备份-golden-123";
try
{
    Test("A60 B30 A10",()=>
    {
        var rows=Run(Engine(),100,s=>s<60 ? a : s<90 ? b : a);
        Check(rows.Where(s=>s.App==a).Sum(s=>s.EndMs-s.StartMs)==70000); Check(rows.Where(s=>s.App==b).Sum(s=>s.EndMs-s.StartMs)==30000);
    });
    Test("idle stops exactly at threshold and resumes on input",()=>
    {
        var c=Engine(); c.IdleThresholdMs=6000;
        for (int i=0;i<=8;i++) c.Poll(new(epoch+i*1000,i*1000,i*1000,a));
        c.Poll(new(epoch+9000,9000,0,a)); c.Poll(new(epoch+10000,10000,1000,a)); c.Break();
        Check(c.Drain().Sessions.Sum(s=>s.EndMs-s.StartMs)==7000);
    });
    Test("partial idle boundary is clipped",()=>
    { var c=Engine(); c.IdleThresholdMs=1500; c.Poll(new(epoch,0,0,a)); c.Poll(new(epoch+1000,1000,1000,a)); c.Poll(new(epoch+2000,2000,2000,a)); Check(c.Drain().Sessions.Sum(s=>s.EndMs-s.StartMs)==1500); });
    Test("sampling gap does not extrapolate",()=>
    { var c=Engine(); c.Poll(new(epoch,0,0,a)); c.Poll(new(epoch+1000,1000,0,a)); c.Poll(new(epoch+11000,11000,0,a)); var d=c.Drain(); Check(d.Sessions.Sum(s=>s.EndMs-s.StartMs)==1000); Check(d.Gaps.Single().EndMs-d.Gaps.Single().StartMs==10000); });
    Test("clock jump uses monotonic duration and cuts boundary",()=>
    { var c=Engine(); c.Poll(new(epoch,0,0,a)); c.Poll(new(epoch+1000,1000,0,a)); c.Poll(new(epoch+3602000,2000,0,a)); c.Poll(new(epoch+3603000,3000,0,a)); Check(c.Drain().Sessions.Sum(s=>s.EndMs-s.StartMs)==2000); });
    Test("unknown process is skipped",()=>
    { var c=Engine(); c.Poll(new(epoch,0,0,a)); c.Poll(new(epoch+1000,1000,0,null)); c.Poll(new(epoch+2000,2000,0,a)); c.Poll(new(epoch+3000,3000,0,a)); Check(c.Drain().Sessions.Sum(s=>s.EndMs-s.StartMs)==1000); });
    Test("lock and pause signals do not continue tails",()=>
    { var c=Engine(); c.Poll(new(epoch,0,0,a)); c.Poll(new(epoch+1000,1000,0,a,"锁屏暂停")); c.Poll(new(epoch+2000,2000,0,a,"锁屏暂停")); c.Break(); c.Poll(new(epoch+100000,100000,0,a)); c.Poll(new(epoch+101000,101000,0,a)); Check(c.Drain().Sessions.Sum(s=>s.EndMs-s.StartMs)==2000); });
    Test("checkpoint updates same session, without duplicate total",()=>
    { var c=Engine(); c.Poll(new(epoch,0,0,a)); c.Poll(new(epoch+1000,1000,0,a)); var one=c.Drain().Sessions.Single(); c.Poll(new(epoch+2000,2000,0,a)); var two=c.Drain().Sessions.Single(); Check(one.Id==two.Id && two.Revision>one.Revision && two.EndMs-two.StartMs==2000); });
    Test("midnight split conserves duration",()=>
    { long start=TimeZones.Midnight(new(2026,10,2),"Asia/Shanghai")-10000; var pieces=TimeZones.Split(start,start+30000,"Asia/Shanghai").ToList(); Check(pieces.Count==2 && pieces.Sum(s=>s.End-s.Start)==30000); });
    Test("DST 23 and 25 hour days",()=>
    { string zone="America/New_York"; Check(TimeZones.Midnight(new(2026,3,9),zone)-TimeZones.Midnight(new(2026,3,8),zone)==23*3600000L); Check(TimeZones.Midnight(new(2026,11,2),zone)-TimeZones.Midnight(new(2026,11,1),zone)==25*3600000L); });
    Test("Windows rollback keeps distinct monotonic episodes in reports",()=>
    {
        var c=Engine(); c.Poll(new(epoch,0,0,a)); c.Poll(new(epoch+5000,5000,0,a));
        c.Poll(new(epoch+1000,6000,0,a)); c.Poll(new(epoch+2000,7000,0,a));
        using var rollback=new Store(Path.Combine(root,"rollback.sqlite")); rollback.Save(c.Drain());
        var r=Statistics.Read(rollback,rollback.LocalDeviceId); var date=TimeZones.Date(epoch,r.Device.Timezone);
        Check(r.Total==6000 && r.Day(date)==6000 && Statistics.Hours(r,date).Sum(h=>h.Duration)==6000);
    });
    Test("overlapping sessions are unioned",()=>Check(TimeZones.Union([(0L,100L),(50L,150L)]).Single()==(0L,150L)));
    Test("ignored range subtraction",()=>Check(Statistics.Subtract([(0L,100L)],[(30L,70L)]).Sum(s=>s.End-s.Start)==60));
    Test("ignored collector does not record and resumes without filling the gap",()=>
    {
        var c=Engine(); c.Poll(new(epoch,0,0,a)); c.Poll(new(epoch+1000,1000,0,a)); c.Break();
        c.IgnoredKeys=[a.Key]; c.Poll(new(epoch+1000,1000,0,a)); c.Poll(new(epoch+4000,4000,0,a)); c.Break();
        Check(c.Status=="已忽略"); c.IgnoredKeys=[];
        c.Poll(new(epoch+4000,4000,0,a)); c.Poll(new(epoch+5000,5000,0,a));
        var rows=c.Drain(); Check(rows.Sessions.Sum(s=>s.EndMs-s.StartMs)==2000 && rows.Gaps.Count==0);
    });
    string managedPath=Path.Combine(root,"management.sqlite"); string managedId;
    using (var managed=new Store(managedPath))
    {
        managed.ChangeTimezone("UTC"); managed.Save(([new(Guid.NewGuid().ToString(),a,0,60000,"UTC",0),new(Guid.NewGuid().ToString(),a,0,20000,"UTC",0)],[]));
        managedId=Statistics.Read(managed,managed.LocalDeviceId).Apps.Single().Id;
        Test("classify and hide retain usage with idempotent preference revisions",()=>
        {
            managed.UpdatePreference(managedId,"学习",true); long revision=managed.Preference(managedId).Revision;
            managed.UpdatePreference(managedId,"学习",true); Check(managed.Preference(managedId).Revision==revision);
            var report=Statistics.Read(managed,managed.LocalDeviceId); Check(report.Total==80000 && report.Apps.Single() is { Category:"学习",Hidden:true });
        });
        Test("ignore periods survive resume and preserve raw sessions",()=>
        {
            managed.SetIgnored(managedId,true,10000); Check(managed.IgnoredKeys().Contains(a.Key));
            managed.SetIgnored(managedId,true,11000); managed.SetIgnored(managedId,false,15000);
            Check(managed.Snapshot()["ignore_periods"].Count==1 && managed.Snapshot()["sessions"].Count==2);
            Check(managed.IgnoredKeys().Count==0 && Statistics.Read(managed,managed.LocalDeviceId).Total==70000);
        });
        Test("manual corrections are separate from hourly distribution and reversible",()=>
        {
            var date=new DateOnly(1970,1,1); managed.AddAdjustment(managedId,date,60000,"补记",100000);
            managed.AddAdjustment(managedId,date,-30000,"扣减",100001);
            var report=Statistics.Read(managed,managed.LocalDeviceId); Check(report.Total==100000 && report.Day(date)==100000 && Statistics.Hours(report,date).Sum(h=>h.Duration)==70000);
            var minus=managed.Adjustments(managedId,date).Single(a=>a.DeltaMs<0); managed.UndoAdjustment(minus.Id); managed.UndoAdjustment(minus.Id);
            Check(Statistics.Read(managed,managed.LocalDeviceId).Total==130000 && managed.Snapshot()["manual_adjustments"].Count==2);
            Check(managed.Snapshot()["manual_adjustments"].Any(r=>N(r,"deleted")==1 && N(r,"revision")==2));
        });
        Test("invalid correction rolls back without altering records",()=>
        {
            int before=managed.Snapshot()["manual_adjustments"].Count;
            bool failed=false; try { managed.AddAdjustment(managedId,new(1970,1,2),-60000,"",100000); } catch (InvalidDataException) { failed=true; }
            Check(failed && managed.Snapshot()["manual_adjustments"].Count==before);
            failed=false; try { managed.AddAdjustment(managedId,new(3000,1,1),60000,"",100000); } catch (InvalidDataException) { failed=true; } Check(failed);
        });
        Test("correction undo cannot invalidate a dependent deduction",()=>
        {
            var date=new DateOnly(1970,1,2); managed.AddAdjustment(managedId,date,60000,"",86400000); managed.AddAdjustment(managedId,date,-60000,"",86400000);
            var plus=managed.Adjustments(managedId,date).Single(a=>a.DeltaMs>0); bool failed=false;
            try { managed.UndoAdjustment(plus.Id); } catch (InvalidDataException) { failed=true; } Check(failed);
            managed.UndoAdjustment(managed.Adjustments(managedId,date).Single(a=>a.DeltaMs<0).Id); managed.UndoAdjustment(plus.Id);
        });
        Test("managed archive backup preserves revisions and raw history",()=>
        {
            string file=Path.Combine(root,"managed.atbackup"); Backup.Export(managed,file,password); var plan=Backup.Read(file,password);
            using var target=new Store(Path.Combine(root,"managed-target.sqlite")); Check(target.Merge(plan.Data)>0 && target.Merge(plan.Data)==0);
            Check(Statistics.Read(target,plan.Origin).Total==130000 && target.LocalDeviceId!=plan.Origin);
            bool failed=false; try { target.SetIgnored(managedId,true,90000); } catch (InvalidDataException) { failed=true; } Check(failed);
            if (args.Length>1) File.Copy(file,args[1],true); // Explicit public synthetic fixture output only.
        });
        Test("Windows timezone is normalized for Android portability",()=> { managed.ChangeTimezone("China Standard Time"); Check(managed.LocalTimezone=="Asia/Shanghai"); });
    }
    Test("management survives reopening without replacing sessions",()=>
    {
        using var managed=new Store(managedPath); Check(managed.Preference(managedId) is { Category:"学习",Hidden:true,Ignored:false });
        Check(Statistics.Read(managed,managed.LocalDeviceId).Total==130000);
    });
    Test("public Windows fixture retains management and clock rollback duration",()=>
    {
        var plan=Backup.Read(Path.Combine(args.Length>0 ? args[0] : "../test-fixtures/backup-v1","windows-golden.atbackup"),password);
        using var target=new Store(Path.Combine(root,"fixture-windows.sqlite")); target.Merge(plan.Data);
        var report=Statistics.Read(target,plan.Origin); Check(report.Device.Platform=="windows" && report.Total==130000);
        Check(report.Day(new(1970,1,1))==130000 && Statistics.Hours(report,new(1970,1,1)).Sum(h=>h.Duration)==70000);
    });
    if (args.Length>2) Test("real Android re-export of Windows archive preserves device and totals",()=>
    {
        var plan=Backup.Read(args[2],password); using var target=new Store(Path.Combine(root,"android-reexport.sqlite"));
        Check(target.Merge(plan.Data)>0 && target.Merge(plan.Data)==0);
        var windows=target.Devices().Single(d=>d.Platform=="windows" && d.Id!=target.LocalDeviceId);
        var report=Statistics.Read(target,windows.Id); var date=new DateOnly(1970,1,1);
        Check(report.Total==130000 && report.Day(date)==130000 && Statistics.Hours(report,date).Sum(h=>h.Duration)==70000);
        Check(report.Apps.Single() is { Category:"学习",Hidden:true });
    });
    string path=Path.Combine(root,"archive.sqlite"); string local;
    using (var store=new Store(path))
    {
        local=store.LocalDeviceId;
        Test("SQLite persistence and day/hour conservation",()=>
        {
            store.Save((Run(Engine(),100,s=>s<60 ? a : s<90 ? b : a),[]));
            var r=Statistics.Read(store,local); Check(r.Total==100000); var day=TimeZones.Date(epoch,r.Device.Timezone);
            Check(r.Day(day)==100000 && Statistics.Hours(r,day).Sum(h=>h.Duration)==100000);
        });
        Test("upsert does not add the same session twice",()=>
        { var c=Engine(); c.Poll(new(epoch+200000,0,0,a)); c.Poll(new(epoch+201000,1000,0,a)); var batch=c.Drain(); store.Save(batch); store.Save(batch); Check(Statistics.Read(store,local).Total==101000); });
        Test("encrypted backup round trip and randomness",()=>
        {
            string file=Path.Combine(root,"roundtrip.atbackup"); Backup.Export(store,file,password); var plan=Backup.Read(file,password);
            Check(plan.Origin==local && plan.Sessions==4); byte[] zipped=Backup.Zip(plan.Data,local);
            byte[] x=Backup.Encrypt(zipped,password),y=Backup.Encrypt(zipped,password); Check(!x.SequenceEqual(y)); Check(Backup.Decrypt(x,password).SequenceEqual(zipped));
        });
        Test("wrong password and tamper fail authentication",()=>
        {
            var bytes=File.ReadAllBytes(Path.Combine(root,"roundtrip.atbackup"));
            bool failed=false; try { Backup.Decrypt(bytes,"wrong-password"); } catch (CryptographicException) { failed=true; } Check(failed);
            bytes[^1]^=1; failed=false; try { Backup.Decrypt(bytes,password); } catch (CryptographicException) { failed=true; } Check(failed);
        });
        Test("Android golden import and duplicate merge",()=>
        {
            string fixture=args.Length>0 ? args[0] : "../test-fixtures/backup-v1";
            var plan=Backup.Read(Path.Combine(fixture,"golden.atbackup"),password); Check(plan.Devices==1 && plan.Apps==1);
            Check(store.Merge(plan.Data)>0); Check(store.Merge(plan.Data)==0);
            var report=Statistics.Read(store,plan.Origin); Check(report.Device.Platform=="android" && report.Total==60000);
            Check(store.LocalDeviceId==local && store.Devices().Count==2);
        });
        Test("conflicting revisions roll back all rows",()=>
        {
            var data=store.Snapshot(); var row=data["sessions"][0]; row["endMs"]=N(row,"endMs")+1000; row["durationMs"]=N(row,"durationMs")+1000;
            long before=Statistics.Read(store,local).Total; bool failed=false;
            try { store.Merge(data); } catch (InvalidDataException) { failed=true; }
            Check(failed && Statistics.Read(store,local).Total==before);
        });
        Test("higher revision and tombstone replace previous session",()=>
        {
            var data=store.Snapshot(); var row=data["sessions"][0]; row["revision"]=N(row,"revision")+1; row["deleted"]=1L;
            long before=Statistics.Read(store,local).Total,duration=N(row,"durationMs"); store.Merge(data); Check(Statistics.Read(store,local).Total==before-duration); Check(store.Merge(data)==0);
        });
        Test("malformed archive rejects before target mutation",()=>
        {
            var data=store.Snapshot(); long before=Statistics.Read(store,local).Total;
            data["sessions"][0]["metric"]="android_foreground"; bool failed=false;
            try { Backup.ValidateZip(Backup.Zip(data,local)); } catch (InvalidDataException) { failed=true; } Check(failed && Statistics.Read(store,local).Total==before);
        });
        Test("misaligned Android daily bucket is not assigned to a date",()=>
        {
            var data=store.Snapshot(); var i=data["app_identities"].First(r=>S(r,"deviceId")!=local); var date=new DateOnly(2026,10,2);
            long midnight=TimeZones.Midnight(date,"Asia/Shanghai");
            data["system_daily_usage"].Add(new() { ["identityId"]=S(i,"identityId"),["reportDate"]=date.ToString("yyyy-MM-dd"),["timezone"]="Asia/Shanghai",["durationMs"]=4800000L,["bucketStartMs"]=midnight+17*3600000L,["bucketEndMs"]=midnight+41*3600000L,["observedAtMs"]=midnight+86400000L });
            store.Merge(data); Check(Statistics.Read(store,S(i,"deviceId")).Day(date)==0);
        });
        Test("duplicate primary key is rejected in staging",()=>
        {
            var data=store.Snapshot(); data["sessions"].Add(new(data["sessions"][0])); bool failed=false;
            try { Backup.ValidateZip(Backup.Zip(data,local)); } catch (InvalidDataException) { failed=true; } Check(failed);
        });
        Test("foreign keys and origin-device mismatch rejected",()=>
        {
            var data=store.Snapshot(); data["sessions"][0]["identityId"]=Guid.NewGuid().ToString(); bool failed=false;
            try { Backup.ValidateZip(Backup.Zip(data,local)); } catch (Microsoft.Data.Sqlite.SqliteException) { failed=true; } Check(failed);
            data=store.Snapshot(); data["sessions"][0]["originDeviceId"]=store.Devices().Single(d=>d.Id!=local).Id; failed=false;
            try { Backup.ValidateZip(Backup.Zip(data,local)); } catch (InvalidDataException) { failed=true; } Check(failed);
        });
        Test("ZIP path traversal and decompression limit rejected",()=>
        {
            byte[] original=Backup.Zip(store.Snapshot(),local);
            byte[] Rewrite(string? extra,bool bomb)
            {
                using var source=new ZipArchive(new MemoryStream(original)); using var output=new MemoryStream();
                using (var target=new ZipArchive(output,ZipArchiveMode.Create,true))
                {
                    foreach (var entry in source.Entries)
                    {
                        using var input=entry.Open(); using var dest=target.CreateEntry(entry.FullName).Open();
                        if (bomb && entry.FullName=="sessions.jsonl") { var block=new byte[1024*1024]; for (int i=0;i<65;i++) dest.Write(block); }
                        else input.CopyTo(dest);
                    }
                    if (extra!=null) target.CreateEntry(extra);
                }
                return output.ToArray();
            }
            bool failed=false; try { Backup.ValidateZip(Rewrite("../escape.jsonl",false)); } catch (InvalidDataException) { failed=true; } Check(failed);
            failed=false; try { Backup.ValidateZip(Rewrite(null,true)); } catch (InvalidDataException) { failed=true; } Check(failed);
        });
        Test("future envelope and truncated file rejected",()=>
        {
            var bytes=File.ReadAllBytes(Path.Combine(root,"roundtrip.atbackup")); bytes[9]=2;
            bool failed=false; try { Backup.Decrypt(bytes,password); } catch (InvalidDataException) { failed=true; } Check(failed);
            failed=false; try { Backup.Decrypt(bytes[..30],password); } catch (InvalidDataException) { failed=true; } Check(failed);
        });
    }
    Test("reopening keeps device ID and confirmed records",()=> { using var store=new Store(path); Check(store.LocalDeviceId==local && store.Snapshot()["sessions"].Count==5); });
    Test("restart never extends a previously confirmed tail",()=>
    { using var store=new Store(path); long before=Statistics.Read(store,local).Total; var fresh=Engine(); fresh.Poll(new(epoch+86400000,0,0,a)); store.Save(fresh.Drain()); Check(Statistics.Read(store,local).Total==before); });
    Console.WriteLine($"{passed} tests passed");
}
finally
{
    string resolved=Path.GetFullPath(root),temp=Path.GetFullPath(Path.GetTempPath());
    if (!resolved.StartsWith(temp,StringComparison.OrdinalIgnoreCase) || !Path.GetFileName(resolved).StartsWith("AppTime-tests-")) throw new Exception("Unsafe test cleanup target");
    Directory.Delete(resolved,true);
}
