package app.leafreader
import org.json.JSONObject
import app.leafreader.data.*
import okhttp3.mockwebserver.*
import okio.Buffer

/** Local server and text/images are entirely self-authored. */
internal class LegadoFixtures {
 val server=MockWebServer()
 var traditional=false
 val text:String get()=(if(traditional)"這段自寫正文只用於聲明式規則、原生分頁和離線續讀驗證。"else"这段自写正文只用于声明式规则、原生分页和离线续读验证。").repeat(450)
 var failChapter=0
 val image=PublicSourceFixtures().image
 val calls=java.util.Collections.synchronizedList(mutableListOf<String>())
 init{server.start();server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse {
  check(r.method=="GET"&&r.getHeader("Authorization")==null&&r.getHeader("Cookie").isNullOrEmpty());calls+=r.path.orEmpty()
  val path=r.path.orEmpty().substringBefore('?');val json=path.startsWith("/j/")
  if(path.endsWith("cover.png"))return MockResponse().setBody(Buffer().write(image)).setHeader("Content-Type","image/png")
  if(path.endsWith("/2")&&failChapter!=0)return MockResponse().setResponseCode(failChapter)
  val body=when(path){
   "/search"->if(r.path.orEmpty().contains("page=2"))"<html><body></body></html>"else"<div class='book'><a href='book/one'><b class='name'>自写书一</b></a><span class='author'>自写作者</span><img src='/cover.png'/><p class='intro'>搜索简介</p></div>"
   "/book/one"->"<h1>自写详情书</h1><span class='writer'>自写作者</span><div class='intro'><p>详情规则取得的简介</p></div><img class='cover' src='/cover.png'/><a class='toc' href='../toc/one'>目录</a>"
   "/toc/one"->"<a class='chapter' href='../chapter/1'>第一章</a><a class='chapter' href='../chapter/2'>第二章</a>"
   "/chapter/1","/chapter/2"->"<h1>标题</h1><div id='content'><p>$text</p><p>段落保持独立。</p><script>neverExecute()</script></div>"
   "/j/search"->"""{"books":[{"name":"JSON 自写书","author":"自写作者","url":"book/one","cover":"/cover.png"}]}"""
   "/j/book/one"->"""{"name":"JSON 详情书","author":"自写作者","intro":"JSON 详情简介","toc":"../toc/one","cover":"/cover.png"}"""
   "/j/toc/one"->"""{"chapters":[{"title":"第一章","url":"../chapter/1"},{"title":"第二章","url":"../chapter/2"}]}"""
   "/j/chapter/1","/j/chapter/2"->JSONObject().put("text",text+"\n\n第二段自写正文。").toString()
   else->return MockResponse().setResponseCode(404)
  };return MockResponse().setBody(body).setHeader("Content-Type",if(json)"application/json; charset=utf-8"else"text/html; charset=utf-8")
 }}}
 val base:String get()=server.url("/").toString()
 fun rules(json:Boolean=false):JSONObject=JSONObject().put("bookSourceName",if(json)"JSON 自写书源"else"自写规则源").put("bookSourceUrl",base).put("searchUrl",if(json)"/j/search?q={{key}}"else"/search?q={{key}}&page={{page}}")
  .put("ruleSearch",if(json)JSONObject().put("bookList","$.books[*]").put("name","$.name").put("author","$.author").put("bookUrl","$.url").put("coverUrl","$.cover")else JSONObject().put("bookList","@css:.book").put("name",".name@text").put("author",".author@text").put("bookUrl","a@href").put("coverUrl","img@src").put("intro",".intro@text"))
  .put("ruleBookInfo",if(json)JSONObject().put("name","$.name").put("author","$.author").put("intro","$.intro").put("tocUrl","$.toc").put("coverUrl","$.cover")else JSONObject().put("name","h1@text").put("author",".writer@text").put("intro",".intro@html").put("tocUrl",".toc@href").put("coverUrl",".cover@src"))
  .put("ruleToc",if(json)JSONObject().put("chapterList","$.chapters[*]").put("chapterName","$.title").put("chapterUrl","$.url")else JSONObject().put("chapterList",".chapter").put("chapterName","@text").put("chapterUrl","@href"))
  .put("ruleContent",JSONObject().put("content",if(json)"$.text"else"#content@html"))
}
