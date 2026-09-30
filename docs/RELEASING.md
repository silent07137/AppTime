# Android 签名与发布

Release 构建启用 R8/资源压缩，关闭 debuggable，包名 `io.github.silent07137.apptime`。v1.0.0 versionCode 为 10000，后续必须递增。

签名配置由 `APPTIME_SIGNING_PROPERTIES` 指向仓库外文件，不能提交密钥、口令或本机路径：

```properties
storeFile=/private/signing/apptime-release.p12
storePassword=your-private-password
keyAlias=apptime
keyPassword=your-private-password
```

v1.0.0 使用维护者自行生成的 PKCS12 密钥库。完整密钥和口令只在仓库外保存；应将两者一起做私密备份。仓库中的 `android/signing-lineage.bin` 是公开的证书轮换证明，不含私钥。它连接早期测试机的旧证书和正式证书，使 Android 9+ 可以覆盖升级并保留原有数据。正式证书的 SHA256 指纹为 `f912203787aa25ca4957486002c0d75a9658f2128076c24ae5861b474cc42b77`。

```powershell
$env:APPTIME_SIGNING_PROPERTIES='C:\private\AppTime\signing.properties'
cd android
.\gradlew.bat :core:test :app:lintRelease :app:assembleRelease
.\sign-release.ps1 -PropertiesFile $env:APPTIME_SIGNING_PROPERTIES
```

Gradle 先输出 `app/build/outputs/apk/release/app-release.apk`。脚本再使用同一正式密钥嵌入轮换证明，输出最终的 `AppTime-v1.0.0.apk` 和 `SHA256SUMS.txt`，均在同一 release 目录。最终 APK 使用 v3 签名，最低系统版本为 Android 10（API 29）；脚本校验签名后才报告成功。无签名配置时 Gradle 得到未签名包，不能安装。Debug/Preview 使用本机调试证书，不保证与发布版本一致。

发布前验证核心/设备测试、lint、签名和版本、覆盖升级数据保留、Release 启动/UI。最终 APK 与 `SHA256SUMS.txt` 一起上传 GitHub Release；tag 指向对应源码，说明注明未实现范围。后续维护者若要支持早期测试机直接升级，应继续使用同一轮换证明和正式私钥；普通升级必须递增 versionCode。

Actions 验证工具链、核心测试、lint 和 Debug/Preview，不持有签名私钥；正式 APK 本地签名发布。
