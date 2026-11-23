package app.leafreader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.leafreader.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import app.leafreader.offline.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import app.leafreader.covers.BookCoverRepository
import java.io.File

data class OfflineManagementSnapshot(val books:List<Book> = emptyList(),val bytesByBook:Map<String,Long> = emptyMap(),val totalBytes:Long=0,val otherBytes:Long=0,val available:Boolean=false)
object OfflineRetention {
    const val NOTE="已有的本机离线副本仍保留。用原账号重新登录后，完整且版本匹配的副本无需重下；完整性以本机核验为准。"
}
data class ReaderState(
    val books: List<Book> = emptyList(), val book: Book? = null,
    val chapters: List<ChapterRef> = emptyList(), val chapter: Chapter? = null,
    val offset: Int = 0, val revision: Int = 0, val settings: ReaderSettings = ReaderSettings(),
    val findError:String?=null,val loading: Boolean = false, val error: String? = null, val notice: String? = null,
    val shelfRevision: Int = 0, val query: String = "", val nextCursor: String? = null, val hasMore: Boolean = false,
    val remote: Boolean = false, val sourceLabel: String = "BookHub · 未登录", val connecting: Boolean = false,
    val connectionError: String? = null, val connectionRevision: Int = 0,
    val topTab: String = "shelf", val shelfBooks: List<Book> = emptyList(), val shelfLoading: Boolean = true,
    val shelfError: String? = null, val shelfHasMore: Boolean = false, val findLoading: Boolean = false,
    val chapterLengths: List<Int>? = null, val chaptersComplete: Boolean = false, val graphicId: String? = null,
    val downloadLabels:Map<String,String> = emptyMap(),val automaticWifi:Boolean=true,val offline:Boolean=false,
    val shelfManaging:Boolean=false,val shelfSelection:Set<String> = emptySet(),
    val localShelfWarning:String?=null,val retentionNote:String?=null,val progressOrderReliable:Boolean=true,val downloadRevision:Int=0, val shelfKnownComplete:Boolean=false, val canReturnPage:Boolean=false
)

