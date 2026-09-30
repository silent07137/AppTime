# Android 签名与发布

Release 构建启用 R8/资源压缩，关闭 debuggable，包名 `io.github.silent07137.apptime`。v1.0.0 versionCode 为 10000，后续必须递增。

签名配置由 `APPTIME_SIGNING_PROPERTIES` 指向仓库外文件，不能提交密钥、口令或本机路径：

```properties
storeFile=/private/signing/apptime-release.p12
storePassword=your-private-password
keyAlias=apptime
keyPassword=your-private-password
```

本次沿用测试机首次安装时的签名证书，导入独立 PKCS12 密钥库，以随机强口令和仅当前用户可读目录保存，支持已有测试档案直接升级。发布 APK 为非调试 Release 构建。维护者应独立备份密钥库与配置；源码不能恢复私钥，替换证书会影响升级。

```powershell
$env:APPTIME_SIGNING_PROPERTIES='C:\private\AppTime\signing.properties'
cd android
.\gradlew.bat :core:test :app:lintRelease :app:assembleRelease
```

输出 `app/build/outputs/apk/release/app-release.apk`。无签名配置时得到未签名包，不能安装。Debug/Preview 使用本机调试证书，不保证与发布版本一致。

发布前验证核心/设备测试、lint、签名和版本、覆盖升级数据保留、Release 启动/UI。复制为 `AppTime-v1.0.0.apk` 并计算 SHA256，与 `SHA256SUMS.txt` 一起上传 GitHub Release；tag 指向对应源码，说明注明未实现范围。

Actions 验证工具链、核心测试、lint 和 Debug/Preview，不持有签名私钥；正式 APK 本地签名发布。
