# OPDS 1 订阅（1.1.5）

入口：设置 → 添加 OPDS 订阅 → 名称、HTTPS 目录及离线保留权确认 → 添加。找书页选择订阅，浏览目录或输入搜索，加入本机书架后可直接阅读。设置底部「版本」读取已安装包元数据。

支持 Atom OPDS 1 navigation/acquisition、同域 next、发布的 OpenSearch 模板、EPUB 及明确封面链接。没有目录搜索能力则提示浏览；不遍历所有书目代替搜索。不支持 OPDS 2、账号/Cookie、购买/借阅/DRM 或跨域资源。仅控制测试允许 localhost/127.0.0.1/10.0.2.2 明文 HTTP；用户公共地址要求 HTTPS。同域旧 HTTP 链接只升级为同域 HTTPS，不降级、不跟跨域跳转；使用系统 TLS，不忽略证书。网络请求不复用 BookHub 客户端、Cookie 或令牌。

## 实际公开入口及规则

- [Wolne Lektury OPDS](https://wolnelektury.pl/opds/)：2026-11-25 实际 200、Atom navigation；作者导航 `/opds/author/` 实际 200；由 feed 发现的 `/static/opensearch.xml` 给出 Atom 搜索模板 `/opds/search/?q={searchTerms}&author={atom:author}&translator={atom:contributor}&title={atom:title}`，未使用私人/猜测接口。公开示例不会自动订阅或登录。
- [官方使用规则](https://wolnelektury.pl/info/zasady-wykorzystania/) 明确允许通过移动应用阅读、下载到手机；作品为公有领域或 CC BY-SA/Free Art，具体版本保留作者、来源和许可。客户端保存原 EPUB，不剥除许可页、不另行转传书籍或封面。
- `robots.txt` 未禁止 `/opds/` 与声明的 `/media/book/epub/`；不访问用户书架、登录或被禁检索网页。原 OPDS 的同域 HTTP 链接升级 HTTPS，实际 HTTPS EPUB 可达。
- [Unglue 官方 API 说明](https://unglue.it/api/help) 和公开 `/api/opds/` 亦已只读确认，未内置或用其替代已验通的 Wolne；跨域获取不在本版范围。
- 古登堡官方 OPDS 维护仍保留退避，未频繁重试、未试探 OPDS 2；古登堡 CSV 与新增通用 OPDS 是两个明确不同的目录路径。

## 本机隔离与缓存

订阅身份绑定规范化目录 URL；书籍身份绑定 Atom entry ID，并保留订阅命名空间。不会按书名合并、不会写入 BookHub 书架，不清 BookHub 登录。只有用户确认可独立保留的来源才能加入本版免登录订阅。取得整包后校验 EPUB、CRC 和 SHA-256，成功标记后离线纯本地打开，保留原始版本绑定的章节与字符进度。书架封面优先已有 EPUB 明确封面，缩略图缓存 2 MB、最大 180×270，不因封面下载整本。完整包最多 32 MB；通用 EPUB 安全限制沿用现有公开源。清除下载/移出书架仍按当前产品规则清此书进度及副本；持久 epoch 阻止迟到响应写回。默认下载仅 Wi-Fi，显式允许流量仍可用。

## 验证边界

可交付自动用例均使用自写内容：真实本机 HTTP → 设置添加 → 导航 → 找书 → 加架 → 原生封面/正文 → 翻页 → 重建 ViewModel/来源注册表 → 停止服务器 → 同章同字符续读；另覆盖无凭据、同 ID 跨订阅隔离、OpenSearch、401、维护退避和不支持的地址。

2026-11-25 已通过真实 Wolne HTTPS ReaderApp 验证：从设置填入并确认官方示例，浏览 Autorzy，发现并提交 OpenSearch「Bajka」，加入 Henryk Sienkiewicz 的《Bajka》，下载实际 333,890 字节 EPUB（SHA-256 `a64fbeb4321c748293819f8652d9fdb92f3230e287b5827462f9f117f88d947b`），原生显示明确封面与 OPF part1 正文、翻页后冷重建并断网，保持同章同字符锚点，离线 HTTP 为 0。该 EPUB 有 12 项 spine，部分附页无文本使字符加权总篇幅不完整，应用使用既有章节估算而不编造全书字符总数。独立测试原件和记录保存在交付外的私有测试目录；不将书正文、封面、原书测试截图或可还原内容打入 APK/源码。主机 Robolectric/Compose 原生绘制不等于实机 GPU 或真正的 Android 进程回收/安装测试。未使用真实 BookHub 账号，未测试授权/借阅目录。
