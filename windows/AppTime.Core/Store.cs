// SPDX-License-Identifier: GPL-2.0-only
using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace AppTime.Core;

public record Column(string Name, string Type, bool Required);
public record Table(string Name, string Sql, Column[] Columns, string[] Keys);
public record Device(string Id, string Platform, string Timezone, long CreatedAt);
public sealed class Store : IDisposable
{
    public static readonly Table[] Tables = JsonSerializer.Deserialize<Table[]>(
        typeof(Store).Assembly.GetManifestResourceStream("AppTime.Core.ArchiveSchema.json")!,
        new JsonSerializerOptions { PropertyNameCaseInsensitive = true })!;
    public readonly object Gate = new();
    private readonly SqliteConnection connection;
    public string LocalDeviceId { get; private set; } = "";
    public long Version { get; private set; }
    public string DirectoryPath { get; }

    public Store(string path, bool staging = false)
    {
        DirectoryPath = path == ":memory:" ? "" : Path.GetDirectoryName(Path.GetFullPath(path))!;
        if (DirectoryPath.Length > 0) Directory.CreateDirectory(DirectoryPath);
        connection = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = path, Pooling = false }.ToString());
        connection.Open();
        Run("PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;");
        long version = Convert.ToInt64(Scalar("PRAGMA user_version"));
        if (version > 1) throw new InvalidDataException("此档案由更高版本创建，请升级 AppTime");
        if (!staging) Run("PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;");
        InTransaction(() =>
        {
            foreach (var table in Tables) Run(table.Sql);
            Run("CREATE UNIQUE INDEX IF NOT EXISTS identity_platform_key ON app_identities(deviceId,profileScope,packageName);" +
                "CREATE INDEX IF NOT EXISTS session_day ON sessions(identityId,startMs,endMs);" +
                "CREATE INDEX IF NOT EXISTS session_device ON sessions(originDeviceId,startMs);" +
                "CREATE INDEX IF NOT EXISTS history_app ON historical_buckets(identityId,startMs,endMs);" +
                "CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY NOT NULL,value TEXT NOT NULL); PRAGMA user_version=1;");
            if (!staging)
            {
                LocalDeviceId = Setting("local_device") ?? Guid.NewGuid().ToString();
                SetSetting("local_device", LocalDeviceId);
                Run("INSERT OR IGNORE INTO devices VALUES($p0,'windows',$p1,$p2)", LocalDeviceId, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), TimeZones.LocalIana);
            }
        });
    }

    public string? Setting(string key) { lock (Gate) return Scalar("SELECT value FROM settings WHERE key=$p0", key) as string; }
    public void SetSetting(string key, string value) { lock (Gate) Run("INSERT INTO settings VALUES($p0,$p1) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, value); }
    public List<Device> Devices() { lock (Gate) return Rows("SELECT * FROM devices ORDER BY createdAt,deviceId").Select(r => new Device(S(r,"deviceId"), S(r,"platform"), S(r,"reportTimezone"), N(r,"createdAt"))).ToList(); }
    public string LocalTimezone => Devices().Single(d => d.Id == LocalDeviceId).Timezone;
    public void ChangeTimezone(string zone)
    {
        TimeZones.Find(zone);
        lock (Gate) InTransaction(() => { Run("UPDATE devices SET reportTimezone=$p0 WHERE deviceId=$p1", zone, LocalDeviceId); Version++; });
    }

    public void Save((List<Session> Sessions, List<Gap> Gaps) batch)
    {
        if (batch.Sessions.Count == 0 && batch.Gaps.Count == 0) return;
        lock (Gate) InTransaction(() =>
        {
            foreach (var session in batch.Sessions)
            {
                string? id = Scalar("SELECT identityId FROM app_identities WHERE deviceId=$p0 AND profileScope='current_user' AND packageName=$p1", LocalDeviceId, session.App.Key) as string;
                if (id == null)
                {
                    id = Guid.NewGuid().ToString();
                    Run("INSERT INTO app_identities VALUES($p0,$p1,'current_user',$p2,$p3)", id, LocalDeviceId, session.App.Key, session.App.Name);
                }
                Run("INSERT INTO sessions VALUES($p0,$p1,$p2,$p3,$p3,$p4,$p5,$p6,$p7,'windows_active_foreground','windows_foreground_poll',0,1,'partial',$p8,0) " +
                    "ON CONFLICT(sessionId) DO UPDATE SET endMs=excluded.endMs,durationMs=excluded.durationMs,revision=excluded.revision WHERE excluded.revision > sessions.revision",
                    session.Id, LocalDeviceId, id, session.StartMs, session.EndMs, session.EndMs - session.StartMs, session.Timezone, session.OffsetSeconds, session.Revision);
            }
            foreach (var group in batch.Gaps.GroupBy(g => g.Reason))
                foreach (var span in TimeZones.Union(group.Select(g => (g.StartMs, g.EndMs))))
                {
                    var last = Rows("SELECT * FROM coverage WHERE deviceId=$p0 AND reason=$p1 ORDER BY endMs DESC LIMIT 1", LocalDeviceId, group.Key).SingleOrDefault();
                    if (last != null && N(last,"endMs") == span.Start) Run("UPDATE coverage SET endMs=$p0 WHERE coverageId=$p1", span.End, S(last,"coverageId"));
                    else Run("INSERT INTO coverage VALUES($p0,$p1,$p2,$p3,'unavailable',$p4)", Guid.NewGuid().ToString(), LocalDeviceId, span.Start, span.End, group.Key);
                }
            Version++;
        });
    }

    // All rows are read within one transaction; the raw archive never depends on derived UI caches.
    public Dictionary<string, List<Dictionary<string, object?>>> Snapshot()
    {
        lock (Gate)
        {
            Dictionary<string, List<Dictionary<string, object?>>>? result = null;
            InTransaction(() => result = Tables.ToDictionary(t => t.Name, t => Rows($"SELECT {string.Join(',', t.Columns.Select(c => Q(c.Name)))} FROM {Q(t.Name)} ORDER BY rowid")));
            return result!;
        }
    }

    public int Merge(Dictionary<string, List<Dictionary<string, object?>>> data, bool staging = false)
    {
        lock (Gate)
        {
            int changed = 0;
            InTransaction(() =>
            {
                foreach (var table in Tables)
                {
                    // These rows describe the source collector, not this computer's live state.
                    if (!staging && table.Name is "collection_state" or "history_import_state") continue;
                    foreach (var row in data[table.Name])
                    {
                        string predicate = string.Join(" AND ", table.Keys.Select((k,i) => $"{Q(k)}=$p{i}"));
                        var keys = table.Keys.Select(k => row[k]).ToArray();
                        var existing = Rows($"SELECT * FROM {Q(table.Name)} WHERE {predicate}", keys).SingleOrDefault();
                        if (staging && existing != null) throw new InvalidDataException("备份存在重复记录");
                        if (existing != null)
                        {
                            string[] immutable = table.Name switch
                            {
                                "devices" => ["platform","createdAt"],
                                "app_identities" => ["deviceId","profileScope","packageName"],
                                "sessions" => ["originDeviceId","identityId","anchorMs","metric","source"],
                                "historical_buckets" => ["originDeviceId","identityId","source","startMs"],
                                "ignore_periods" => ["identityId","startMs"],
                                "manual_adjustments" => ["identityId","reportDate","timezone","deltaMs","note","createdAtMs"],
                                _ => []
                            };
                            if (immutable.Any(k => !Equals(existing[k], row[k]))) throw new InvalidDataException("档案身份冲突，现有数据未修改");
                            if (table.Columns.All(c => Equals(existing[c.Name], row[c.Name]))) continue;
                            if (table.Name == "app_identities") continue;
                            var revision = table.Name switch
                            {
                                "sessions" or "historical_buckets" or "app_preferences" or "ignore_periods" or "manual_adjustments" => "revision",
                                "system_daily_usage" or "app_observations" => "observedAtMs",
                                "coverage" => "endMs",
                                _ => null
                            };
                            if (revision == null || N(existing,revision) == N(row,revision)) throw new InvalidDataException("档案存在同版本冲突，现有数据未修改");
                            if (N(row,revision) < N(existing,revision)) continue;
                        }
                        string cols = string.Join(',',table.Columns.Select(c => Q(c.Name)));
                        string parameters = string.Join(',',table.Columns.Select((_,i) => $"$p{i}"));
                        string update = string.Join(',',table.Columns.Select(c => $"{Q(c.Name)}=excluded.{Q(c.Name)}"));
                        Run($"INSERT INTO {Q(table.Name)} ({cols}) VALUES ({parameters}) ON CONFLICT ({string.Join(',', table.Keys.Select(Q))}) DO UPDATE SET {update}", table.Columns.Select(c => row[c.Name]).ToArray());
                        changed++;
                    }
                }
                ValidateReferences();
                if (Convert.ToInt64(Scalar("SELECT COUNT(*) FROM devices")) > 32 || Tables.Sum(t => Convert.ToInt64(Scalar($"SELECT COUNT(*) FROM {Q(t.Name)}"))) > 200000)
                    throw new InvalidDataException("合并后的档案超过限制");
            });
            if (changed > 0) Version++;
            return changed;
        }
    }

    public void ValidateReferences()
    {
        if (Rows("SELECT 1 FROM system_daily_usage s JOIN app_identities i ON i.identityId=s.identityId JOIN devices d ON d.deviceId=i.deviceId WHERE d.platform!='android' LIMIT 1").Count>0)
            throw new InvalidDataException("系统日报与设备平台不匹配");
        if (Rows("PRAGMA foreign_key_check").Count > 0 || Rows("SELECT 1 FROM sessions s JOIN app_identities i ON s.identityId=i.identityId JOIN devices d ON d.deviceId=i.deviceId WHERE s.originDeviceId!=i.deviceId OR (d.platform='windows' AND s.metric!='windows_active_foreground') OR (d.platform='android' AND s.metric!='android_foreground') UNION ALL SELECT 1 FROM historical_buckets h JOIN app_identities i ON h.identityId=i.identityId JOIN devices d ON d.deviceId=i.deviceId WHERE h.originDeviceId!=i.deviceId OR d.platform!='android' LIMIT 1").Count > 0)
            throw new InvalidDataException("应用与来源设备不匹配");
    }
    internal List<Dictionary<string, object?>> Rows(string sql, params object?[] parameters)
    {
        using var command = Command(sql, parameters); using var reader = command.ExecuteReader(); var rows = new List<Dictionary<string, object?>>();
        while (reader.Read())
        {
            var row = new Dictionary<string, object?>();
            for (int i=0;i<reader.FieldCount;i++) row.Add(reader.GetName(i),reader.IsDBNull(i) ? null : reader.GetValue(i));
            rows.Add(row);
        }
        return rows;
    }
    private SqliteCommand Command(string sql, object?[] parameters)
    {
        var cmd = connection.CreateCommand(); cmd.CommandText = sql;
        for (int i=0;i<parameters.Length;i++) cmd.Parameters.AddWithValue($"$p{i}",parameters[i] ?? DBNull.Value);
        return cmd;
    }
    private void Run(string sql, params object?[] parameters) { using var cmd = Command(sql,parameters); cmd.ExecuteNonQuery(); }
    private object? Scalar(string sql, params object?[] parameters) { using var cmd = Command(sql,parameters); return cmd.ExecuteScalar(); }
    private void InTransaction(Action action)
    {
        using var transaction = connection.BeginTransaction();
        try { action(); transaction.Commit(); } catch { transaction.Rollback(); throw; }
    }
    public static string Q(string name) => "\"" + name.Replace("\"","\"\"") + "\"";
    public static string S(Dictionary<string, object?> row, string key) => (string)row[key]!;
    public static long N(Dictionary<string, object?> row, string key) => Convert.ToInt64(row[key]);
    public void Dispose() { lock (Gate) connection.Dispose(); }
}
