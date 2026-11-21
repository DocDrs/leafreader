package app.leafreader

import org.json.JSONArray
import org.json.JSONObject

/** Locally invented protocol fixtures. No upstream code, content, addresses or credentials. */
object BookHubFixtures {
    fun envelope(data: JSONObject) = JSONObject().put("code", "OK").put("data", data).toString()
    fun session(account: String = "fixture-account", change: Boolean = false) = JSONObject()
        .put("account", JSONObject().put("id", account).put("role", "READER").put("mustChangePassword", change))
        .put("accessToken", "fixture-access").put("refreshToken", "fixture-refresh").put("deviceId", "fixture-device")
    fun book(id: String = "public:1", title: String = "协议样书") = JSONObject().put("bookId", id).put("title", title)
        .put("author", "测试自写").put("description", "仅用于本地协议验证").put("format", "TXT")
    fun page(id: String = "public:1", next: String? = null) = JSONObject().put("items", JSONArray().put(book(id)))
        .put("hasMore", next != null).put("nextCursor", next ?: JSONObject.NULL)
    val firstText = "第一章　江上的灯\n" + "灯亮了，风很轻。🌙\n".repeat(45)
    val secondText = "第二章　归岸\n" + "船靠岸，故事仍在继续。\n".repeat(45)
    fun txtManifest(version: String = "fixture-v1"): JSONObject {
        val split = firstText.codePointCount(0, firstText.length)
        val end = split + secondText.codePointCount(0, secondText.length)
        return JSONObject().put("bookId", "public:1").put("format", "TXT").put("contentVersion", version)
            .put("expiresAt", "2099-01-01T00:00:00Z").put("contentUrl", "")
            .put("spine", JSONArray().put("a").put("b"))
            .put("resources", JSONArray().put(resource("a")).put(resource("b")))
            .put("toc", JSONArray().put(JSONObject().put("label", "第一章　江上的灯").put("codePointOffset", 0))
                .put(JSONObject().put("label", "第二章　归岸").put("codePointOffset", split)))
            .put("metadata", JSONObject().put("codePointLength", end).put("segments", JSONArray()
                .put(JSONObject().put("resourceId", "a").put("startCodePoint", 0).put("endCodePoint", split))
                .put(JSONObject().put("resourceId", "b").put("startCodePoint", split).put("endCodePoint", end))))
    }
    fun resource(id: String) = JSONObject().put("resourceId", id).put("mediaType", "text/plain")
        .put("url", "/api/v1/books/public%3A1/resources/$id?ticket=fixture-ticket")
    fun epubManifest(): JSONObject = JSONObject().put("bookId", "public:1").put("format", "EPUB")
        .put("contentVersion", "epub-fixture-v1").put("expiresAt", "2099-01-01T00:00:00Z")
        .put("spine", JSONArray().put("a"))
        .put("resources", JSONArray().put(resource("a").put("mediaType", "application/xhtml+xml")))
        .put("toc", JSONArray().put(JSONObject().put("label", "一、出发").put("resourceId", "a").put("href", "/api/v1/books/public%3A1/resources/a#one"))
            .put(JSONObject().put("label", "二、到达").put("resourceId", "a").put("href", "/api/v1/books/public%3A1/resources/a#two")))
        .put("metadata", JSONObject())
    val epubText = "<html><head><style>SECRET_STYLE</style><script>SECRET_SCRIPT</script></head><body>" +
        "<h1 id='one'>出发</h1><p>第一段，走向江边。🌙</p><h1 id='two'>到达</h1><p>第二段，到达对岸。</p>" +
        "<img src='https://invalid.example/never-fetch.png'/></body></html>"
}
