package app.leafreader.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import app.leafreader.data.*
import app.leafreader.offline.*
import app.leafreader.covers.CoverThumbnailStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.coroutineContext
import java.util.concurrent.ConcurrentHashMap

data class ShelfApplied(val intent:ShelfIntent)
/** One Main-dispatcher session and mutex for foreground and JobService work. Wire and cleanup disk IO are off Main. */
@android.annotation.SuppressLint("StaticFieldLeak","ApplySharedPref") // Persist expiry/intent boundaries before a worker can stop.
class ShelfSyncRuntime(val context:Context,val vault:SessionVault=EncryptedSessionVault(context),
    private val gate:BookHubConnectionGate=BookHubConnectionGate(),val downloads:OfflineDownloads=OfflineDownloads.get(context),
    private val clock:()->Long={System.currentTimeMillis()},private val scheduleJobs:Boolean=true) {
    val intents=ShelfIntents(context)
    val mutex=Mutex()
    val revision=MutableStateFlow(0L)
    val running=MutableStateFlow<Set<String>>(emptySet())
    private val applied=MutableSharedFlow<ShelfApplied>(replay=1,extraBufferCapacity=32)
    val completions=applied.asSharedFlow()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var client:BookHubClient?=null
    private var generation=0L
    private var kick:Job?=null
    private var kickRequested:BookHubClient?=null
    val membershipEpoch:Long get()=intents.epoch
    fun activeClient()=client
    private fun signal(){revision.value++;OfflineEvents.changed()}
    fun activate(next:BookHubClient,login:Boolean):BookHubClient {
        val chosen=if(!login && client?.snapshot()!=null && client?.snapshot()==next.snapshot())client!! else next
        if(client!==chosen){generation++;client=chosen;downloads.activate(chosen)}
        val g=generation
        chosen.onSessionChanged={saved->if(client===chosen && generation==g){
            if(saved==null){vault.clear();downloads.deactivate();context.getSharedPreferences("offline-retention-note-v1",Context.MODE_PRIVATE).edit().putBoolean("show",true).putString("reason","EXPIRED").commit()}else vault.save(saved)
            signal()
        }}
        if(login)intents.resume(BookHubSource.ownerId(chosen),clock())
        scheduleDaily();signal();return chosen
    }
    fun deactivate(){downloads.deactivate();client?.snapshot()?.let{intents.pause(BookHubSource.ownerId(client!!))};generation++;client=null;scheduler()?.cancel(RETRY);signal()}
    fun enqueue(c:BookHubClient,book:Book,present:Boolean):Boolean {
        if(client!==c || c.snapshot()==null || BookHubSource.ownerId(c)!=book.sourceId)return false
        val saved=intents.enqueue(c.snapshot()!!,book,present,!present,clock())?:return false
        signal();scheduleNext();kick(c);return saved.pending
    }
    fun cancel(book:Book):Boolean {val ok=intents.cancel(book.sourceId,book.id,clock());if(ok){signal();client?.let(::kick);scheduleNext()};return ok}
    fun cancelLocal(book:Book){intents.cancelLocally(book.sourceId,book.id);signal()}
    fun pending(book:Book)=intents.get(book.sourceId,book.id)?.takeIf{it.pending}
    fun kick(c:BookHubClient) {
        kickRequested=c;if(kick?.isActive==true)return
        kick=scope.launch(start=CoroutineStart.LAZY){do{val next=kickRequested;kickRequested=null;if(next!=null)try{process(next)}catch(e:CancellationException){throw e}catch(_:Exception){/* A durable pending intent remains; no busy retry loop on disk failure. */}}while(kickRequested!=null)}
        kick?.start()
    }
    /** Explicit successful login can resume auth-paused/capped new intents, never legacy shelf metadata. */
    suspend fun onLogin(c:BookHubClient){process(c)}
    private fun valid(c:BookHubClient,g:Long,r:ShelfIntent):Boolean = client===c && generation==g && c.snapshot()?.let{
        ServiceOrigin(it.origin).value==r.origin && it.accountId==r.account && BookHubSource.ownerId(c)==r.owner
    }==true && intents.current(r)
    private fun persist(r:ShelfIntent,change:Boolean=false){if(!intents.save(r,change))throw java.io.IOException("intent persistence failed");signal()}
    suspend fun process(c:BookHubClient)=mutex.withLock {
        finishCommitted()
        val g=generation;val snapshot=c.snapshot()?:return@withLock
        if(client!==c)return@withLock
        val owner=BookHubSource.ownerId(c)
        if(!downloads.connection.online){scheduleNext();return@withLock}
        for(batch in 0 until 32) {
            if(client!==c || generation!=g || c.snapshot()==null)break
            val r=intents.all().firstOrNull{it.owner==owner && it.pending && it.state in setOf("PENDING","RUNNING","VERIFY") && it.due<=clock()}?:break
            processOne(c,g,r)
        }
        scheduleNext()
    }
    private suspend fun processOne(c:BookHubClient,g:Long,start:ShelfIntent) {
        var r=start
        running.value=running.value+r.book.key
        try {
            repeat(2) {
                if(!valid(c,g,r))return
                if(r.attempts>=6){persist(r.copy(state="CAPPED",due=clock()+ShelfIntents.DAY));return}
                r=r.copy(state="RUNNING",attempts=r.attempts+1,due=clock()+BACKOFF)
                persist(r)
                val detail=c.shelfDetail(r.book.id);coroutineContext.ensureActive();if(!valid(c,g,r))return
                if(r.baseline==null){r=r.copy(baseline=detail.present);persist(r)}
                if(detail.present!=r.present) {
                    r=r.copy(sent=true);persist(r)
                    try {c.changeShelf(r.book.id,r.present,detail.revision)}catch(e:SourceProblem){if(e.kind==FailureKind.VERSION && it==0)return@repeat;throw e}
                    coroutineContext.ensureActive();if(!valid(c,g,r))return
                }
                if(r.present) {
                    val latest=c.shelfDetail(r.book.id,verifyAccount=false);coroutineContext.ensureActive();if(!valid(c,g,r))return
                    if(!latest.present)throw SourceProblem(FailureKind.VERSION,"shelf changed")
                    latest.book.readAllowed?.let{ReadingStore(context,r.owner).confirmReadPermission(r.book.id,it)}
                    if(!ReadingStore(context,r.owner).addToShelf(latest.book.copy(sourceId=r.owner),remoteConfirmed=true))throw java.io.IOException()
                    r=r.copy(book=latest.book.copy(sourceId=r.owner));persist(r,true)
                    if(!r.keepLocal && r.book.format in listOf("TXT","EPUB") && downloads.automaticWifi())withContext(Dispatchers.IO) {
                        downloads.enqueueIfNeeded(r.owner,r.book,automatic=true,stillWanted={valid(c,g,r) && !ReadingStore(context,r.owner).cleanupPending(r.book.id) && ReadingStore(context,r.owner).peekShelf().any{it.id==r.book.id}})
                    }
                    coroutineContext.ensureActive();if(!valid(c,g,r))return
                } else if(r.clearOnRemove) {
                    r=r.copy(state="APPLYING");persist(r,true)
                    completeCleanup(r);return
                } else {
                    val st=ReadingStore(context,r.owner)
                    if(r.keepLocal)st.addToShelf(r.book,localOverride=true) else st.recordRemoteAbsent(r.book.id)
                    persist(r,true)
                }
                if(r.watchUntil>clock() && r.checks<4)persist(r.copy(state="VERIFY",due=minOf(r.watchUntil,clock()+BACKOFF*(r.checks+1)),checks=r.checks+1))
                else persist(r.copy(state="DONE"),true)
                applied.tryEmit(ShelfApplied(r));return
            }
            if(intents.current(r))persist(r.copy(state="PENDING",due=clock()+BACKOFF))
        }catch(e:Exception) {
            if(intents.current(r)) {
                val auth=e is SourceProblem && e.kind in listOf(FailureKind.AUTH,FailureKind.FORBIDDEN)
                val state=if(r.state=="APPLYING")"APPLYING" else if(auth)"WAIT_AUTH" else if(r.attempts>=6)"CAPPED" else "PENDING"
                val delay=BACKOFF*(1L shl (r.attempts-1).coerceIn(0,6))
                persist(r.copy(state=state,due=clock()+if(state=="CAPPED")ShelfIntents.DAY else minOf(delay,ShelfIntents.DAY),watchUntil=if(r.sent)maxOf(r.watchUntil,clock()+ShelfIntents.DAY)else r.watchUntil))
            }
            if(e is CancellationException)throw e
        }finally{running.value=running.value-start.book.key}
    }
    private suspend fun completeCleanup(r:ShelfIntent) {
        if(!intents.current(r))return
        val st=ReadingStore(context,r.owner)
        if(st.membershipCleanupNonce(r.book.id)!=r.nonce) {
            if(!st.requestCleanup(r.book.id,remoteHide=true,cleanupId=r.nonce))throw java.io.IOException()
        }
        val token=st.cleanupToken(r.book.id)
        if(token==r.nonce)withContext(Dispatchers.IO) {
            downloads.eraseAndCancel(r.owner,r.book.id,token)
            CoverThumbnailStore(File(context.noBackupFilesDir,"cover-thumbs-v1")).clear(r.owner,r.book.id)
            if(!downloads.finishErase(r.owner,r.book.id,token))throw java.io.IOException()
        }
        if(st.cleanupPending(r.book.id))throw java.io.IOException()
        if(intents.current(r)){persist(r.copy(state="DONE"),true);applied.tryEmit(ShelfApplied(r))}
    }
    private suspend fun finishCommitted(){for(r in intents.all().filter{it.state=="APPLYING" && it.due<=clock()}.take(32))try{completeCleanup(r)}catch(e:CancellationException){throw e}catch(_:Exception){/* Durable local cleanup resumes later, with the same token. */}}
    /** Entry used only by Android's constrained job. No password or speculative login protocol exists here. */
    suspend fun runScheduled(daily:Boolean) {
        val captured=generation
        val saved=withContext(Dispatchers.IO){vault.load()}
        if(captured!=generation)return
        val ready=(saved as? SessionLoad.Ready)?.session?:run{mutex.withLock{finishCommitted()};return}
        var c=client
        if(c==null) {
            c=gate.restore(ready);activate(c,false)
            val current=c;val g=generation
            c.onSessionChanged={s->if(client===current && generation==g){if(s==null){vault.clear();downloads.deactivate();context.getSharedPreferences("offline-retention-note-v1",Context.MODE_PRIVATE).edit().putBoolean("show",true).putString("reason","EXPIRED").commit()}else vault.save(s);signal()}}
        } else if(c.snapshot()?.let{it.accountId==ready.accountId && ServiceOrigin(it.origin).value==ServiceOrigin(ready.origin).value}!=true)return
        if(daily)intents.all().filter{it.owner==BookHubSource.ownerId(c) && it.state=="CAPPED" && it.due<=clock()}.forEach{persist(it.copy(state="PENDING",attempts=0,due=clock()))}
        val hadPending=intents.all().any{it.owner==BookHubSource.ownerId(c) && it.pending}
        process(c)
        if(daily && !hadPending && downloads.connection.online && client===c && c.snapshot()!=null) {
            try{mutex.withLock{if(client===c && c.snapshot()!=null)c.verifyShelfAccount()}}catch(e:CancellationException){throw e}catch(_:Exception){/* A bounded lightweight check; genuine auth expiry updates the vault through the official client. */}
        }
    }
    private fun scheduler()=if(scheduleJobs)context.getSystemService(JobScheduler::class.java)else null
    fun dailyJobInfo()=JobInfo.Builder(DAILY,ComponentName(context,ShelfSyncService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .setPeriodic(ShelfIntents.DAY,6*60*60*1000L).setPersisted(true).build()
    fun scheduleDaily(){val s=scheduler()?:return;if(s.getPendingJob(DAILY)==null)s.schedule(dailyJobInfo())}
    private fun scheduleNext() {
        val s=scheduler()?:return;val c=client?:return;val owner=runCatching{BookHubSource.ownerId(c)}.getOrNull()?:return
        val due=intents.all().filter{it.owner==owner && it.state in setOf("PENDING","RUNNING","VERIFY","APPLYING")}.minOfOrNull{it.due}
        if(due==null){s.cancel(RETRY);return}
        s.schedule(JobInfo.Builder(RETRY,ComponentName(context,ShelfSyncService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency((due-clock()).coerceAtLeast(0)).setBackoffCriteria(BACKOFF,JobInfo.BACKOFF_POLICY_EXPONENTIAL).setPersisted(true).build())
    }
    companion object {
        const val DAILY=1700000001;const val RETRY=1700000002;const val BACKOFF=15*60*1000L
        private val instances=ConcurrentHashMap<String,ShelfSyncRuntime>()
        fun get(context:Context)=instances.getOrPut(context.applicationInfo.dataDir){ShelfSyncRuntime(context.applicationContext)}
    }
}
