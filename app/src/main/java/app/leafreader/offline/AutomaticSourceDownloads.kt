package app.leafreader.offline

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import app.leafreader.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

data class AutomaticSourceRequest(val key:String,val source:String,val book:String,val jobId:Int,val nonce:String,val state:String,val message:String)

/** Only new explicit shelf additions. No BookHub client, session, cookie, password or address in this queue. */
@android.annotation.SuppressLint("StaticFieldLeak","ApplySharedPref")
class AutomaticSourceDownloads(context:Context,val connection:ConnectionState=AndroidConnectionState(context),
    val scheduleJobs:Boolean=true,private val resolver:((String)->IndependentBookSource?)?=null) {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("automatic-independent-downloads-v1",Context.MODE_PRIVATE)
    private val policy=GlobalDownloadPreferences(app)
    private val scheduler=app.getSystemService(JobScheduler::class.java)
    private val providers=ConcurrentHashMap<String,IndependentBookSource>()
    private val running=ConcurrentHashMap<String,Job>()
    private val guard=guards.getOrPut(app.applicationInfo.dataDir){Any()}
    companion object {
        private val guards=ConcurrentHashMap<String,Any>()
        private val instances=ConcurrentHashMap<String,AutomaticSourceDownloads>()
        fun get(context:Context)=instances.getOrPut(context.applicationInfo.dataDir){AutomaticSourceDownloads(context)}
    }
    init{scheduleMore()}
    fun register(source:IndependentBookSource):IndependentBookSource=providers.compute(source.id){_,old->
        if(old!=null&&requests().any{it.source==source.id&&running.containsKey(it.key)})old else source
    }!!
    fun runningFor(id:String)=requests().any{it.source==id&&running.containsKey(it.key)}
    fun provider(id:String):IndependentBookSource? {
        providers[id]?.let{return it}
        val source=resolver?.invoke(id)?:when {
            id==GutenbergSource.SOURCE->GutenbergSource(app,network=connection)
            id.startsWith("opds-v1-")->OpdsSubscriptions(app).all().firstOrNull{it.id==id&&it.retainedRights}?.let{OpdsSource(app,it,connection)}
            id.startsWith("legado-v1-")->LegadoSources(app).all().firstOrNull{it.id==id&&it.retainedRights}?.let{LegadoSource(app,it,connection)}
            else->null
        }
        return source?.takeIf{it.id==id}?.let(::register)
    }
    private fun encode(r:AutomaticSourceRequest)=JSONObject().put("key",r.key).put("source",r.source).put("book",r.book).put("job",r.jobId).put("nonce",r.nonce).put("state",r.state).put("message",r.message).toString()
    private fun decode(value:String):AutomaticSourceRequest?=runCatching{JSONObject(value).let{AutomaticSourceRequest(it.getString("key"),it.getString("source"),it.getString("book"),it.getInt("job"),it.getString("nonce"),it.getString("state"),it.getString("message"))}}.getOrNull()
    private fun key(source:String,book:String)=digest("$source|$book")
    fun requests()=prefs.all.filterKeys{it.startsWith("request-")}.values.mapNotNull{(it as?String)?.let(::decode)}
    fun request(key:String)=prefs.getString("request-$key",null)?.let(::decode)
    fun request(source:String,book:String)=request(key(source,book))
    private fun save(r:AutomaticSourceRequest){check(prefs.edit().putString("request-${r.key}",encode(r)).commit())}
    fun busy(source:String,book:String)=request(source,book)?.state in listOf("QUEUED","WAITING","RUNNING")
    fun label(source:String,book:String):String?=request(source,book)?.let{when(it.state){"QUEUED","WAITING","RUNNING","FAILED"->it.message;else->null}}
    suspend fun enqueue(source:IndependentBookSource,book:Book,stillWanted:()->Boolean={true}):Boolean {
        if(!policy.enabled()||!source.supportsAutomaticDownload||!stillWanted())return false
        register(source)
        if(source.status(book.id).complete)return false
        synchronized(guard){
            if(!policy.enabled()||!stillWanted()||busy(source.id,book.id)||ReadingStore(app,source.id).cleanupPending(book.id))return false
            if(ReadingStore(app,source.id).peekShelf().none{it.id==book.id})return false
            val previous=request(source.id,book.id)
            val id=previous?.jobId?:prefs.getInt("next-job",1000000).also{check(prefs.edit().putInt("next-job",it+1).commit())}
            save(AutomaticSourceRequest(key(source.id,book.id),source.id,book.id,id,UUID.randomUUID().toString(),"QUEUED",if(policy.networkAllowed(connection))"等待下载"else policy.waitingLabel()))
        }
        scheduleMore();OfflineEvents.changed();return true
    }
    fun cancel(source:String,book:String) {
        synchronized(guard){request(source,book)?.let{r->scheduler?.cancel(r.jobId);save(r.copy(nonce=UUID.randomUUID().toString(),state="CANCELLED",message="已取消"));running[r.key]?.cancel()}}
        OfflineEvents.changed()
    }
    fun cancelAutomatic(){requests().filter{it.state in listOf("QUEUED","WAITING","RUNNING")}.forEach{cancel(it.source,it.book)}}
    /** Replace only active automatic executions; identity and partial files remain intact. */
    fun networkPolicyChanged() {
        synchronized(guard){requests().filter{it.state in listOf("QUEUED","WAITING","RUNNING")}.forEach{r->
            save(r.copy(nonce=UUID.randomUUID().toString(),state="WAITING",message=if(policy.networkAllowed(connection))"等待系统继续"else policy.waitingLabel()))
            scheduler?.cancel(r.jobId);running[r.key]?.cancel()
        }}
        scheduleMore();OfflineEvents.changed()
    }
    private fun update(r:AutomaticSourceRequest,state:String,message:String)=synchronized(guard){val current=request(r.key)?:return@synchronized
        if(current.nonce==r.nonce&&current.state!="CANCELLED")save(current.copy(state=state,message=message))}
    fun pause(r:AutomaticSourceRequest){synchronized(guard){val current=request(r.key);if(current?.nonce==r.nonce&&current.state in listOf("QUEUED","WAITING","RUNNING"))save(current.copy(state="WAITING",message="等待系统继续"))};OfflineEvents.changed()}
    private fun valid(r:AutomaticSourceRequest)=policy.enabled()&&request(r.key)?.let{it.nonce==r.nonce&&it.state in listOf("QUEUED","WAITING","RUNNING")}==true&&!ReadingStore(app,r.source).cleanupPending(r.book)
    fun jobInfo(r:AutomaticSourceRequest)=JobInfo.Builder(r.jobId,ComponentName(app,AutomaticSourceDownloadService::class.java))
        .setExtras(PersistableBundle().apply{putString("request",r.key);putString("nonce",r.nonce)})
        .setRequiredNetworkType(if(policy.wifiOnly())JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY).setRequiresStorageNotLow(true).setPersisted(true)
        .setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
    /** One independent-source job at a time; source instances also retain their original rate limiters. */
    fun scheduleMore(finishedJob:Int?=null) {
        if(!scheduleJobs||!policy.enabled())return
        val pending=scheduler?.allPendingJobs.orEmpty().filter{it.service==ComponentName(app,AutomaticSourceDownloadService::class.java)&&it.id!=finishedJob}
        if(pending.isNotEmpty())return
        val next=requests().filter{it.state in listOf("QUEUED","WAITING","RUNNING")&&it.jobId!=finishedJob}.sortedBy{it.jobId}.firstOrNull()?:return
        if(try{scheduler?.schedule(jobInfo(next))!=JobScheduler.RESULT_SUCCESS}catch(_:RuntimeException){true})update(next,"FAILED","系统暂未安排下载，可手动下载")
    }
    /** Shared by JobService and controlled host tests. Returns whether Android should retry with backoff. */
    suspend fun execute(r:AutomaticSourceRequest):Boolean {
        if(!valid(r)){if(request(r.key)?.nonce==r.nonce)cancel(r.source,r.book);return false}
        val job=coroutineContext.job
        synchronized(guard){if(!valid(r))return false;if(running.putIfAbsent(r.key,job)!=null)return true}
        var retry=false
        var loaded:IndependentBookSource?=null
        try {
            val source=provider(r.source);loaded=source
            if(source==null||!source.supportsAutomaticDownload){update(r,"FAILED","书源不支持后台下载，可手动下载");return false}
            if(ReadingStore(app,r.source).peekShelf().none{it.id==r.book}){update(r,"CANCELLED","已取消");return false}
            if(source.status(r.book).complete){update(r,"COMPLETE","完整缓存");return false}
            if(!policy.networkAllowed(connection)){update(r,"WAITING",policy.waitingLabel());return true}
            update(r,"RUNNING","下载中")
            withContext(DownloadIntentGuard{valid(r)&&policy.networkAllowed(connection)}){checkDownloadPolicy();source.download(r.book,true);checkDownloadPolicy()}
            if(valid(r)) {
                if(!source.status(r.book).complete)throw SourceProblem(FailureKind.PROTOCOL,"缓存尚未完整")
                update(r,"COMPLETE","完整缓存")
            }
        }catch(e:CancellationException){throw e}
        catch(_:DownloadStopped){if(valid(r)){update(r,"WAITING",policy.waitingLabel());retry=true}}
        catch(e:Exception){
            if(valid(r))when {
                e is SourceProblem&&e.kind==FailureKind.NETWORK&&(e.httpStatus==null||e.httpStatus>=500||e.httpStatus==429)->{update(r,"WAITING","网络暂不可用，稍后重试");retry=true}
                e is java.io.IOException&&listOf("ENOSPC","No space","空间").any{e.message?.contains(it,true)==true}->update(r,"FAILED","空间不足，可释放空间后重试")
                else->update(r,"FAILED","下载失败，可手动重试")
            }
        }finally{loaded?.trimDownloadMemory();running.remove(r.key,job);OfflineEvents.changed()}
        return retry
    }
}
