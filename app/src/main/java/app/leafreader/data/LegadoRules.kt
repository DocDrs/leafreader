package app.leafreader.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

internal fun ruleError(field:String):Nothing=throw SourceProblem(FailureKind.UNSUPPORTED,"不兼容规则：$field",diagnostic="LG_RULE_UNSUPPORTED")

/** Self-written, deliberately non-executable subset. No upstream evaluator or source bundle. */
data class LegadoDefinition(val json:String,val retainedRights:Boolean) {
    val config:JSONObject get()=JSONObject(json)
    val name:String get()=config.getString("bookSourceName")
    val origin:String get()=config.getString("bookSourceUrl")
    val id:String get()="legado-v1-"+digest(json).take(32)
}
data class LegadoRejectedSource(val name:String,val reason:String)
data class LegadoImportResult(val accepted:List<LegadoDefinition>,val rejected:List<LegadoRejectedSource>)
internal object LegadoRules {
    const val MAX_IMPORT=1024*1024
    private val groups=linkedMapOf(
        "ruleSearch" to setOf("bookList","name","author","bookUrl","coverUrl","intro","nextUrl"),
        "ruleBookInfo" to setOf("name","author","coverUrl","intro","tocUrl"),
        "ruleToc" to setOf("chapterList","chapterName","chapterUrl"),
        "ruleContent" to setOf("content"))
    private val inert=setOf("bookSourceGroup","bookSourceComment","bookUrlPattern","customOrder","lastUpdateTime","weight","respondTime","enabled","enabledExplore","enabledReview","enabledCookieJar","bookSourceType")
    private val emptyOnly=setOf("header","loginUrl","loginUi","loginCheckJs","loginCheckJs","cookie","cookies","account","username","password","token","authorization","jsLib","variableComment","concurrentRate","exploreUrl","ruleExplore","ruleReview","ruleLogin","coverDecodeJs","bookSourceLoginUrl")
    fun parse(input:String,rights:Boolean):List<LegadoDefinition> {
        val result=review(input,rights)
        result.rejected.firstOrNull()?.let{throw SourceProblem(FailureKind.UNSUPPORTED,it.reason,diagnostic="LG_RULE_UNSUPPORTED")}
        return result.accepted
    }
    fun review(input:String,rights:Boolean):LegadoImportResult {
        if(!rights)ruleError("请先确认来源内容可下载并离线保留")
        if(input.toByteArray(Charsets.UTF_8).size>MAX_IMPORT)ruleError("JSON 超过 1 MB")
        val raw=input.removePrefix("\uFEFF").trim();checkDepth(raw)
        val tokener=JSONTokener(raw);val value=try{tokener.nextValue()}catch(_:Exception){ruleError("JSON 格式")}
        if(tokener.nextClean().code!=0)ruleError("JSON 尾部")
        val rows:List<Any> = when(value){is JSONObject->listOf(value);is JSONArray->{if(value.length() !in 1..30)ruleError("一次限 1–30 个来源");(0 until value.length()).map{value.get(it)}};else->ruleError("JSON 顶层")}
        val accepted=mutableListOf<LegadoDefinition>();val rejected=mutableListOf<LegadoRejectedSource>()
        rows.forEachIndexed{i,row->
            val name=((row as?JSONObject)?.opt("bookSourceName")as?String)?.filterNot{it.isISOControl()}?.take(80)?.takeIf{it.isNotBlank()}?:"来源 ${i+1}"
            try{accepted+=validate(row as?JSONObject?:ruleError("来源对象"))}
            catch(e:SourceProblem){rejected+=LegadoRejectedSource(name,e.message?:"不兼容规则")}
            catch(_:Exception){rejected+=LegadoRejectedSource(name,"不兼容规则：来源格式")}
        }
        return LegadoImportResult(accepted.distinctBy{it.id},rejected)
    }
    internal fun checkDepth(raw:String) {
        var depth=0;var quoted=false;var escaped=false
        for(c in raw){if(quoted){if(escaped)escaped=false else if(c=='\\')escaped=true else if(c=='"')quoted=false}else when(c){'"'->quoted=true;'[','{'->{if(++depth>40)ruleError("JSON 嵌套过深")};']','}'->{if(--depth<0)ruleError("JSON 结构")}}}
        if(quoted||depth!=0)ruleError("JSON 结构")
    }
    private fun empty(v:Any?)=v==null||v==JSONObject.NULL||v==false||v==""||v is JSONObject&&v.length()==0||v is JSONArray&&v.length()==0
    private fun safeField(value:String)=value.takeIf{it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,40}"))}?:"未知字段"
    private fun validate(root:JSONObject):LegadoDefinition {
        for(k in root.keys()) {
            when {
                k in groups || k in setOf("bookSourceName","bookSourceUrl","searchUrl")->Unit
                k in emptyOnly->{if(!empty(root.opt(k)))ruleError(k)}
                k in inert->{if(k=="bookSourceType"&&root.optInt(k,0)!=0 || k=="enabledCookieJar"&&root.optBoolean(k,false))ruleError(k)}
                !empty(root.opt(k))->ruleError(safeField(k)+"：不支持的字段")
            }
        }
        for(k in listOf("bookSourceName","bookSourceUrl","searchUrl"))if(root.opt(k) !is String)ruleError(k)
        val name=root.optString("bookSourceName").trim();if(name.length !in 1..80)ruleError("bookSourceName")
        val scope=try{CatalogOrigin(root.optString("bookSourceUrl"))}catch(_:SourceProblem){ruleError("bookSourceUrl：需要无凭据的 HTTPS 地址")};if(scope.root.rawQuery!=null)ruleError("bookSourceUrl 查询参数")
        val search=root.optString("searchUrl");if(search.length !in 1..2048 || !search.contains("{{key}}"))ruleError("searchUrl 仅支持 {{key}} / {{page}} 的 GET 地址")
        val checked=search.replace("{{key}}","sample").replace("{{page}}","1")
        if(checked.contains('{')||checked.contains('}')||checked.contains(",{"))ruleError("searchUrl 动态表达式或请求配置")
        val searchUri=try{scope.resolve(checked)}catch(_:SourceProblem){ruleError("searchUrl：仅支持同域 GET 地址")}
        val credentialKeys=setOf("token","access_token","apikey","api_key","secret","password","passwd","cookie","username","account","authorization","auth")
        if(searchUri.rawQuery.orEmpty().split('&').any{java.net.URLDecoder.decode(it.substringBefore('='),"UTF-8").lowercase() in credentialKeys})ruleError("searchUrl 凭据参数")
        val clean=JSONObject().put("bookSourceName",name).put("bookSourceUrl",scope.root.toString()).put("searchUrl",search)
        for((group,keys)in groups){val rules=root.optJSONObject(group)?:ruleError(group);val out=JSONObject()
            for(k in rules.keys())if(k !in keys&&!empty(rules.opt(k)))ruleError(group+"."+safeField(k))
            for(k in keys.sorted()){val v=rules.opt(k);if(v!=null&&v!=JSONObject.NULL&&v !is String)ruleError("$group.$k")
                val r=if(v==null||v==JSONObject.NULL)""else (v as String).trim();if(r.isNotEmpty()){BasicRule.validate(r,"$group.$k",k in setOf("bookList","chapterList"));out.put(k,r)}
            };clean.put(group,out)
        }
        for((g,k)in listOf("ruleSearch" to "bookList","ruleSearch" to "name","ruleSearch" to "bookUrl","ruleToc" to "chapterList","ruleToc" to "chapterName","ruleToc" to "chapterUrl","ruleContent" to "content"))if(clean.getJSONObject(g).optString(k).isBlank())ruleError("$g.$k")
        return LegadoDefinition(canonical(clean),true)
    }
    private fun canonical(j:JSONObject):String=j.keys().asSequence().toList().sorted().joinToString(",","{","}"){k->JSONObject.quote(k)+":"+when(val v=j.get(k)){is JSONObject->canonical(v);else->JSONObject.quote(v.toString())}}
}

