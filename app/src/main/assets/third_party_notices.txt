# 第三方依赖

本项目未复制 BookHub 或起点代码与素材。APK 包含标准 Android 开源依赖，所有权与许可归各原作者：

| 项目 | 许可证 | 官方来源 |
| --- | --- | --- |
| AndroidX / Jetpack Compose / Material3 / Lifecycle / Activity / Core | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin 标准库与构建插件 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| kotlinx.coroutines | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| jsoup 1.18.3（仅解析已取得的 EPUB 字符串） | MIT | https://jsoup.org/ |
| OkHttp MockWebServer 4.12.0（仅本地测试，不进 APK） | Apache-2.0 | https://github.com/square/okhttp |
| Robolectric（仅测试，不进 APK） | MIT | https://github.com/robolectric/robolectric |
| JUnit 4（仅测试） | EPL-1.0 | https://junit.org/junit4/ |
| Gradle Wrapper（构建工具） | Apache-2.0 | https://github.com/gradle/gradle |

运行时字体由 Android 系统提供，不另打包字体。构建工具、SDK、JDK 与缓存不包含在源码 ZIP 中。

Apache-2.0 全文随本项目放在 `licenses/Apache-2.0.txt`，并在 APK assets 中提供。
jsoup 的原始 MIT 声明随 JAR 提取至 `licenses/Jsoup-MIT.txt`，同时在 APK assets 中提供。

本文件只描述第三方组件的许可，不为本项目代码添加许可证。服务接入与书籍内容使用需要使用者自己的授权；公开版本不提供默认服务器或任何预置账户。
