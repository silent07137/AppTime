# 第三方许可

AppTime 原创源码：GPL-2.0-only。Copyright (C) 2026 AppTime contributors.

构建与运行依赖保持各自许可：

| 组件 | 用途 | 许可与来源 |
|---|---|---|
| Kotlin | 编译器与标准库 | Apache-2.0 · https://github.com/JetBrains/kotlin |
| AndroidX Compose / Activity / Lifecycle / Room / WorkManager | Android 界面、持久化、任务 | Apache-2.0 · https://android.googlesource.com/platform/frameworks/support/ |
| kotlinx.coroutines | 协程与 Flow | Apache-2.0 · https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx.serialization | Room 的传递运行库及测试元数据；版本通过 BOM 对齐 | Apache-2.0 · https://github.com/Kotlin/kotlinx.serialization |
| Gradle Wrapper | 构建引导，非 AppTime 原创代码 | Apache-2.0 · https://github.com/gradle/gradle |
| Android Gradle Plugin / KSP | 构建与 Room 代码生成 | Apache-2.0 · https://android.googlesource.com/platform/tools/base/ · https://github.com/google/ksp |
| JUnit / Hamcrest | 测试，不进入发布 APK | EPL-1.0 / BSD-3-Clause · https://github.com/junit-team/junit4 · https://github.com/hamcrest/JavaHamcrest |
| AndroidX Test | 实机测试，不进入发布 APK | Apache-2.0 · https://android.googlesource.com/platform/frameworks/testing/ |

完整 Apache-2.0 文本随应用 assets 提供。发布时须随源码保留 LICENSE、本文件以及各依赖要求保留的版权/NOTICE；本表不是逐个传递依赖的完整发布审计。