/** CSS + final @text/@ownText/@html/@attribute, or simple JSON $.a.b[index]/[*]. */
internal object BasicRule {
    data class Html(val element:Element)
    private val attrs=setOf("text","ownText","html","href","src","content","title","data-src","data-href")
    private fun split(rule:String):Pair<String,String> {
        val r=rule.removePrefix("@css:").trim()
        val suffix=Regex("\\s*@([A-Za-z][A-Za-z0-9_-]*)$").find(r)
        if(suffix!=null)return r.substring(0,suffix.range.first).trim() to suffix.groupValues[1]
        if(r in attrs)return "" to r
        return r to "text"
    }
    fun validate(rule:String,field:String,list:Boolean=false) {
        if(Regex("(?i)@js|<\\s*/?\\s*js|javascript:|\\bjava\\.").containsMatchIn(rule))ruleError("$field：首版不执行 JS")
        var bracket=0;var quote:Char?=null;var marks=0
        for(c in rule.removePrefix("@css:")){if(quote!=null){if(c==quote)quote=null}else when(c){'\'' ,'"'->quote=c;'['->bracket++;']'->bracket--; '@'->if(bracket==0)marks++}}
        if(marks>1)ruleError("$field：不支持旧式多级 @，请使用 CSS")
        if(rule.length>512||Regex("(?i)@js|<\\s*/?\\s*js|javascript:|\\bjava\\.|##|\\{\\{|@get|@put|:matches|:has\\(").containsMatchIn(rule)||rule.contains("~=")||rule.contains("&&")||rule.contains("||")||rule.startsWith('/'))ruleError(field)
        if(rule.startsWith('$')){pathTokens(rule,field);return}
        val(selector,attr)=split(rule)
        if(attr !in attrs || list && selector.isEmpty())ruleError(field)
        try{if(selector.isNotEmpty())Jsoup.parse("<div></div>").select(selector)}catch(_:Exception){ruleError(field)}
    }
    private fun pathTokens(path:String,field:String):List<String> {
        if(!path.startsWith("$."))ruleError(field)
        val tail=path.substring(2);val token=Regex("[A-Za-z_][A-Za-z0-9_-]*(?:\\[(?:[0-9]{1,4}|\\*)])?")
        val parts=tail.split('.');if(parts.size>20||parts.any{!token.matches(it)})ruleError(field);return parts
    }
    fun document(body:String):Any=if(body.trimStart().let{it.startsWith('{')||it.startsWith('[')}) {
        try{LegadoRules.checkDepth(body);val t=JSONTokener(body);val v=t.nextValue();if(t.nextClean().code!=0)throw IllegalArgumentException();v}catch(_:Exception){throw SourceProblem(FailureKind.PROTOCOL,"来源 JSON 响应无效",diagnostic="LG_JSON")}
    }else {val d=Jsoup.parse(body);if(d.getAllElements().size>20000)throw SourceProblem(FailureKind.PROTOCOL,"来源页面结构过大",diagnostic="LG_SIZE");Html(d)}
    fun nodes(context:Any,rule:String,requiredPath:Boolean=false):List<Any> {
        if(rule.startsWith('$')) {
            var nodes=listOf(context)
            for(t in pathTokens(rule,"JSON path")) {
                val key=t.substringBefore('[');val index=t.substringAfter('[',"").removeSuffix("]")
                nodes=nodes.mapNotNull{val obj=it as? JSONObject;if(requiredPath&&(obj==null||!obj.has(key)))throw SourceProblem(FailureKind.PROTOCOL,"来源规则未找到列表字段",diagnostic="LG_FIELDS");obj?.opt(key)}.flatMap{v->when{index.isEmpty()->listOf(v);v !is JSONArray->emptyList();index=="*"->(0 until v.length()).map{v.get(it)};else->listOfNotNull(v.opt(index.toInt()))}}
                if(nodes.size>2000)throw SourceProblem(FailureKind.PROTOCOL,"来源条目数量超限",diagnostic="LG_SIZE")
            };return nodes.filter{it!=JSONObject.NULL}
        }
        val html=context as? Html?:throw SourceProblem(FailureKind.PROTOCOL,"来源规则与响应类型不符",diagnostic="LG_RESPONSE")
        val(selector,_)=split(rule);val found=if(selector.isEmpty())listOf(html.element)else html.element.select(selector).toList()
        if(found.size>2000)throw SourceProblem(FailureKind.PROTOCOL,"来源条目数量超限",diagnostic="LG_SIZE");return found.map(::Html)
    }
    fun value(context:Any,rule:String):String {
        if(rule.isBlank())return ""
        val(_,attr)=split(rule)
        return nodes(context,rule).joinToString("\n"){n->if(n is Html)when(attr){"text"->n.element.text();"ownText"->n.element.ownText();"html"->n.element.html();else->n.element.attr(attr)}else if(n is String||n is Number||n is Boolean)n.toString()else throw SourceProblem(FailureKind.PROTOCOL,"来源字段不是文字",diagnostic="LG_RESPONSE")}
    }
    fun content(context:Any,rule:String):String {
        if(rule.startsWith('$'))return value(context,rule).replace("\r\n","\n").trim()
        val(_,attr)=split(rule)
        return nodes(context,rule).joinToString("\n\n"){n->n as Html;if(attr in setOf("text","html"))EpubTextDocument(n.element.outerHtml()).slice().text else value(n,"@$attr")}.trim()
    }
}
