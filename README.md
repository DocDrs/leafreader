# 栖页 LeafReader

原生 Android 阅读器，使用 Kotlin、Jetpack Compose 和 Android 原生文字排版。支持兼容 BookHub 接口的书籍服务，不包含后端服务、公共书库、预置账号或书籍内容。

## 功能

- 书架、找书、设置三个页面；真封面缩图、最近阅读排序、下拉同步。
- 单击续读，长按查看下载状态与本机操作；支持多选下载及本机移出。移出只隐藏本机条目，保留阅读进度和离线副本。
- 中文点按/滑动分页，目录跳转，字号、行距、段距和独立边距；按字符位置保存进度。
- 五种阅读背景，包括原创牛皮纸纹理；底部显示实际章节页数、全书进度、系统时间及电量图标。
- 手动整本下载和按需资源缓存；新加入书籍可按设置排队 Wi-Fi 下载。队列状态与完整副本校验分别显示。
- HTTPS 精确 origin 登录，密码不持久化，会话由 Android Keystore 加密；服务与账号的数据相互隔离。

## 配置服务

公开源码**不内置默认服务器**。在“设置 → BookHub 账号与服务”填写你自己管理或获准使用的 HTTPS 地址，再填写该服务的账号与密码并确认读取授权。不要把服务地址、账号或会话写入代码或提交到仓库。

本客户端实现了部分 BookHub 风格的登录、书架、搜索、阅读清单/资源、封面和阅读包契约，代码位于 `data/BookHubClient.kt`、`data/BookHubSource.kt` 和 `offline/`。不同后端版本可能需要适配；没有服务授权时不会用演示书或其他来源替代数据。使用服务和书籍内容需要相应授权。

## 构建

需要 JDK 17、Android SDK Platform 35、Build Tools 35.0.0。Gradle Wrapper 固定为 8.9，AGP 8.7.3，Kotlin/Compose Compiler 2.0.21。首次构建需要访问 Google Maven 与 Maven Central。

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew testDebugUnitTest testReleaseUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

Windows 使用 `gradlew.bat`。debug APK 由本机构建环境签名；release 默认生成未签名 APK，需要使用自己的签名流程。仓库不包含签名材料、SDK、JDK、构建缓存或安装包。从源码本地构建通常使用构建者自己的签名，不能覆盖已安装的同包名 1.0.1 APK。已有用户不要为试源码而卸载现有应用，可使用独立模拟器或测试机。

Android 最低版本为 8.0/API 26，目标 API 35。测试使用 JUnit、Robolectric、Compose 测试与本地 MockWebServer；测试书名、正文、图片和身份均为合成 fixtures，`.invalid` 地址不是真实服务。测试不使用真实账号或实际书籍。

## 目录

| 路径（相对 `app/src/main/java/app/leafreader`） | 内容 |
| --- | --- |
| `MainActivity.kt`、`ReaderViewModel.kt` | 界面、导航和状态 |
| `data/` | 服务适配、会话、书架及进度 |
| `reader/` | 原生分页、图文呈现、阅读状态与纸纹 |
| `offline/` | 私有持久缓存、下载队列和完整性校验 |
| `covers/` | 内嵌封面识别及缩图缓存 |

第三方组件与许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 和 [licenses](licenses/)。本仓库暂未为项目代码添加开源许可证，第三方声明仅适用于各自组件。
