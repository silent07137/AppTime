// SPDX-License-Identifier: GPL-2.0-only
using System.Buffers.Binary;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using static AppTime.Core.Store;
namespace AppTime.Core;

public record RestorePlan(string Origin, Dictionary<string,List<Dictionary<string,object?>>> Data)
{
    public int Devices => Data["devices"].Count;
    public int Apps => Data["app_identities"].Count;
    public int Sessions => Data["sessions"].Count;
}
public static class Backup
{
    private static readonly byte[] Magic = [65,84,66,75,13,10,26,10];
    public const int MaxCipher=16*1024*1024, MaxPlain=64*1024*1024;
    private const long MaxTime=253402300799999;
    public static byte[] Encrypt(byte[] zip, string password)
    {
        Password(password);
        if (zip.Length+16>MaxCipher) throw new InvalidDataException("档案超过备份限制");
        var header=new byte[54]; Magic.CopyTo(header,0); BinaryPrimitives.WriteInt16BigEndian(header.AsSpan(8),1);
        header[10]=header[11]=header[12]=1; BinaryPrimitives.WriteInt32BigEndian(header.AsSpan(14),600000);
        RandomNumberGenerator.Fill(header.AsSpan(18,16)); RandomNumberGenerator.Fill(header.AsSpan(34,12));
        BinaryPrimitives.WriteInt64BigEndian(header.AsSpan(46),zip.Length+16);
        var key=Rfc2898DeriveBytes.Pbkdf2(password,header.AsSpan(18,16),600000,HashAlgorithmName.SHA256,32);
        try
        {
            var result=new byte[54+zip.Length+16]; header.CopyTo(result,0);
            using var aes=new AesGcm(key,16); aes.Encrypt(header.AsSpan(34,12),zip,result.AsSpan(54,zip.Length),result.AsSpan(54+zip.Length,16),header); return result;
        }
        finally { CryptographicOperations.ZeroMemory(key); }
    }
    public static byte[] Decrypt(byte[] file, string password)
    {
        Password(password);
        if (file.Length<70 || file.Length>MaxCipher+54 || !file.AsSpan(0,8).SequenceEqual(Magic) || BinaryPrimitives.ReadInt16BigEndian(file.AsSpan(8))!=1 || file[10]!=1 || file[11]!=1 || file[12]!=1 || file[13]!=0 || BinaryPrimitives.ReadInt32BigEndian(file.AsSpan(14))!=600000) throw new InvalidDataException("不支持的备份格式或参数");
        long length=BinaryPrimitives.ReadInt64BigEndian(file.AsSpan(46));
        if (length<16 || length>MaxCipher || file.Length!=length+54) throw new InvalidDataException("备份长度无效");
        var key=Rfc2898DeriveBytes.Pbkdf2(password,file.AsSpan(18,16),600000,HashAlgorithmName.SHA256,32);
        try
        {
            var zip=new byte[(int)length-16]; using var aes=new AesGcm(key,16);
            aes.Decrypt(file.AsSpan(34,12),file.AsSpan(54,zip.Length),file.AsSpan(54+zip.Length,16),zip,file.AsSpan(0,54)); return zip;
        }
        finally { CryptographicOperations.ZeroMemory(key); }
    }
    private static void Password(string password) { if (password.Length is <8 or >256) throw new InvalidDataException("口令需为 8–256 个字符"); }
    public static RestorePlan Read(string path, string password)
    {
        if (new FileInfo(path).Length>MaxCipher+54) throw new InvalidDataException("备份文件过大");
        var zip=Decrypt(File.ReadAllBytes(path),password);
        try { return ValidateZip(zip); } finally { CryptographicOperations.ZeroMemory(zip); }
    }
    public static RestorePlan ValidateZip(byte[] zip)
    {
        using var archive=new ZipArchive(new MemoryStream(zip),ZipArchiveMode.Read);
        var expected=Tables.Select(t=>t.Name+".jsonl").Append("manifest.json").ToHashSet(StringComparer.Ordinal);
        if (archive.Entries.Count!=13 || !archive.Entries.Select(e=>e.FullName).ToHashSet().SetEquals(expected)) throw new InvalidDataException("备份文件清单无效");
        var files=new Dictionary<string,byte[]>(); long total=0;
        foreach (var entry in archive.Entries)
        {
            int bound=entry.FullName=="manifest.json" ? 128*1024 : MaxPlain;
            using var input=entry.Open(); using var output=new MemoryStream(); var buffer=new byte[32768]; int read;
            while ((read=input.Read(buffer))>0)
            {
                total+=read; if (output.Length+read>bound || total>MaxPlain) throw new InvalidDataException("备份解压大小超限"); output.Write(buffer,0,read);
            }
            files.Add(entry.FullName,output.ToArray());
        }
        using var doc=JsonDocument.Parse(files["manifest.json"]); var manifest=doc.RootElement;
        string[] manifestKeys=["format_version","schema_version","snapshot_id","created_at","exporting_device_id","device_coverage","files"];
        if (manifest.EnumerateObject().Count()!=manifestKeys.Length || !manifest.EnumerateObject().Select(p=>p.Name).ToHashSet().SetEquals(manifestKeys)) throw new InvalidDataException("备份清单字段无效");
        if (manifest.GetProperty("format_version").GetInt64()!=1 || manifest.GetProperty("schema_version").GetInt64()!=1) throw new InvalidDataException("不支持的数据版本");
        Guid.Parse(manifest.GetProperty("snapshot_id").GetString()!);
        string origin=manifest.GetProperty("exporting_device_id").GetString()!; Guid.Parse(origin);
        if (manifest.GetProperty("created_at").GetInt64() is <0 or >MaxTime) throw new InvalidDataException("备份时间无效");
        var info=manifest.GetProperty("files");
        if (info.EnumerateObject().Count()!=12 || !info.EnumerateObject().Select(p=>p.Name).ToHashSet().SetEquals(expected.Where(n=>n!="manifest.json"))) throw new InvalidDataException("内容清单无效");
        var data=new Dictionary<string,List<Dictionary<string,object?>>>(); int count=0;
        foreach (var table in Tables)
        {
            var bytes=files[table.Name+".jsonl"]; var meta=info.GetProperty(table.Name+".jsonl");
            if (meta.GetProperty("bytes").GetInt64()!=bytes.Length || meta.GetProperty("sha256").GetString()!=Convert.ToHexStringLower(SHA256.HashData(bytes))) throw new InvalidDataException("备份内容校验失败");
            var rows=new List<Dictionary<string,object?>>();
            using var reader=new StringReader(new UTF8Encoding(false,true).GetString(bytes)); string? line;
            while ((line=reader.ReadLine())!=null)
            {
                if (line.Length==0 || Encoding.UTF8.GetByteCount(line)>16384 || ++count>200000) throw new InvalidDataException("备份行数或记录长度超限");
                using var rowDoc=JsonDocument.Parse(line); var entry=rowDoc.RootElement;
                if (entry.ValueKind!=JsonValueKind.Object || entry.EnumerateObject().Count()!=table.Columns.Length || !entry.EnumerateObject().Select(p=>p.Name).ToHashSet().SetEquals(table.Columns.Select(c=>c.Name))) throw new InvalidDataException("备份字段不兼容");
                var row=new Dictionary<string,object?>();
                foreach (var col in table.Columns)
                {
                    var value=entry.GetProperty(col.Name);
                    if (value.ValueKind==JsonValueKind.Null)
                    { if (col.Required || table.Keys.Contains(col.Name)) throw new InvalidDataException("备份缺少必填字段"); row.Add(col.Name,null); }
                    else if (col.Type=="INTEGER")
                    {
                        long number=value.GetInt64(); row.Add(col.Name,number);
                        if (new[]{"deleted","provisional","transitionEstimated","hidden","ignored","enabled","signingChanged"}.Contains(col.Name) && (number is <0 or >1)) throw new InvalidDataException("备份布尔值无效");
                        if (col.Name=="revision" && (number is <1 or >1000000000)) throw new InvalidDataException("备份修订号无效");
                        if ((col.Name.EndsWith("Ms") && col.Name!="deltaMs" || col.Name is "createdAt" or "usageMs") && (number is <0 or >MaxTime)) throw new InvalidDataException("备份时间超限");
                    }
                    else
                    {
                        string str=value.GetString()!; if (str.Length>(col.Name is "note" or "detail" or "reason" ? 2048 : 512)) throw new InvalidDataException("备份文本超限"); row.Add(col.Name,str);
                    }
                }
                ValidateRow(table.Name,row); rows.Add(row);
            }
            if (rows.Count!=meta.GetProperty("rows").GetInt64() || (table.Name is "collection_state" or "history_import_state" && rows.Count>1)) throw new InvalidDataException("备份记录数无效");
            data.Add(table.Name,rows);
        }
        if (data["devices"].Count is <1 or >32 || !data["devices"].Any(r=>S(r,"deviceId")==origin)) throw new InvalidDataException("来源设备缺失");
        using var stage=new Store(":memory:",true); stage.Merge(data,true); stage.ValidateReferences();
        // Exercise derived calculations before touching the destination (overflow, calendar bounds).
        foreach (var device in stage.Devices()) Statistics.Read(stage,device.Id);
        var coverage=manifest.GetProperty("device_coverage");
        if (coverage.ValueKind!=JsonValueKind.Array || coverage.GetArrayLength()!=data["devices"].Count) throw new InvalidDataException("来源覆盖清单无效");
        var ids=new HashSet<string>();
        foreach (var item in coverage.EnumerateArray())
        {
            string id=item.GetProperty("device_id").GetString()!;
            if (!ids.Add(id) || !data["devices"].Any(d=>S(d,"deviceId")==id) || item.GetProperty("quality").GetString()!="partial") throw new InvalidDataException("来源覆盖清单无效");
            var sessions=data["sessions"].Where(r=>S(r,"originDeviceId")==id && N(r,"deleted")==0).ToArray();
            if (sessions.Length==0)
            { if (item.GetProperty("start_utc_ms").ValueKind!=JsonValueKind.Null || item.GetProperty("end_utc_ms").ValueKind!=JsonValueKind.Null) throw new InvalidDataException("来源覆盖边界无效"); }
            else if (item.GetProperty("start_utc_ms").GetInt64()!=sessions.Min(r=>N(r,"startMs")) || item.GetProperty("end_utc_ms").GetInt64()!=sessions.Max(r=>N(r,"endMs"))) throw new InvalidDataException("来源覆盖边界无效");
        }
        return new(origin,data);
    }
    private static void ValidateRow(string table, Dictionary<string,object?> r)
    {
        foreach (var key in new[]{"deviceId","originDeviceId","identityId","sessionId","bucketId","periodId","adjustmentId"}) if (r.ContainsKey(key)) Guid.Parse(S(r,key));
        if (r.ContainsKey("reportDate")) DateOnly.ParseExact(S(r,"reportDate"),"yyyy-MM-dd");
        if (r.ContainsKey("timezone") && S(r,"timezone")!="unknown") TimeZones.Find(S(r,"timezone"));
        if (r.ContainsKey("reportTimezone")) TimeZones.Find(S(r,"reportTimezone"));
        if (r.ContainsKey("utcOffsetSeconds") && (N(r,"utcOffsetSeconds") is <-64800 or >64800)) throw new InvalidDataException("时区偏移无效");
        if (table=="devices" && S(r,"platform") is not ("android" or "windows")) throw new InvalidDataException("不支持的设备平台");
        if (table=="collection_state" && S(r,"source")!="android_usage_events") throw new InvalidDataException("采集状态来源无效");
        if (table is "history_import_state" or "historical_buckets" && S(r,"source") is not ("android_usage_stats_daily" or "android_usage_stats_best")) throw new InvalidDataException("历史来源无效");
        if (table=="sessions")
        {
            bool android=S(r,"metric")=="android_foreground" && S(r,"source")=="android_usage_events";
            bool windows=S(r,"metric")=="windows_active_foreground" && S(r,"source")=="windows_foreground_poll";
            if (!android && !windows || N(r,"endMs")<=N(r,"startMs") || N(r,"startMs")<N(r,"anchorMs") || N(r,"durationMs")!=N(r,"endMs")-N(r,"startMs") || N(r,"durationMs")>7*86400000L) throw new InvalidDataException("会话数据无效");
        }
        if (table is "historical_buckets" or "coverage" && N(r,"endMs")<=N(r,"startMs")) throw new InvalidDataException("时间范围无效");
        if (table=="system_daily_usage" && N(r,"bucketEndMs")<=N(r,"bucketStartMs")) throw new InvalidDataException("系统汇总范围无效");
        if (table=="ignore_periods" && r["endMs"]!=null && N(r,"endMs")<N(r,"startMs")) throw new InvalidDataException("忽略范围无效");
        if (table=="manual_adjustments" && (N(r,"deltaMs")==0 || N(r,"deltaMs") is <-86400000 or >86400000)) throw new InvalidDataException("调整量无效");
        foreach (var key in new[]{"acceptedBuckets","skippedBuckets"}) if (r.ContainsKey(key) && N(r,key) is <0 or >int.MaxValue) throw new InvalidDataException("历史行数无效");
    }
    public static byte[] Zip(Dictionary<string,List<Dictionary<string,object?>>> data, string origin)
    {
        var files=new Dictionary<string,byte[]>(); var info=new Dictionary<string,object>();
        foreach (var table in Tables)
        {
            byte[] bytes=Encoding.UTF8.GetBytes(string.Concat(data[table.Name].Select(r=>JsonSerializer.Serialize(r)+"\n")));
            files.Add(table.Name+".jsonl",bytes); info.Add(table.Name+".jsonl",new { rows=data[table.Name].Count,bytes=bytes.Length,sha256=Convert.ToHexStringLower(SHA256.HashData(bytes)) });
        }
        var coverage=data["devices"].Select(d=>
        {
            var rows=data["sessions"].Where(r=>S(r,"originDeviceId")==S(d,"deviceId") && N(r,"deleted")==0).ToArray();
            return new { device_id=S(d,"deviceId"),start_utc_ms=rows.Length==0 ? (long?)null : rows.Min(r=>N(r,"startMs")),end_utc_ms=rows.Length==0 ? (long?)null : rows.Max(r=>N(r,"endMs")),quality="partial" };
        });
        files.Add("manifest.json",JsonSerializer.SerializeToUtf8Bytes(new { format_version=1,schema_version=1,snapshot_id=Guid.NewGuid().ToString(),created_at=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),exporting_device_id=origin,device_coverage=coverage,files=info }));
        using var output=new MemoryStream(); using (var archive=new ZipArchive(output,ZipArchiveMode.Create,true)) foreach (var file in files) { using var stream=archive.CreateEntry(file.Key,CompressionLevel.Optimal).Open(); stream.Write(file.Value); }
        return output.ToArray();
    }
    public static void Export(Store store, string path, string password)
    {
        var zip=Zip(store.Snapshot(),store.LocalDeviceId); byte[] encoded;
        try { ValidateZip(zip); encoded=Encrypt(zip,password); } finally { CryptographicOperations.ZeroMemory(zip); }
        string temp=path+"."+Guid.NewGuid().ToString("N")+".tmp";
        try
        {
            using (var file=new FileStream(temp,FileMode.CreateNew,FileAccess.Write,FileShare.None)) { file.Write(encoded); file.Flush(true); }
            if (!SHA256.HashData(File.ReadAllBytes(temp)).SequenceEqual(SHA256.HashData(encoded))) throw new IOException("备份写入校验失败");
            Read(temp,password); File.Move(temp,path,true);
        }
        finally { if (File.Exists(temp)) File.Delete(temp); }
    }
}