@android.annotation.SuppressLint("ApplySharedPref") // Complete one-time layout migration before switching accounts.
class ReaderViewModel @JvmOverloads constructor(application: Application,
    private var source: BookSource = SignedOutBookSource(),
    private val connectionGate: BookHubConnectionGate = BookHubConnectionGate(),
    private val sessionVault: SessionVault = EncryptedSessionVault(application), private val offlineRuntime:OfflineDownloads?=null,
    independentSources:List<BookSource> = emptyList(), private val clock: () -> Long = { System.currentTimeMillis() }) : AndroidViewModel(application) {
    private val localSource = source
    private val independent=independentSources.associateBy{it.id}.also{map->require(map.values.all{it.independentOffline && !it.id.startsWith("bookhub-v1-")})}
    private val shelfIndex=LocalShelfIndex(application).apply{remember(localSource.id);independent.keys.forEach(::remember)}
    private var activeReadingSource:BookSource?=null
    private val localStore = ReadingStore(application, localSource.id)
    var store = localStore; private set
    private val layoutStore = ReadingStore(application, "layout-global-v1")
    private val layoutPrefs = application.getSharedPreferences("reader-layout-migration", Application.MODE_PRIVATE)
    private val initialSettings = if (layoutPrefs.getBoolean("done", false)) layoutStore.settings() else store.settings().also {
        layoutStore.saveSettings(it); layoutPrefs.edit().putBoolean("done", true).commit()
    }
    private val retentionPrefs=application.getSharedPreferences("offline-retention-note-v1",Application.MODE_PRIVATE)
    @Volatile private var sessionAvailable = true
    private val state = MutableStateFlow(ReaderState(settings = initialSettings, shelfBooks = unifiedShelf(),localShelfWarning=shelfIndex.warning,retentionNote=if(retentionPrefs.getBoolean("show",false))OfflineRetention.NOTE else null,automaticWifi=offlineRuntime?.automaticWifi()?:true,offline=offlineRuntime?.connection?.online==false))
    val ui = state.asStateFlow()
    private var pending: Job? = null
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private var findRetry: () -> Unit = {}
    private var shelfJob: Job? = null
    private var shelfFailure: Exception? = null
    private var connection: Job? = null
    private var client: BookHubClient? = null
    @Volatile private var sessionGeneration = 0L
    private val cursors = mutableSetOf<String>()
    private val shelfCursors = mutableSetOf<String>()
    private var serviceShelf = store.savedServiceShelf()
    private var freshServiceShelf = emptyList<Book>()
    private var shelfCursor: String? = null
    private val serverPrefs = application.getSharedPreferences("own-server", Application.MODE_PRIVATE)
    private val covers by lazy { BookCoverRepository(File(application.noBackupFilesDir,"cover-thumbs-v1"),viewModelScope) }
    private var retry: () -> Unit = { loadShelf() }

    private val identityPrefs=application.getSharedPreferences("bookhub-storage-identities-v1",Application.MODE_PRIVATE)
    init {
        if (!restoreSavedSession()) loadShelf()
        if(offlineRuntime!=null)viewModelScope.launch {
            OfflineEvents.revision.collectLatest {
                delay(100)
                // Per-book integrity is checked only when its actions are opened, never across the shelf.
                state.update{it.copy(automaticWifi=offlineRuntime.automaticWifi(),offline=!offlineRuntime.connection.online,downloadRevision=it.downloadRevision+1)}
            }
        }
    }
    private fun retainOfflineNote() {
        retentionPrefs.edit().putBoolean("show",true).commit()
        state.update{it.copy(retentionNote=OfflineRetention.NOTE)}
    }
    private fun identityKey(c:BookHubClient)=digest(c.origin.value+"|"+c.accountId)
    private fun attachClient(next: BookHubClient, tab: String, persist: Boolean) {
        if(!identityPrefs.contains(identityKey(next)))identityPrefs.edit().putString(identityKey(next),next.storageOrigin).commit()
        retentionPrefs.edit().putBoolean("show",false).commit()
        client = next; source = offlineRuntime?.source(next)?:BookHubSource(next); store = ReadingStore(getApplication(), source.id); sessionAvailable = true
        shelfIndex.remember(source.id)
        serviceShelf = store.savedServiceShelf(); freshServiceShelf = emptyList(); shelfCursor = null
        val generation = sessionGeneration
        next.onSessionChanged = { snapshot ->
            if (client === next && generation == sessionGeneration) {
                if (snapshot == null) {
                    retainOfflineNote();offlineRuntime?.deactivate()
                    sessionAvailable = false; sessionVault.clear()
                    state.update { s->val affected=s.book?.sourceId==source.id
                        s.copy(loading=if(affected)false else s.loading,shelfLoading=false,findLoading=false,
                            canReturnPage=if(affected)false else s.canReturnPage,
                            error=if(affected)"本书的 BookHub 会话已失效。该来源未确认无会话离线许可，请恢复原账号授权；副本和进度保留。\n诊断：BH_AUTH" else s.error,
                            notice=if(affected)"BookHub 登录已失效，请重新登录。\n"+OfflineRetention.NOTE else s.notice,
                            sourceLabel="BookHub · 会话需重新登录") }
                } else if (!sessionVault.save(snapshot)) {
                    retainOfflineNote()
                    state.update { it.copy(notice = "设备无法安全保存登录；当前会话仍可用，重启后需要重新登录。密码不会保存。\n"+OfflineRetention.NOTE) }
                }
            }
        }
        offlineRuntime?.activate(next)
        serverPrefs.edit().putString("origin", next.origin.value).apply()
        state.value = ReaderState(settings = state.value.settings, remote = true, sourceLabel = "BookHub · ${next.origin.uri.host}",
            topTab = tab, shelfBooks = combinedShelf(),localShelfWarning=shelfIndex.warning, connectionRevision = state.value.connectionRevision + 1,automaticWifi=offlineRuntime?.automaticWifi()?:true,offline=offlineRuntime?.connection?.online==false)
        if (persist) next.snapshot()?.let { next.onSessionChanged?.invoke(it) }
        if(persist || combinedShelf().isEmpty())loadShelf() else state.update{it.copy(shelfLoading=false)}
    }
    private fun restoreSavedSession(): Boolean = when (val saved = sessionVault.load()) {
        is SessionLoad.Ready -> try { attachClient(connectionGate.restore(saved.session), "shelf", false); true }
            catch (_: Exception) { retainOfflineNote();sessionVault.clear(); state.update { it.copy(notice = "保存的登录无法恢复，请重新登录；本机书架和进度已保留。\n"+OfflineRetention.NOTE) }; false }
        SessionLoad.Unavailable -> { retainOfflineNote();state.update { it.copy(notice = "设备无法解密保存的登录，请重新登录；本机书架和进度已保留。\n"+OfflineRetention.NOTE) }; false }
        SessionLoad.Empty -> false
    }
    private fun tagged(book: Book, sourceId: String = source.id) = book.copy(sourceId = book.sourceId.ifBlank { sourceId })
    private fun storeFor(book: Book): ReadingStore = if (book.sourceId == localSource.id) localStore else if (book.sourceId.isBlank() || book.sourceId == source.id) store else ReadingStore(getApplication(), book.sourceId)
    private fun sourceFor(book: Book): BookSource {
        independent[book.sourceId]?.let{return it}
        if (book.sourceId == localSource.id) return localSource
        if ((book.sourceId.isBlank() || book.sourceId == source.id) && (source.id == localSource.id || sessionAvailable)) {
            val permission=(source as? BookHubSource)?.observedReadPermission(book.id)?:storeFor(book).knownReadPermission(book.id)?:book.readAllowed
            if(permission==false)throw SourceProblem(FailureKind.FORBIDDEN,"本书读取授权已被明确拒绝。完整副本不等于许可；可主动刷新目录重新核实授权，原进度保留。")
            return source
        }
        throw SourceProblem(FailureKind.AUTH, "本书需原服务及原账号的授权。BookHub 未确认无会话离线许可；本机书架、副本和进度保留，不会请求当前其他来源")
    }
    private fun readingSourceFor(book:Book):BookSource {
        val authorized=sourceFor(book)
        return if(state.value.book?.key==book.key)activeReadingSource?:authorized else authorized
    }
    fun positionOf(book: Book) = storeFor(book).position(book.id)
    suspend fun shelfCover(book:Book):android.graphics.Bitmap? {
        if(book.format!="EPUB")return null
        val current=source as? BookHubSource
        if(current==null || book.sourceId!=current.id || !sessionAvailable) {
            if(!canManageOffline(book))return null
            return try{withContext(Dispatchers.IO){covers.localThumbnail(book.sourceId,book.id,offlineRuntime!!.cache(book.sourceId))}}catch(e:CancellationException){throw e}catch(_:Exception){null}
        }
        val generation=sessionGeneration
        return try { withContext(Dispatchers.IO){val visual=current.completeLocalReader(book.id,positionOf(book)?.chapterId)?:current;covers.load(visual,book.id,"$generation:${state.value.shelfRevision}"){source===current && sessionGeneration==generation && sessionAvailable}} }
        catch(e:CancellationException){throw e}catch(_:Exception){null}
    }
    fun isLocalBook(book: Book) = book.sourceId == localSource.id || (book.sourceId.isBlank() && source.id == localSource.id)
    suspend fun chapterList(book: Book, refresh:Boolean=false):List<ChapterRef> {
        val s=state.value
        if(!refresh && s.book?.key==book.key && s.chapters.isNotEmpty())return s.chapters
        if(refresh && source is BookHubSource && book.sourceId==source.id && sessionAvailable) {
            try {
                val refs=(source as BookHubSource).freshChapters(book.id)
                storeFor(book).confirmReadPermission(book.id,true);refreshShelfOrder();return refs
            } catch(e:Exception) {
                if((source as BookHubSource).observedReadPermission(book.id)==false)applyReadDenial(listOf(book.copy(readAllowed=false)),source.id)
                throw e
            }
        }
        return readingSourceFor(book).chapters(book.id)
    }
    fun canAddBook(book:Book):Boolean = isOnShelf(book) || source !is BookHubSource || state.value.shelfKnownComplete || book.id in storeFor(book).hiddenShelfIds()

    suspend fun chapterList(bookId: String) = chapterList(Book(bookId, "", "", "", "", 0, 0))
    private fun unifiedShelf():List<Book> = shelfIndex.books().sortedWith(compareByDescending<Book>{storeFor(it).position(it.id)!=null || storeFor(it).enteredReadOnly(it.id)>0}
        .thenByDescending{storeFor(it).enteredReadOnly(it.id)}.thenBy{storeFor(it).shelfOrder(it.id)}.thenBy{it.key})
    private fun combinedShelf():List<Book> = unifiedShelf()
    private fun applyReadDenial(items:List<Book>,owner:String) {
        for(book in items.filter{it.readAllowed!=null})storeFor(book.copy(sourceId=owner)).confirmReadPermission(book.id,book.readAllowed!!)
        val active=state.value.book
        if(active?.sourceId==owner && items.any{it.id==active.id && it.readAllowed==false})state.update{it.copy(loading=false,error="本书读取授权已被明确拒绝；副本和进度保留。可主动刷新目录核实权限。\n诊断：BH_FORBIDDEN",canReturnPage=false)}
    }
    private fun requireSession() { if (source.id != localSource.id && !sessionAvailable) throw SourceProblem(FailureKind.AUTH, "请重新登录 BookHub；本机书架和进度已保留") }
    fun selectTab(tab: String) { if(tab in listOf("shelf","find","settings"))state.update{it.copy(topTab=tab,shelfManaging=it.shelfManaging && tab==it.topTab,shelfSelection=if(tab==it.topTab)it.shelfSelection else emptySet())} }
    fun manageShelf() {state.update{it.copy(shelfManaging=true,shelfSelection=emptySet())}}
    fun endShelfManagement() {state.update{it.copy(shelfManaging=false,shelfSelection=emptySet())}}
    fun toggleShelfSelection(key:String) {state.update{if(!it.shelfManaging || it.shelfBooks.none{b->b.key==key})it else it.copy(shelfSelection=if(key in it.shelfSelection)it.shelfSelection-key else it.shelfSelection+key)}}
    fun selectAllShelf() {state.update {val keys=it.shelfBooks.map{b->b.key}.toSet();if(!it.shelfManaging)it else it.copy(shelfSelection=if(it.shelfSelection.intersect(keys)==keys)emptySet() else keys)}}
    fun isOnShelf(bookId: String): Boolean = isOnShelf(Book(bookId,"","","","",0,0,sourceId=source.id))
    fun isOnShelf(book: Book): Boolean = state.value.shelfBooks.any { it.key == tagged(book).key }
    fun addToShelf(book: Book): Boolean {
        val owned = tagged(book)
        if(!canAddBook(owned))return false
        if(owned.sourceId!=localSource.id && (owned.sourceId!=source.id || !sessionAvailable)) return false
        val added=!isOnShelf(owned)
        if(!storeFor(owned).addToShelf(owned)){state.update{it.copy(notice="加入书架未能完整保存，请重试；原记录保留。")};return false};
        if(owned.sourceId==source.id && serviceShelf.any{it.id==owned.id}) {serviceShelf=serviceShelf.map{if(it.id==owned.id)owned else it};store.rememberServiceShelf(serviceShelf)}
        refreshShelfOrder()
        if(added && downloadsAvailable(owned) && offlineRuntime?.automaticWifi()==true) {
            val current=source;val generation=sessionGeneration
            viewModelScope.launch(Dispatchers.IO) {try {
                if(source===current && generation==sessionGeneration)offlineRuntime.enqueueIfNeeded(current.id,owned,automatic=true)
            }catch(_:Exception){if(source===current && generation==sessionGeneration)state.update{it.copy(notice="书架已保存，自动下载未能排队；可手动重试。")}}}
        }
        return added
    }
    fun removeFromShelf(keys:Set<String>,expectedConnectionRevision:Int) {
        if(expectedConnectionRevision!=state.value.connectionRevision)return
        val targets=state.value.shelfBooks.filter{it.key in keys}.distinctBy{it.key}
        var failed=0
        for(group in targets.groupBy{it.sourceId}.values) {
            try {if(!storeFor(group.first()).hideFromShelf(group.map{it.id}.toSet()))failed+=group.size}
            catch(_:Exception){failed+=group.size}
        }
        refreshShelfOrder()
        val visible=state.value.shelfBooks.map{it.key}.toSet()
        state.update{it.copy(shelfSelection=it.shelfSelection.intersect(visible),notice=if(failed>0)"有 $failed 本的本机隐藏状态未能完整保存，请重试；进度和下载保留。" else null)}
    }
    suspend fun inspectDownload(book:Book):OfflineStatus {
        val runtime=offlineRuntime?:return OfflineStatus()
        if(!canManageOffline(book))return OfflineStatus()
        return withContext(Dispatchers.IO){runtime.cache(book.sourceId).status(book.id,verify=true)}
    }
    fun downloadTaskLabel(book:Book):String? {
        if(!canManageOffline(book))return null
        val r=offlineRuntime?.request(offlineRuntime.key(book.sourceId,book.id))?:return null
        return when(r.state){"QUEUED"->"已排队 · ${r.message}";"WAITING"->"等待下载 · ${r.message}";"RUNNING"->"下载中 · ${r.message}";"PAUSED"->"已暂停 · ${r.message}";"FAILED"->"下载失败 · ${r.message}";"CANCELLED"->"已取消";else->null}
    }
    fun canManageOffline(book:Book)=offlineRuntime!=null && Regex("bookhub-v1-[a-f0-9]{32}").matches(book.sourceId) && book.sourceId in shelfIndex.sources()
    fun downloadsAvailable(book:Book)=offlineRuntime!=null && source is BookHubSource && sessionAvailable && book.format in listOf("TXT","EPUB") && (book.sourceId.isBlank() || book.sourceId==source.id)
    fun downloadLabel(book:Book)=state.value.downloadLabels[tagged(book).key]?:"未下载"
    fun downloadBusy(book:Book)=canManageOffline(book) && offlineRuntime?.busy(book.sourceId,book.id)==true
    fun download(book:Book,allowMetered:Boolean=false) {
        val accepted=try{downloadsAvailable(book) && offlineRuntime?.enqueue(source.id,tagged(book),allowMetered)==true}catch(_:Exception){false}
        if(!accepted)
            state.update{it.copy(notice="请登录这本书所属的 BookHub 账号后下载。")}
        else state.update{it.copy(downloadRevision=it.downloadRevision+1)}
    }
    fun cancelDownload(book:Book) {
        if(!canManageOffline(book))return
        val owner=book.sourceId
        viewModelScope.launch(Dispatchers.IO){try{offlineRuntime?.cancel(owner,book.id);state.update{it.copy(downloadRevision=it.downloadRevision+1)}}catch(_:Exception){state.update{it.copy(notice="取消任务未能完整保存，请重试；阅读进度保留。")}}}
    }
    private fun eraseDownloaded(book:Book) {
        val runtime=offlineRuntime?:return
        runtime.cancel(book.sourceId,book.id)
        val current=(source as? BookHubSource)?.takeIf{it.id==book.sourceId}
        if(current!=null)current.forgetDownloaded(book.id) else runtime.cache(book.sourceId).clear(book.id)
        covers.clear(book.sourceId,book.id);OfflineEvents.changed()
        state.update{it.copy(downloadRevision=it.downloadRevision+1)}
    }
    fun clearDownload(book:Book) {
        if(!canManageOffline(book))return
        viewModelScope.launch(Dispatchers.IO){try{eraseDownloaded(book)}catch(_:Exception){state.update{it.copy(notice="清除副本未完成，请重试；书架、进度和登录已保留。")}}}
    }
    fun offlineSourceEnabled()=offlineRuntime!=null && source is BookHubSource && sessionAvailable
    private fun hiddenClearAllowed(book:Book,revision:Int)=offlineSourceEnabled() && revision==state.value.connectionRevision && book.sourceId==source.id && book.id in store.hiddenShelfIds()
    fun clearHiddenDownload(book:Book,expectedConnectionRevision:Int) {
        if(!hiddenClearAllowed(book,expectedConnectionRevision))return
        val generation=sessionGeneration
        viewModelScope.launch(Dispatchers.IO) {
            if(generation!=sessionGeneration || !hiddenClearAllowed(book,expectedConnectionRevision))return@launch
            try{eraseDownloaded(book)}catch(_:Exception){state.update{it.copy(notice="清理未完成，请重试；书架隐藏状态、进度和登录保留。")}}
        }
    }
    fun automaticWifi(value:Boolean){offlineRuntime?.setAutomaticWifi(value);state.update{it.copy(automaticWifi=value)}}
    private fun refreshShelfOrder() {
        val shelf=combinedShelf();val keys=shelf.map{it.key}.toSet()
        state.update { it.copy(shelfBooks=shelf,localShelfWarning=shelfIndex.warning,books=it.books.filterNot{b->b.key in keys},shelfRevision=it.shelfRevision+1) }
    }
    /** Resolve exact current shelf keys, never titles/bare IDs. Queue identity and policy stay in OfflineDownloads. */
    fun downloadSelection(keys: Set<String>, expectedConnectionRevision: Int, allowMetered: Boolean = false) {
        val current=source as? BookHubSource?:return
        val runtime=offlineRuntime?:return
        val generation=sessionGeneration
        if(expectedConnectionRevision!=state.value.connectionRevision || !sessionAvailable)return
        val targets=state.value.shelfBooks.filter{it.key in keys && downloadsAvailable(it)}.distinctBy{it.key}
        viewModelScope.launch(Dispatchers.IO) {
            var added=0;var existing=0;var failed=0
            for(book in targets) {
                if(generation!=sessionGeneration || source!==current || !sessionAvailable)return@launch
                try {
                    when(runtime.enqueueIfNeeded(current.id,book,allowMetered)) {
                        BatchEnqueueResult.ADDED->added++
                        BatchEnqueueResult.PRESENT->existing++
                        BatchEnqueueResult.REJECTED->failed++
                    }
                } catch(_:Exception) {failed++}
            }
            if(generation==sessionGeneration && source===current && sessionAvailable)state.update{it.copy(downloadRevision=it.downloadRevision+1,
                notice="已加入下载队列 $added 本；已有下载或任务 $existing 本。"+
                    if(failed>0)"另有 $failed 本未能排队，可重试。" else if(targets.size<keys.size)"其余选中项不属于当前可下载账号，已跳过。" else "")}
        }
    }

    /** Settings summary reads file lengths on IO; no manifest parsing or resource SHA validation. */
    suspend fun offlineUsage():OfflineManagementSnapshot = withContext(Dispatchers.IO) {
        val current=source as? BookHubSource?:return@withContext OfflineManagementSnapshot()
        val runtime=offlineRuntime?:return@withContext OfflineManagementSnapshot()
        if(!sessionAvailable)return@withContext OfflineManagementSnapshot()
        val generation=sessionGeneration
        val bytes=runtime.cache(current.id).totalDiskBytes()
        if(source!==current || generation!=sessionGeneration || !sessionAvailable)throw CancellationException()
        OfflineManagementSnapshot(totalBytes=bytes,available=true)
    }
    suspend fun hiddenCopiesSnapshot():OfflineManagementSnapshot=withContext(Dispatchers.IO) {
        val current=source as? BookHubSource?:return@withContext OfflineManagementSnapshot()
        val runtime=offlineRuntime?:return@withContext OfflineManagementSnapshot()
        if(!sessionAvailable)return@withContext OfflineManagementSnapshot()
        val generation=sessionGeneration;val activeStore=store;val hidden=activeStore.hiddenShelfIds()
        if(hidden.isEmpty())return@withContext OfflineManagementSnapshot(available=true)
        val requests=runtime.requestsForOwner(current.id).filter{it.bookId in hidden && it.state in listOf("QUEUED","WAITING","RUNNING","PAUSED","FAILED")}.associateBy{it.bookId}
        val usage=runtime.cache(current.id).diskUsageForBooks(hidden)
        val known=(activeStore.savedServiceShelf()+activeStore.localShelf()+listOfNotNull(activeStore.recentBook())).filter{it.id in hidden}.distinctBy{it.id}
        val knownIds=known.map{it.id}.toSet()
        val extras=(requests.keys+usage.payloadBooks).filter{it !in knownIds}.sorted().map{id->Book(id,requests[id]?.title?:"已移出的本机副本（书名缺失）","","","",0,0,sourceId=current.id)}
        val books=(known+extras).filter{it.id in usage.payloadBooks || it.id in requests}
        if(source!==current || generation!=sessionGeneration || !sessionAvailable)throw CancellationException()
        OfflineManagementSnapshot(books,books.associate{it.key to (usage.bytesByBook[it.id]?:0L)},usage.totalBytes,available=true)
    }
    suspend fun managementBooks():List<Book> = managementSnapshot().books
    /** Metadata inventory only: the cleanup list never pre-validates every book's content hashes. */
    suspend fun managementSnapshot():OfflineManagementSnapshot = withContext(Dispatchers.IO) {
        val current=source as? BookHubSource?:return@withContext OfflineManagementSnapshot()
        val runtime=offlineRuntime?:return@withContext OfflineManagementSnapshot()
        if(!sessionAvailable)return@withContext OfflineManagementSnapshot()
        val generation=sessionGeneration
        val requests=runtime.requestsForOwner(current.id).filter{it.state in listOf("QUEUED","WAITING","RUNNING","PAUSED","FAILED")}
        val requestById=requests.associateBy{it.bookId}
        val known=(store.savedServiceShelf()+store.localShelf()+listOfNotNull(store.recentBook())).distinctBy{it.id}
        val knownIds=known.map{it.id}.toSet()
        val usage=runtime.cache(current.id).diskUsage(knownIds+requestById.keys)
        val discovered=usage.formats.filterKeys{it !in knownIds}.map{(id,format)->Book(id,"未入架的阅读缓存","","","",0,0,format,current.id)}
        val extras=requests.filter{it.bookId !in knownIds}.map{r->Book(r.bookId,r.title,"","","",0,0,usage.formats[r.bookId]?:"EPUB",current.id)}
        val books=(known+extras+discovered).distinctBy{it.id}.filter{b->b.id in requestById || b.id in usage.payloadBooks}
        if(source!==current || generation!=sessionGeneration || !sessionAvailable)throw CancellationException()
        val sizes=books.associate{it.key to (usage.bytesByBook[it.id]?:0L)}
        OfflineManagementSnapshot(books,sizes,usage.totalBytes,(usage.totalBytes-sizes.values.sum()).coerceAtLeast(0),true)
    }
    fun ownOrigin(): String = serverPrefs.getString("origin", "") ?: ""
    /** Invalidate credentials without replacing the visible/persisted shelf or current typography. */
    fun clearRemoteForServerSelection() {
        offlineRuntime?.deactivate()
        sessionGeneration++; connection?.cancel(); cancelSourceJobs(); sessionVault.clear(); client?.clearSession(); client = null
        if (source.id != localSource.id) sessionAvailable = false
        state.update { it.copy(connecting=false,connectionError=null,loading=false,shelfLoading=false,findLoading=false,shelfManaging=false,shelfSelection=emptySet()) }
    }
    fun connect(origin: String, username: String, password: String) {
        val destination=if(state.value.topTab=="settings") "settings" else "find"
        connection?.cancel()
        state.update { it.copy(connecting = true, connectionError = null) }
        connection = viewModelScope.launch {
            try {
                val next = connectionGate.login(origin, username, password)
                coroutineContext.ensureActive()
                val old=client?.snapshot()
                val legacy=old?.takeIf{it.accountId==next.accountId && ServiceOrigin(it.origin).value==next.origin.value}?.storageOrigin
                    ?: identityPrefs.getString(identityKey(next),null)?.takeIf{runCatching{ServiceOrigin(it).value==next.origin.value}.getOrDefault(false)}
                    ?: ownOrigin().takeIf{it.isNotBlank() && runCatching{ServiceOrigin(it).value==next.origin.value}.getOrDefault(false)}
                if(legacy!=null)next.retainStorageOrigin(legacy)
                // Commit the new session only after login succeeded. Immutable clients keep each origin isolated.
                offlineRuntime?.deactivate();sessionGeneration++;client?.onSessionChanged=null;client?.clearSession()
                cancelSourceJobs(); attachClient(next, destination, true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.update { it.copy(connecting = false, connectionError = safeError(e)) }
            }
        }
    }
    private fun cancelSourceJobs() { activeReadingSource=null;pending?.cancel(); clearSearch(); shelfJob?.cancel() }
    fun cancelConnection() { connection?.cancel(); state.update { it.copy(connecting = false, connectionError = null) } }
    fun signOut() {
        retainOfflineNote()
        val destination=if(state.value.topTab=="settings") "settings" else "find"
        offlineRuntime?.deactivate()
        sessionGeneration++; connection?.cancel(); cancelSourceJobs(); sessionVault.clear(); client?.clearSession(); client = null
        source = localSource; store = localStore; sessionAvailable = true
        serviceShelf = store.savedServiceShelf(); freshServiceShelf = emptyList(); shelfCursor = null
        state.value = ReaderState(settings = state.value.settings, topTab = destination,retentionNote=OfflineRetention.NOTE,notice=OfflineRetention.NOTE, shelfBooks = combinedShelf(),localShelfWarning=shelfIndex.warning, connectionRevision = state.value.connectionRevision + 1,automaticWifi=offlineRuntime?.automaticWifi()?:true,offline=offlineRuntime?.connection?.online==false)
        loadShelf()
    }

    /** User refresh is coalesced; existing entries stay visible until and after the result. */
    fun refreshShelf() { if(!state.value.shelfLoading && shelfJob?.isActive!=true) {state.update{it.copy(shelfRevision=it.shelfRevision+1)};loadShelf()} }
    /** Empty query refreshes server/local shelf; nonempty keeps the previous public method compatible as a search. */
    fun loadShelf(query: String = "") {
        if (query.isNotBlank()) { search(query); return }
        if(source is SignedOutBookSource){val books=combinedShelf();state.update{it.copy(shelfBooks=books,localShelfWarning=shelfIndex.warning,shelfLoading=false,shelfError=null,shelfHasMore=false)};return}
        shelfJob?.cancel(); shelfFailure=null; shelfCursors.clear(); shelfCursor = null; freshServiceShelf = emptyList()
        state.update { it.copy(shelfLoading = true, shelfError = null, shelfHasMore = false,shelfKnownComplete=false) }
        shelfJob = viewModelScope.launch {
            try {
                requireSession(); val page = source.browse("")
                coroutineContext.ensureActive()
                applyReadDenial(page.items,source.id)
                freshServiceShelf = page.items.map { tagged(it) }.distinctBy { it.id }
                serviceShelf = (freshServiceShelf + serviceShelf).distinctBy { it.id }
                shelfCursor = page.nextCursor
                coroutineContext.ensureActive(); store.rememberServiceShelf(serviceShelf)
                val shelf=combinedShelf();val keys=shelf.map{it.key}.toSet()
                state.update { it.copy(shelfBooks=shelf,localShelfWarning=shelfIndex.warning,shelfLoading=false,shelfHasMore=page.hasMore,shelfKnownComplete=!page.hasMore && offlineRuntime?.connection?.online!=false,books=it.books.filterNot{b->b.key in keys}) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                shelfFailure=e;state.update { it.copy(shelfLoading = false, shelfError = safeError(e)) }
            }
        }
    }
    fun loadMoreShelf() {
        val s = state.value; val cursor = shelfCursor ?: return
        if (s.shelfLoading || !s.shelfHasMore) return
        shelfFailure=null;state.update { it.copy(shelfLoading = true, shelfError = null) }
        shelfJob = viewModelScope.launch {
            try {
                requireSession(); val page = source.browse("", cursor)
                coroutineContext.ensureActive()
                if (page.nextCursor != null && page.nextCursor in shelfCursors) throw SourceProblem(FailureKind.PROTOCOL, "服务分页出现循环，请刷新书架")
                applyReadDenial(page.items,source.id)
                shelfCursors += cursor
                freshServiceShelf = (freshServiceShelf + page.items.map { tagged(it) }).distinctBy { it.id }
                // Server absence is not a local delete operation. Keep cached books and explicit additions.
                serviceShelf = (freshServiceShelf + serviceShelf).distinctBy { it.id }
                shelfCursor = page.nextCursor
                coroutineContext.ensureActive(); store.rememberServiceShelf(serviceShelf)
                val shelf=combinedShelf();val keys=shelf.map{it.key}.toSet()
                state.update { it.copy(shelfBooks=shelf,localShelfWarning=shelfIndex.warning,shelfLoading=false,shelfHasMore=page.hasMore,shelfKnownComplete=!page.hasMore && offlineRuntime?.connection?.online!=false,books=it.books.filterNot{b->b.key in keys}) }
            } catch (e: Exception) { if (e is CancellationException) throw e; shelfFailure=e;state.update { it.copy(shelfLoading = false, shelfError = safeError(e)) } }
        }
    }
    /** Editing/clearing invalidates pending results; blank input never browses the service shelf. */
    fun clearSearch() {
        searchGeneration++;searchJob?.cancel();cursors.clear();findRetry={}
        state.update{it.copy(query="",books=emptyList(),nextCursor=null,hasMore=false,findLoading=false,findError=null,error=if(it.book==null)null else it.error)}
    }
    private fun searchItems(items:List<Book>,owner:String):List<Book> {
        val shelfKeys=state.value.shelfBooks.map{it.key}.toSet()
        return items.map{tagged(it,owner)}.distinctBy{it.key}.filterNot{it.key in shelfKeys}
    }
    /** An unfetched shelf page is not proof that a catalog book is addable. Read only the official shelf pages. */
    private suspend fun completeShelfForSearch(current:BookSource,request:Long,generation:Long) {
        var pages=0
        if(state.value.shelfError!=null || (!state.value.shelfKnownComplete && !state.value.shelfHasMore && shelfJob?.isActive!=true))loadShelf()
        while(true) {
            shelfJob?.join();coroutineContext.ensureActive()
            if(request!=searchGeneration || generation!=sessionGeneration || source!==current)throw CancellationException()
            state.value.shelfError?.let { throw shelfFailure ?: SourceProblem(FailureKind.NETWORK,"书架同步未完成，暂不能确认可添加的书籍；请重试搜索") }
            if(!state.value.shelfHasMore)return
            if(++pages>500)throw SourceProblem(FailureKind.PROTOCOL,"书架分页过多，请稍后重试搜索")
            loadMoreShelf()
        }
    }
    fun retrySearch()=findRetry.invoke()
    fun search(query: String) {
        clearSearch()
        val term=query.trim();if(term.isEmpty())return
        val request=searchGeneration;val generation=sessionGeneration;val current=source
        state.update { it.copy(findLoading=true,query=term) }
        findRetry={search(term)}
        searchJob=viewModelScope.launch {
            try {
                requireSession()
                val page=current.browse(term);coroutineContext.ensureActive()
                if(request!=searchGeneration || generation!=sessionGeneration || source!==current) { return@launch }
                applyReadDenial(page.items,current.id)
                state.update{it.copy(books=searchItems(page.items,current.id),nextCursor=page.nextCursor,hasMore=page.hasMore,findLoading=false)}
                // Membership discovery continues after rendering; uncertain results remain non-addable.
                try {completeShelfForSearch(current,request,generation)} catch(e:CancellationException){throw e} catch(e:Exception){if(request==searchGeneration && generation==sessionGeneration)state.update{it.copy(findError=safeError(e),error=if(it.book==null)safeError(e) else it.error)}}
            } catch(e:Exception) {
                if(e is CancellationException)throw e
                if(request==searchGeneration && generation==sessionGeneration)state.update{it.copy(findLoading=false,findError=safeError(e),error=if(it.book==null)safeError(e) else it.error)}
            }
        }
    }
    fun loadMore() {
        val s=state.value;val cursor=s.nextCursor?:return
        if(s.query.isBlank() || s.findLoading || !s.hasMore) { return }
        val request=searchGeneration;val generation=sessionGeneration;val current=source
        findRetry={loadMore()};state.update{it.copy(findLoading=true,findError=null,error=if(it.book==null)null else it.error)}
        searchJob=viewModelScope.launch {
            try {
                requireSession()
                val page=current.browse(s.query,cursor)
                coroutineContext.ensureActive()
                if(request!=searchGeneration || generation!=sessionGeneration || source!==current) { return@launch }
                if(page.nextCursor!=null && (page.nextCursor==cursor || page.nextCursor in cursors))throw SourceProblem(FailureKind.PROTOCOL,"服务分页出现循环，请重新搜索")
                applyReadDenial(page.items,current.id)
                cursors+=cursor
                state.update{it.copy(books=searchItems(it.books+page.items,current.id),nextCursor=page.nextCursor,hasMore=page.hasMore,findLoading=false)}
                // Membership discovery continues after rendering; uncertain results remain non-addable.
                try {completeShelfForSearch(current,request,generation)} catch(e:CancellationException){throw e} catch(e:Exception){if(request==searchGeneration && generation==sessionGeneration)state.update{it.copy(findError=safeError(e),error=if(it.book==null)safeError(e) else it.error)}}
            } catch(e:Exception) {
                if(e is CancellationException)throw e
                if(request==searchGeneration && generation==sessionGeneration)state.update{it.copy(findLoading=false,findError=safeError(e),error=if(it.book==null)safeError(e) else it.error)}
            }
        }
    }

    /** All Read/Continue actions use this route; a saved invalid locator is an error, never a silent first chapter. */
    fun open(book: Book, chapterId: String? = null, fromStart:Boolean=false) {
        pending?.cancel()
        val readingStore = storeFor(book)
        val previous = readingStore.position(book.id)
        if((fromStart || state.value.error?.contains("BH_VERSION")==true) && previous!=null)readingStore.preserveRecovery(book.id)
        val saved = if(fromStart)null else previous
        val cached=state.value.takeIf{it.book?.key==book.key && chapterId!=null && it.chapters.any{c->c.id==chapterId}}?.chapters
        state.update { it.copy(shelfManaging=false,shelfSelection=emptySet(),book = book, chapter = null, chapters = emptyList(), chaptersComplete = false, chapterLengths = null, loading = true, error = null, notice = null,canReturnPage=false) }
        retry = { open(book, chapterId,fromStart) }
        pending = viewModelScope.launch {
            try {
                val authorized=sourceFor(book)
                val readingSource=if(authorized is BookHubSource)authorized.completeLocalReader(book.id,chapterId?:saved?.chapterId)?:authorized else authorized
                activeReadingSource=readingSource
                val refs = cached ?: readingSource.chaptersFor(book.id,chapterId?:saved?.chapterId)
                coroutineContext.ensureActive()
                if (refs.isEmpty()) throw SourceProblem(FailureKind.EMPTY, "这本书暂时没有可读章节，请稍后重试")
                state.update { it.copy(chapters = refs) }
                val target = chapterId ?: saved?.chapterId
                if (target != null && refs.none { it.id == target }) throw SourceProblem(FailureKind.VERSION, "原阅读章节已失效，请主动打开目录选择章节；原进度已保留")
                val initial = if (target == null) readingSource.initialReading(book.id, refs) else null
                val chapter = initial?.chapter ?: readingSource.chapter(book.id, target!!)
                if (refs.none { it.id == chapter.id }) throw SourceProblem(FailureKind.VERSION, "目录已变化，请重试；原进度已保留")
                coroutineContext.ensureActive()
                if (chapter.paragraphs.none { it.isNotBlank() } && chapter.graphics.isEmpty()) throw SourceProblem(FailureKind.EMPTY, "章节暂时没有正文，请稍后重试")
                val offset = if (chapterId == null && saved != null) saved.offset else 0
                if (offset !in 0..chapter.text.lastIndex || Character.isLowSurrogate(chapter.text[offset]))
                    throw SourceProblem(FailureKind.VERSION, "原阅读位置已失效，请主动打开目录选择章节；原进度已保留")
                val graphicId=if(chapterId==null && saved!=null) saved.graphicId else chapter.graphics.firstOrNull { it.offset==0 }?.id
                if(graphicId!=null && chapter.graphics.none { it.id==graphicId }) throw SourceProblem(FailureKind.VERSION,"原图片位置已失效，原进度已保留")
                val lengths = readingSource.chapterLengths(book.id)?.takeIf { it.size == refs.size && it.all { n -> n > 0 } }
                coroutineContext.ensureActive(); sourceFor(book); readingStore.save(book.id, chapter.id, offset,graphicId); readingStore.markEntered(book, clock())
                state.update { it.copy(chapters = refs, chapter = chapter, offset = offset, revision = it.revision + 1, loading = false,
                    chapterLengths = lengths, chaptersComplete = true,progressOrderReliable=readingSource.progressIsReliable(book.id), shelfBooks = combinedShelf(), notice = initial?.notice ?: chapter.navigationNotice,graphicId=graphicId) }
            } catch (e: Exception) { fail(e) }
        }
    }
    private suspend fun completeJump(current: ReaderState, chapterId: String, lastPage: Boolean) {
        val book = current.book ?: return
        if (current.chapters.none { it.id == chapterId }) throw SourceProblem(FailureKind.VERSION, "目录章节已失效，请重新打开目录")
        val readingStore = storeFor(book)
        val chapter = readingSourceFor(book).chapter(book.id, chapterId)
        coroutineContext.ensureActive()
        if (chapter.paragraphs.none { it.isNotBlank() } && chapter.graphics.isEmpty()) throw SourceProblem(FailureKind.EMPTY, "章节暂时没有正文，请稍后重试")
        val tail = chapter.text.lastIndex
        val offset = if (lastPage) {
            if (tail > 0 && Character.isLowSurrogate(chapter.text[tail])) tail - 1 else tail
        } else 0
        val graphicId=if(lastPage) chapter.graphics.lastOrNull { it.offset>=tail }?.id else chapter.graphics.firstOrNull { it.offset==0 }?.id
        coroutineContext.ensureActive(); sourceFor(book); readingStore.save(book.id, chapter.id, offset,graphicId); readingStore.markEntered(book, clock())
        state.update { it.copy(chapter = chapter, offset = offset, revision = it.revision + 1, loading = false, chaptersComplete = true,
            shelfBooks = combinedShelf(),graphicId=graphicId,notice=chapter.navigationNotice) }
    }
    fun jump(chapterId: String, lastPage: Boolean = false) {
        val current = state.value; current.book ?: return
        if (current.loading) return
        retry = { jump(chapterId, lastPage) }
        state.update { it.copy(loading = true, error = null, notice = null, chaptersComplete = false) }
        pending = viewModelScope.launch { try { completeJump(current,chapterId,lastPage) } catch(e: Exception) { fail(e) } }
    }
    fun boundary(direction: Int) {
        val current = state.value; val book = current.book ?: return; val chapter = current.chapter ?: return
        if(current.loading || direction !in listOf(-1,1)) return
        retry = { boundary(direction) }
        state.update { it.copy(loading = true,error = null,notice = null,chaptersComplete = false) }
        pending = viewModelScope.launch {
            try {
                val next = readingSourceFor(book).readingNeighbor(book.id,chapter.id,direction,current.chapters)
                coroutineContext.ensureActive()
                if(next == null) state.update { it.copy(loading = false,chaptersComplete = current.chaptersComplete,
                    notice = if(direction < 0) "已经是全书开头" else "全书读完了，感谢阅读") }
                else completeJump(current,next,direction < 0)
            } catch(e: Exception) { fail(e) }
        }
    }
    private fun activeAuthorized()=state.value.book?.let{runCatching{sourceFor(it)}.isSuccess}?:false
    fun returnReadablePage() {
        val s=state.value
        if(s.canReturnPage && activeAuthorized() && s.chapter!=null)state.update{it.copy(error=null,loading=false,chaptersComplete=true,canReturnPage=false)}
    }
    fun hasPreservedPosition():Boolean=state.value.book?.let{storeFor(it).preservedPosition(it.id)!=null}?:false
    fun restorePreservedPosition() {state.value.book?.let{if(storeFor(it).restorePreserved(it.id))open(it)}}
    fun recoverFromStart() {state.value.book?.let{open(it,fromStart=true)}}
    fun position(offset: Int) {
        visualPosition(offset,null)
    }
    fun visualPosition(offset: Int, graphicId: String?) {
        val s = state.value; val book = s.book ?: return; val chapter = s.chapter ?: return
        if (s.loading || s.error != null) return
        val bounded = offset.coerceIn(0, chapter.text.lastIndex.coerceAtLeast(0))
        val safe=if(graphicId!=null && bounded>0 && Character.isLowSurrogate(chapter.text[bounded])) bounded-1 else bounded
        if(graphicId!=null && chapter.graphics.none { it.id==graphicId }) return
        storeFor(book).save(book.id, chapter.id, safe,graphicId); state.update { it.copy(offset = safe,graphicId=graphicId) }
    }
    fun settings(settings: ReaderSettings) { val safe = settings.normalized(); layoutStore.saveSettings(safe); store.saveSettings(safe); state.update { it.copy(settings = safe) } }
    fun backToShelf() {
        pending?.cancel()
        state.update { it.copy(book = null, chapter = null, chaptersComplete = false, loading = false, error = null, notice = null,
            topTab = "shelf", shelfBooks = combinedShelf(), shelfRevision = it.shelfRevision + 1) }
    }
    fun dismissNotice() { state.update { it.copy(notice = null) } }
    fun retry() { retry.invoke() }
    private fun fail(e: Exception) { if (e is CancellationException) throw e
        state.value.book?.takeIf{it.sourceId==source.id}?.let{book->if((source as? BookHubSource)?.observedReadPermission(book.id)==false)storeFor(book).confirmReadPermission(book.id,false)}
        state.update { it.copy(loading = false, error = safeError(e), chaptersComplete = false,canReturnPage=it.chapter!=null && activeAuthorized() && !(e is SourceProblem && e.kind in listOf(FailureKind.AUTH,FailureKind.FORBIDDEN))) } }
    private fun safeError(e: Exception) = if (e is SourceProblem) "${e.message ?: "暂时无法读取，请重试"}\n诊断：${e.diagnostic}" else "暂时无法读取，请重试。已保存的进度会保留。\n诊断：READER_UNKNOWN"
}
