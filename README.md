# 栖页 LeafReader 1.1.0

原生 Android 阅读器，使用 Kotlin、Jetpack Compose 和 Android 原生文字排版。支持兼容 BookHub 契约的获授权书籍服务，不包含后端、预置账号、默认公共书库或书籍内容。

## 本版

- 悬浮底栏在 Android 13/API 33 及以上、硬件绘制可用时，尝试对**当前滚动内容**作局部坐标重采样：圆角边缘折射与轻微中心放大。背景由本应用内容的独立 GraphicsLayer 提供，底栏文字和图标另行绘制，不随背景变形。没有把渐变、背景模糊或动画纹理称为折射。
- 软件绘制、旧系统、低内存设备、省电、系统关闭动画、用户关闭“底栏折射”、着色器失败或裁切尺寸超限时，使用高不透明度的静态渐变表面。着色范围只在底栏小区域，不做整屏像素回读或持续截图，不影响阅读纸张。
- 长按面板只显示书名、简明缓存/任务状态和适用操作，不显示额外副标题或技术核验说明。完整缓存不默认重下；失败重试、取消、清除和移出沿用原有逻辑。
- 日常移出确认仅有“移出书架？”和“取消 / 移除”，不显示同步开关或说明正文。登录书架不一致时的方向选择与具体远端移出清单仍独立确认。

本轮只调整上述界面和底栏绘制。保留原有正文左右点击、中间菜单、内部左滑下一页/右滑上一页；物理两侧系统返回区域不消费触摸，不设置手势排除区。

## 书架、书源和离线

一个本机书架显示已保存的各来源书籍，登录失效、退出、换源或重启不替换整个列表。身份仍包含原服务、账号和稳定书籍 ID；同名、同 ID 不跨来源合并。保留最近阅读排序、真封面缩图、下拉刷新、找书过滤与原位置续读。

有效的同源同账号 BookHub 登录下，明确加入/移出书架会同步名单；临时冲突或网络失败保存新版单本待办，读取最新状态/修订后有界重试。日检与下次正常登录只处理这些待办，不巡查整座书架、不补发旧隐藏或未登录操作，也不代替用户执行登录差异的批量选择。真正失效的授权暂停，等待正常登录或既有官方会话刷新，不保存密码或后台反复登录。

**清除下载会清除该书缓存和阅读进度，保留书架；明确移出会同时清本机下载与进度。** 旧响应和下载写入通过代次/nonce 隔离。服务端拉到的名单移出只更新可见名单，不静默销毁本机副本和进度。未登录操作保持本机范围，重新登录不会静默批量上传旧删除。

完整缓存按所选内容版本核验，原账号有效的可恢复本地会话仍在且未收到明确读取拒绝时，可纯本地读取目录、正文和图片，不依赖服务在线。文件齐全不代表永久离线许可；退出、会话无法恢复或明确授权拒绝后需恢复原授权，副本不会因掉登录自动删除。不把旧书请求发给新服务。设置仅提供当前账号占用及已移出副本清理，日常操作放在单本更多。

中文原生分页支持字号、行距、段距、独立边距、纸张主题、图文页面、目录跳转和字符锚点续读。失败切章可返回刚才可读页，真实授权失效除外；失效历史提供目录和显式恢复。进度与下载保存在本机，不承诺云进度同步。

## 配置服务与升级

公开 APK 和源码默认**没有真实服务器值**。在“设置 → BookHub 账号与服务”填写自己管理或获准使用的 HTTPS 地址、对应账号密码并确认读取授权。密码仅发送到明确选定的精确 HTTPS origin，不跨域转发、不保存；会话由设备 Keystore 加密。不同后端版本可能需要适配。

应用 ID 为 `app.leafreader`，versionName 为 `1.1.0`，versionCode 为 `1100`。官方公开 APK 和个人交付 APK 使用原有同一签名，可覆盖兼容旧版；不要先卸载。已有服务、会话、书架、进度和下载仍优先，公开 APK 无默认值不会主动清掉设备已存配置。没有实际设备覆盖安装验证时，签名与合成升级回归不能替代真机实测。

## 从源码构建

需要 JDK 17、Android SDK Platform 35、Build Tools 35.0.0。Gradle Wrapper 8.9，AGP 8.7.3，Kotlin/Compose Compiler 2.0.21；首次构建需要官方依赖仓库。

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew testDebugUnitTest testReleaseUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

Windows 使用 `gradlew.bat`。源码 release 默认未签名，使用自己的签名流程；官方发布证书私钥不在仓库中。自行构建通常不能覆盖已安装的官方同包名 APK，版本号增加不能解决签名不同。请用独立模拟器或测试机，不要为试源码而卸载现有应用。

可选本机输入 `LEAFREADER_DEFAULT_BOOKHUB_ORIGIN` 仅在生成任务执行时读取，不包含任何真实值或示例真实地址。无输入仍为手填 HTTPS。仅接受无凭据、路径、query 或 fragment 的 HTTPS origin，错误不回显输入；设备已保存的地址和会话优先。带值构建须在无 Git 的隔离目录，使用独立临时 Gradle 用户目录，并加 `--no-configuration-cache --no-build-cache -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`；不用 debug 日志或 Build Scan，不运行会记录目标的 UI 测试，编译后移除临时生成区。带值 APK 的地址可提取，不能公开发布。公开 Release 不设置此输入。

## 验证范围

业务和 UI 回归使用自写书目、图片、协议夹具、`.invalid` 地址和本地 MockWebServer，不使用真实账号或用户图书。合成升级夹具和可复现 AES 测试键不用于真实设备 Keystore。正式 App 不内置样书或新增导入入口。

Robolectric API 34 检查静态回退、真实列表滚动到导航下方、最后一本可见、48px 合成底部安全区、浅深主题/大字号、操作、偏好持久化和原有阅读功能。API 35 原生 Skia 检查同一 AGSL 内核对实际 Compose 滚动素材的重采样以及效应对象缓存。主机像素检验为测试专用光栅 oracle，不等同于 Android 设备 GPU 链路：测试才放开软件 Canvas 的 RuntimeShader 限制，APK 严格要求硬件绘制并保留回退。

当前没有可用真机或模拟器，未验证设备 GPU 性能、物理系统返回、真实后台调度、硬件 Keystore 或设备覆盖安装；未测试真实 BookHub 服务。静态回退预览不冒充折射设备截图。绘制实现位于 `NavRefraction.kt`，依据 [Android RuntimeShader](https://developer.android.com/reference/android/graphics/RuntimeShader)、[RenderEffect](https://developer.android.com/reference/android/graphics/RenderEffect) 和 [Compose GraphicsLayer](https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/layer/GraphicsLayer) 的公开接口。

第三方许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 和 [licenses](licenses/)。沿用原公开仓策略，本项目代码暂未添加新的开源许可证；第三方声明仅适用于各自组件。源码树不含 APK、签名材料、凭据、实际服务地址、私人审计或原版 BookHub 源码。
