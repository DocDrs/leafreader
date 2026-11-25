package app.leafreader.offline

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.PersistableBundle
import app.leafreader.data.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class DownloadRequest(val key:String,val owner:String,val bookId:String,val title:String,val jobId:Int,
    val nonce:String,val allowMetered:Boolean,val automatic:Boolean,val state:String,val message:String)
enum class BatchEnqueueResult { ADDED, PRESENT, REJECTED }

/** Credentials stay exclusively in the existing Keystore vault. The queue contains identity hashes only. */
@android.annotation.SuppressLint("StaticFieldLeak","ApplySharedPref") // Application context only; queue commits precede system scheduling.
class OfflineDownloads(context:Context,val connection:ConnectionState=AndroidConnectionState(context.applicationContext),
    private val vault:SessionVault=EncryptedSessionVault(context),private val scheduleJobs:Boolean=true,
    private val restoreClient:(SavedSession)->BookHubClient={BookHubConnectionGate().restore(it)}) {
    private val context=context.applicationContext
    companion object {
        @Volatile private var singleton:OfflineDownloads?=null
        private val queueGuards=java.util.concurrent.ConcurrentHashMap<String,Any>()
        fun get(context:Context):OfflineDownloads=singleton?:synchronized(this){singleton?:OfflineDownloads(context.applicationContext).also{singleton=it}}
    }
    private val prefs=context.getSharedPreferences("offline-queue-v1",Context.MODE_PRIVATE)
    private val scheduler=context.getSystemService(JobScheduler::class.java)
    @Volatile private var activeOwner:String?=null
    @Volatile private var activeClient:BookHubClient?=null
    private val guard=queueGuards.getOrPut(context.applicationInfo.dataDir){Any()}
    init {
        GlobalDownloadPreferences(context)
        if(prefs.getBoolean("global-policy-cancel-pending",false)) {
            requests().filter{it.automatic&&it.state in listOf("QUEUED","WAITING","RUNNING","PAUSED")}.forEach{cancel(it.owner,it.bookId)}
            check(prefs.edit().putBoolean("global-policy-cancel-pending",false).commit())
        }
        if(scheduleJobs)try {
            context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object:ConnectivityManager.NetworkCallback(){
                override fun onAvailable(network:Network){OfflineEvents.changed()}
                override fun onLost(network:Network){OfflineEvents.changed()}
                override fun onCapabilitiesChanged(network:Network,caps:android.net.NetworkCapabilities){OfflineEvents.changed()}
            })
        }catch(_:SecurityException){/* Missing capability is displayed as offline; no network is forced. */}
    }
    fun cache(owner:String)=OfflineStore(File(context.noBackupFilesDir,"offline-v1/"+sha(owner.toByteArray())),owner)
    fun source(client:BookHubClient)=BookHubSource(client,cache(BookHubSource.ownerId(client)),connection)
    fun automaticWifi()=GlobalDownloadPreferences(context).enabled()
    fun setAutomaticWifi(value:Boolean) {
        GlobalDownloadPreferences(context).set(value)
        prefs.edit().putBoolean("auto-wifi",value).commit()
        if(!value)requests().filter{it.automatic && it.state in listOf("QUEUED","WAITING","RUNNING","PAUSED")}.forEach{cancel(it.owner,it.bookId)}
        OfflineEvents.changed()
    }
    fun automaticWifiOnly()=GlobalDownloadPreferences(context).wifiOnly()
    fun canTransfer(r:DownloadRequest,client:BookHubClient)=valid(r,client)&&(!r.automatic||GlobalDownloadPreferences(context).networkAllowed(connection))
    /** Network preference changes never touch manual requests, content, or saved reading anchors. */
    fun networkPolicyChanged() {
        synchronized(guard){requests().filter{it.automatic&&it.state in listOf("QUEUED","WAITING","RUNNING")}.forEach{r->
            save(r.copy(nonce=UUID.randomUUID().toString(),state="WAITING",message=GlobalDownloadPreferences(context).waitingLabel()))
            scheduler?.cancel(r.jobId)
        }}
        activeOwner?.let{scheduleMore(it)};OfflineEvents.changed()
    }
    private fun encode(r:DownloadRequest)=JSONObject().put("key",r.key).put("owner",r.owner).put("book",r.bookId).put("title",r.title)
        .put("job",r.jobId).put("nonce",r.nonce).put("metered",r.allowMetered).put("auto",r.automatic).put("state",r.state).put("message",r.message).toString()
    private fun decode(s:String):DownloadRequest?=try {val j=JSONObject(s);DownloadRequest(j.getString("key"),j.getString("owner"),j.getString("book"),j.getString("title"),j.getInt("job"),j.getString("nonce"),j.getBoolean("metered"),j.getBoolean("auto"),j.getString("state"),j.getString("message"))}catch(_:Exception){null}
    fun request(key:String)=prefs.getString("request-$key",null)?.let(::decode)
    fun requestsForOwner(owner:String)=requests().filter{it.owner==owner}
    private fun requests()=prefs.all.filterKeys{it.startsWith("request-")}.values.mapNotNull{(it as? String)?.let(::decode)}
    fun key(owner:String,bookId:String)=sha("$owner|$bookId".toByteArray())
    fun activate(client:BookHubClient) {
        val owner=BookHubSource.ownerId(client)
        if(activeOwner!=null && activeOwner!=owner)deactivate()
        activeOwner=owner;activeClient=client
        requests().filter{it.owner==owner && !ReadingStore(context,owner).cleanupPending(it.bookId) && it.state in listOf("QUEUED","WAITING","RUNNING","PAUSED")}.forEach{
            if(it.automatic&&!automaticWifi())cancel(it.owner,it.bookId)
            else save(it.copy(state="QUEUED",message=if(it.automatic && !GlobalDownloadPreferences(context).networkAllowed(connection))GlobalDownloadPreferences(context).waitingLabel() else if(!it.automatic && !it.allowMetered && !connection.wifi) "等待 Wi-Fi" else "等待系统调度"))
        }
        scheduleMore(owner)
        OfflineEvents.changed()
    }
    fun deactivate() {
        val owner=activeOwner;activeOwner=null;activeClient=null
        requests().filter{it.owner==owner && it.state in listOf("QUEUED","WAITING","RUNNING")}.forEach{
            scheduler?.cancel(it.jobId);save(it.copy(state="PAUSED",message="等待对应账号登录"))
        };OfflineEvents.changed()
    }
    private fun permitted(owner:String,book:Book)=
        (activeClient?.takeIf{activeOwner==owner}?.observedReadPermission(book.id)?:ReadingStore(context,owner).knownReadPermission(book.id)?:book.readAllowed)!=false
    /** Batch idempotence must be atomic with enqueue, including simultaneous confirmations / automatic jobs. */
    fun enqueueIfNeeded(owner:String,book:Book,allowMetered:Boolean=false,automatic:Boolean=false,stillWanted:()->Boolean={true}):BatchEnqueueResult=synchronized(guard) {
        if(!stillWanted())return@synchronized BatchEnqueueResult.REJECTED
        if(owner!=activeOwner || activeClient?.snapshot()==null || !permitted(owner,book))return@synchronized BatchEnqueueResult.REJECTED
        if(busy(owner,book.id) || cache(owner).status(book.id,verify=true).complete)return@synchronized BatchEnqueueResult.PRESENT
        if(enqueue(owner,book,allowMetered,automatic))BatchEnqueueResult.ADDED else BatchEnqueueResult.REJECTED
    }
    fun enqueue(owner:String,book:Book,allowMetered:Boolean=false,automatic:Boolean=false):Boolean=synchronized(guard) {
        if(owner!=activeOwner || activeClient?.snapshot()==null || !permitted(owner,book) || ReadingStore(context,owner).cleanupPending(book.id))return@synchronized false
        if(automatic && !automaticWifi())return@synchronized false
        val key=key(owner,book.id);val previous=request(key)
        val id=previous?.jobId?:prefs.getInt("next-job",7000).also{prefs.edit().putInt("next-job",it+1).commit()}
        scheduler?.cancel(id)
        val r=DownloadRequest(key,owner,book.id,book.title,id,UUID.randomUUID().toString(),allowMetered,automatic,"QUEUED",if(automatic && !GlobalDownloadPreferences(context).networkAllowed(connection))GlobalDownloadPreferences(context).waitingLabel() else if(!automatic && !allowMetered && !connection.wifi)"等待 Wi-Fi" else "等待系统调度")
        save(r);scheduleMore(owner);OfflineEvents.changed();true
    }
    private fun save(r:DownloadRequest){prefs.edit().putString("request-${r.key}",encode(r)).commit()}
    fun jobInfo(r:DownloadRequest):JobInfo=JobInfo.Builder(r.jobId,ComponentName(context,OfflineDownloadService::class.java))
        .setExtras(PersistableBundle().apply{putString("request",r.key);putString("nonce",r.nonce)})
        .setRequiredNetworkType(if(if(r.automatic)!automaticWifiOnly()else r.allowMetered)JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_UNMETERED)
        .setRequiresStorageNotLow(true).setPersisted(true).setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
    fun scheduleMore(owner:String,finishedJob:Int?=null) {
        if(!scheduleJobs || activeOwner!=owner)return
        val pending=scheduler?.allPendingJobs.orEmpty().filter{it.service==ComponentName(context,OfflineDownloadService::class.java) && it.id!=finishedJob}.map{it.id}.toSet()
        var slots=(2-pending.size).coerceAtLeast(0)
        for(r in requests().filter{it.owner==owner && !ReadingStore(context,owner).cleanupPending(it.bookId) && it.state in listOf("QUEUED","WAITING","RUNNING") && (!it.automatic || automaticWifi()) && it.jobId !in pending}.sortedBy{it.jobId}) {
            if(slots<=0)break
            val result=try{scheduler?.schedule(jobInfo(r))}catch(_:RuntimeException){null}
            if(result==JobScheduler.RESULT_SUCCESS)slots-- else save(r.copy(state="FAILED",message="系统暂未安排下载，请重试"))
        }
    }
    fun cancel(owner:String,bookId:String) {
        synchronized(guard) {request(key(owner,bookId))?.let{scheduler?.cancel(it.jobId);save(it.copy(nonce=UUID.randomUUID().toString(),state="CANCELLED",message="已取消，已保存内容保留"))};cache(owner).cancelWrites(bookId)}
        scheduleMore(owner)
        OfflineEvents.changed()
    }
    /** Queue nonce and cache generation change under the same queue guard. */
    fun eraseAndCancel(owner:String,bookId:String,cleanupToken:String?=null) {
        synchronized(guard) {
            if(cleanupToken!=null && ReadingStore(context,owner).cleanupToken(bookId)!=cleanupToken)return
            cancel(owner,bookId);cache(owner).clear(bookId)
        }
        OfflineEvents.changed()
    }
    fun finishErase(owner:String,bookId:String,token:String):Boolean = synchronized(guard) {
        ReadingStore(context,owner).finishCleanup(bookId,token)
    }
    fun update(r:DownloadRequest,state:String,message:String) {
        synchronized(guard) {
            val current=request(r.key)?:return
            if(current.nonce!=r.nonce || current.state in listOf("CANCELLED","PAUSED"))return
            if(current.state==state && current.message==message)return
            save(current.copy(state=state,message=message))
        };OfflineEvents.changed()
    }
    fun resolveClient(r:DownloadRequest):BookHubClient? {
        activeClient?.takeIf{activeOwner==r.owner && it.snapshot()!=null}?.let{return it}
        if(activeOwner!=null)return null
        val saved=(vault.load() as? SessionLoad.Ready)?.session?:return null
        val client=restoreClient(saved)
        if(BookHubSource.ownerId(client)!=r.owner)return null
        activeOwner=r.owner;activeClient=client
        client.onSessionChanged={session->if(activeClient===client && activeOwner==r.owner){if(session==null){vault.clear();deactivate()}else vault.save(session)}}
        return client
    }
    fun valid(r:DownloadRequest,client:BookHubClient):Boolean {
        val current=request(r.key)
        return !ReadingStore(context,r.owner).cleanupPending(r.bookId) && activeClient===client && activeOwner==r.owner && client.snapshot()!=null && client.observedReadPermission(r.bookId)!=false && (!r.automatic || automaticWifi()) && current?.nonce==r.nonce && current.state in listOf("QUEUED","WAITING","RUNNING")
    }
    fun label(owner:String,bookId:String):String {
        val status=cache(owner).status(bookId);val r=request(key(owner,bookId))
        return when {
            r?.state in listOf("QUEUED","WAITING","RUNNING","PAUSED")->r!!.message
            r?.state=="FAILED"->"${status.label} · ${r.message}"
            else->status.label
        }
    }
    fun busy(owner:String,bookId:String)=request(key(owner,bookId))?.state in listOf("QUEUED","WAITING","RUNNING")
}
