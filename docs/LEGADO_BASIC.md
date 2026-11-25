# Legado 基础声明式规则（1.1.6）

入口：设置 → 导入 Legado 基础规则 → 选择本地 JSON 或主动粘贴 JSON → 确认来源内容可访问且可离线保留 → 导入。导入不联网、不验证账号；在找书中选择该来源后，主动搜索才发请求。兼容目标为安卓版导出的现代嵌套规则 JSON，不是 Tauri 脚本书源。可导入一个对象或 1–30 个对象的数组；每个来源分别验证，有效来源照常保存，拒绝项按书源名称、字段和原因列出。混合导入停留在结果页，点击「完成」回到找书；JSON 顶层损坏、过大或缺少授权确认时不导入。不预装任何小说网站规则。

本实现是自写的有限兼容层，不包含原版评估器或代码。2026-11-25 [原仓库](https://github.com/gedoor/legado) 当前主分支仅保留公告，旧源码路径和 3.0 分支不可读，因此不能核对所有当前扩展语法，更不声称通用 Legado 规则都可运行。以下语法是本客户端明确支持和测试的范围。没有改用未经确认的规则集合或小说站作样本。

## 支持范围

- `bookSourceName`、`bookSourceUrl`、`searchUrl`；源要求明确 HTTPS origin/地址，仅控制测试允许 localhost/127.0.0.1/10.0.2.2 HTTP。`searchUrl` 仅 GET，支持 URL 编码后的 `{{key}}` 与从 1 开始的 `{{page}}`；不计算其他模板表达式。
- `ruleSearch`：`bookList`、`name`、`author`、`bookUrl`、`coverUrl`、`intro`，以及本客户端支持的可选 `nextUrl`。有 page 模板时允许主动逐页查找，空页终止、重复页报错、最多 100 页；有声明的下一页链接则跟随该链接。
- `ruleBookInfo`：`name`、`author`、`intro`、`coverUrl`、`tocUrl`。详情按需取得并保存；没有 tocUrl 则在详情页使用目录规则。
- `ruleToc`：`chapterList`、`chapterName`、`chapterUrl`。保持返回顺序，不推测卷/付费标记、重复链接、fragment 或分页目录的含义。
- `ruleContent`：`content`。仅 UTF-8 纯文字正文；HTML 段落经已有安全文字解析器提取，不执行脚本、不访问正文外链或图片。图片正文、分页章节不支持，会明确报错而不标为已缓存。
- HTML/CSS：普通 Jsoup CSS selector，可加 `@css:`，末尾 `@text`、`@ownText`、`@html`、`@href`、`@src`、`@content`、`@title`、`@data-src`、`@data-href`；行内 `@text`/`@href` 表示当前元素。列表规则返回元素。正文的 text/html 使用原生段落文本，不显示 HTML 标签。
- 简单 JSON 路径：`$.name`、`$.data.books[*]`、`$.items[0].title`，只支持点号字段和非负下标/通配数组；不支持过滤、递归、表达式、插值或任意代码。旧式多级 `@` 链不转换，会指出对应来源和字段。

非空 JS、`@js`、`java.*`、WebView、XPath、正则替换/正则 CSS、请求 body/method 配置、header、Cookie、登录字段、账户/口令/令牌、自定义 JS 库、目录/章节翻页等会在导入时指出不兼容字段，不会偷偷去掉再运行。空的这些字段可忽略。展示/排序/更新时间、发现和评论开关等不参与运行也不保存，未实现发现/评论入口。书籍 URL 模板拼接不支持；服务器应直接返回可解析的链接。已知凭据 query 参数也拒绝导入。

## 请求、缓存和身份

所有请求走独立的无认证 GET 客户端，无 BookHub token/Cookie/会话；只访问该来源精确 origin，拒绝跨域和降级，只允许同主机旧 HTTP 链接升级为 HTTPS。标准 TLS，不忽略证书。不符合本版同域规则的可选封面回退为占位，不访问该外域。单来源串行、最短间隔 500 ms；HTML/JSON/缩图各最多 512 KiB，JSON 导入最多 1 MiB。基础来源读取预算 30 秒，连接最长 15 秒、每次读取按剩余预算收紧；系统 DNS/连接阶段的实际终止仍受 Android 网络栈影响。429/503 遵循 Retry-After 或六小时退避，不循环重试。

源 ID 绑定规范化的可执行规则及名称；改规则会成为新来源，保留旧来源与原书架关联。书 ID 绑定来源内部的详情 URL，不按标题合并；BookHub、OPDS、古登堡与不同规则来源分别隔离。没有自动源匹配、批量回写或云进度同步。现有数据路径、阅读设置、签名、书架隐藏状态及进度格式不变。

首次保存目录后固定该有序清单版本，章节 ID 绑定该版本和实际 URL。读到的文字先在 no-backup 私有目录原子保存并校验 SHA-256，再显示正文/写进度。缓存章节不重新请求；未缓存章断网时明确报未缓存，完整/部分根据实际可读章节计算。全部章缓存完整才显示「完整缓存」，目录每章可查看本机状态。完整/已读章冷重建可纯本地续读，未缓存章仍需网络。

手动下载整本仅在用户操作后进行，沿用 Wi-Fi 默认/明确流量许可，每书至多 1000 章、64 MiB 章节缓存。取消/清除有持久 epoch，迟到响应不能重新写回。清除下载同时清阅读进度、保留书架；移出同时清进度和副本，沿用既有确认。清理不联网，保留公开书目以便重新搜索或下载。损坏章节不会静默改写原进度或伪装为完整。

限制：通用 HTML/JSON 没有可信远端内容版本协议，本版只固定目录结构和首次保存的章节内容，不能保证跨时间抓取的不同章节属于服务器同一编辑版。清除下载后才重新取目录。该来源应由使用者确认访问及保留许可；完成缓存本身不代表获得许可。

## 最小格式示例

以下为文档占位地址，不内置、不访问，不是现成书源。规则可由拥有内容/服务的人按自己站点填写。

```json
{
  "bookSourceName": "我的授权来源",
  "bookSourceUrl": "https://catalog.example.invalid/",
  "searchUrl": "/search?q={{key}}&page={{page}}",
  "ruleSearch": {"bookList": ".book", "name": ".name@text", "author": ".author@text", "bookUrl": "a@href"},
  "ruleBookInfo": {"name": "h1@text", "intro": ".intro@html", "tocUrl": ".toc@href"},
  "ruleToc": {"chapterList": ".chapter", "chapterName": "@text", "chapterUrl": "@href"},
  "ruleContent": {"content": "#content@html"}
}
```

## 验证边界

可交付测试使用本机真实 HTTP 服务器与完全自写的 HTML、JSON、正文、图片：ReaderApp 设置的粘贴导入；ACTION_OPEN_DOCUMENT 的选择契约回调与真实本地 JSON 文件读取；找书 IME、详情、目录、加架隐藏、书架直接阅读、原生分页、部分缓存、重建客户端与断网同字符续读、BookHub 合成会话保留。混合数组验证有效 CSS 来源、JS 来源及旧式多级 @ 来源，只有有效来源保存；真实 HTTP 验证中文关键词中空格与字面 + 的编码、相对详情/目录/章链接，以及受控 socket 超时与正常空结果的区别。另覆盖脚本/凭据拒绝、畸形响应、来源隔离、损坏缓存、清除迟到响应、重试和 Wi-Fi。

文件选择测试为 Android/Robolectric 契约回调，未运行真实系统文件管理器。未使用真实 BookHub 账号、未测试第三方小说网站或任意下载的 Legado 规则；主机 Compose 原生绘制不等于手机 GPU、真实进程回收或设备覆盖安装。1.1.5 的古登堡首次慢响应/离线搜索、OPDS 与原阅读/下载/同步合成回归保留。
