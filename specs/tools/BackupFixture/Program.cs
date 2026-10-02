// SPDX-License-Identifier: GPL-2.0-only
using System.Buffers.Binary;
using System.Security.Cryptography;
using System.IO.Compression;
using System.Text.Json;

var directory = Path.GetFullPath(args[0]);
const string password = "AppTime-备份-golden-123";
var zipped = File.ReadAllBytes(Path.Combine(directory, "golden.zip"));
if (args.Length > 1 && args[1] == "generate")
    File.WriteAllBytes(Path.Combine(directory, "golden.atbackup"), Encode(zipped, password, Enumerable.Range(0, 16).Select(i => (byte)i).ToArray(), Enumerable.Range(16, 12).Select(i => (byte)i).ToArray()));
var encrypted = File.ReadAllBytes(Path.Combine(directory, "golden.atbackup"));
var decoded = Decode(encrypted, password);
if (!decoded.SequenceEqual(zipped)) throw new Exception("Golden plaintext mismatch");
var roundTrip = Encode(decoded, password, RandomNumberGenerator.GetBytes(16), RandomNumberGenerator.GetBytes(12));
if (!Decode(roundTrip, password).SequenceEqual(decoded) || roundTrip.SequenceEqual(encrypted)) throw new Exception("Round trip/randomness failure");
try { Decode(encrypted, "wrong-password"); throw new Exception("Wrong password accepted"); } catch (CryptographicException) { }
using var archive = new ZipArchive(new MemoryStream(decoded));
using var manifest = JsonDocument.Parse(archive.GetEntry("manifest.json")!.Open());
foreach (var file in manifest.RootElement.GetProperty("files").EnumerateObject()) {
    using var content = archive.GetEntry(file.Name)!.Open(); using var bytes = new MemoryStream(); content.CopyTo(bytes);
    var value = bytes.ToArray();
    if (value.Length != file.Value.GetProperty("bytes").GetInt64() || Convert.ToHexStringLower(SHA256.HashData(value)) != file.Value.GetProperty("sha256").GetString()) throw new Exception("Manifest checksum mismatch");
}
Console.WriteLine(".NET portable backup fixture: authentication, UTF-8 password, hashes and round trip passed");

static byte[] Encode(byte[] zip, string password, byte[] salt, byte[] nonce) {
    var header = new byte[54]; new byte[] {65,84,66,75,13,10,26,10}.CopyTo(header,0);
    BinaryPrimitives.WriteInt16BigEndian(header.AsSpan(8),1); header[10]=header[11]=header[12]=1;
    BinaryPrimitives.WriteInt32BigEndian(header.AsSpan(14),600000); salt.CopyTo(header,18); nonce.CopyTo(header,34);
    BinaryPrimitives.WriteInt64BigEndian(header.AsSpan(46),zip.Length+16);
    var key = Rfc2898DeriveBytes.Pbkdf2(password, salt,600000,HashAlgorithmName.SHA256,32);
    try {
        var result = new byte[54+zip.Length+16]; header.CopyTo(result,0);
        using var aes = new AesGcm(key,16); aes.Encrypt(nonce,zip,result.AsSpan(54,zip.Length),result.AsSpan(54+zip.Length,16),header);
        return result;
    } finally { CryptographicOperations.ZeroMemory(key); }
}
static byte[] Decode(byte[] file, string password) {
    if (file.Length<70 || !file.AsSpan(0,8).SequenceEqual(new byte[] {65,84,66,75,13,10,26,10}) || BinaryPrimitives.ReadInt16BigEndian(file.AsSpan(8))!=1 || file[10]!=1 || file[11]!=1 || file[12]!=1 || file[13]!=0 || BinaryPrimitives.ReadInt32BigEndian(file.AsSpan(14))!=600000) throw new Exception("Unsupported header");
    var length=BinaryPrimitives.ReadInt64BigEndian(file.AsSpan(46));
    if (length<16 || length>16*1024*1024 || file.Length!=54+length) throw new Exception("Invalid size");
    var key=Rfc2898DeriveBytes.Pbkdf2(password,file.AsSpan(18,16),600000,HashAlgorithmName.SHA256,32);
    try {
        var zip=new byte[(int)length-16]; using var aes=new AesGcm(key,16);
        aes.Decrypt(file.AsSpan(34,12),file.AsSpan(54,zip.Length),file.AsSpan(54+zip.Length,16),zip,file.AsSpan(0,54));
        return zip;
    } finally { CryptographicOperations.ZeroMemory(key); }
}
