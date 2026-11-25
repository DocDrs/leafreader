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
jsoup 的原始 MIT 声明随 JAR 提取至 `licenses/Jsoup-MIT.txt`，同时在 APK assets 中提供。上游 BookHub 附件未提供项目级许可，未将其代码、原始附件、资源或凭据打包。0.4 仅依据用户授权将原客户端生产默认 HTTPS origin 作为独立配置预填，可由用户覆盖。

## OpenCC 离线繁转简字典

使用 BYVoid/OpenCC ver.1.1.9（commit `556ed22496d650bd0b13b6c163be9814637970ae`）的原始 `TSPhrases.txt`、`TSCharacters.txt`、`STPhrases.txt`、`STCharacters.txt` 与 t2s 配置，许可证 Apache-2.0；作者 BYVoid 与 OpenCC 贡献者（原 AUTHORS 同附）。原字典未修改；纯 Kotlin 转换及原始坐标映射由本客户端实现。官方来源 https://github.com/BYVoid/OpenCC ，许可证全文见 `licenses/OpenCC-Apache-2.0.txt`，APK assets 同附。转换不涉及网络，不作正文文件的永久改写。
