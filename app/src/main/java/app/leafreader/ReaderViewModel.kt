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
import app.leafreader.sync.ShelfSyncRuntime

data class OfflineManagementSnapshot(val books:List<Book> = emptyList(),val bytesByBook:Map<String,Long> = emptyMap(),val totalBytes:Long=0,val otherBytes:Long=0,val available:Boolean=false)
object OfflineRetention {
    const val NOTE="已有的本机离线副本仍保留。用原账号重新登录后，完整且版本匹配的副本无需重下；完整性以本机核验为准。"
}
data class ShelfDifference(val token:Long,val addRemote:List<Book>,val removeRemote:List<Book>,val skipped:List<Book>,val localKeys:Set<String>)
private data class ShelfChoicePlan(val view:ShelfDifference,val remote:List<Book>,val details:Map<String,ShelfMembershipDetail>,val owner:String,val generation:Long,val epoch:Long,val runtimeEpoch:Long)
data class ReaderState(
    val books: List<Book> = emptyList(), val book: Book? = null,
    val chapters: List<ChapterRef> = emptyList(), val chapter: Chapter? = null,
    val offset: Int = 0, val revision: Int = 0, val settings: ReaderSettings = ReaderSettings(),
    val findError:String?=null,val loading: Boolean = false, val error: String? = null, val notice: String? = null,
    val shelfRevision: Int = 0, val query: String = "", val nextCursor: String? = null, val hasMore: Boolean = false,
    val catalogImportErrors:List<LegadoRejectedSource> = emptyList(),val catalogImportedCount:Int=0, val catalogNote:String?=null,val catalogSections:List<CatalogSection> = emptyList(),val catalogCanBack:Boolean=false,val addingCatalog:Boolean=false,val catalogAddError:String?=null,
    val catalogId:String="bookhub", val catalogLabel:String="BookHub", val publicCatalog:Boolean=false, val catalogBrowsing:Boolean=false,
    val remote: Boolean = false, val sourceLabel: String = "BookHub · 未登录", val connecting: Boolean = false,
    val connectionError: String? = null, val connectionRevision: Int = 0,
    val topTab: String = "shelf", val shelfBooks: List<Book> = emptyList(), val shelfLoading: Boolean = true,
    val shelfError: String? = null, val shelfHasMore: Boolean = false, val findLoading: Boolean = false,
    val chapterLengths: List<Int>? = null, val chaptersComplete: Boolean = false, val graphicId: String? = null,
    val downloadLabels:Map<String,String> = emptyMap(),val automaticWifi:Boolean=true,val automaticWifiOnly:Boolean=true,val offline:Boolean=false,
    val shelfManaging:Boolean=false,val shelfSelection:Set<String> = emptySet(),
    val localShelfWarning:String?=null,val retentionNote:String?=null,val progressOrderReliable:Boolean=true,val downloadRevision:Int=0, val shelfKnownComplete:Boolean=false, val canReturnPage:Boolean=false,val clearingBooks:Set<String> = emptySet(),val syncingShelf:Boolean=false,val syncRevision:Int=0,val membershipBusy:Set<String> = emptySet(),val membershipFeedback:String?=null,val membershipRevision:Int=0,val shelfChoicePending:Boolean=false,val shelfDifference:ShelfDifference?=null,val showShelfChoice:Boolean=false,val shelfChoiceBusy:Boolean=false
)

@android.annotation.SuppressLint("ApplySharedPref") // Complete one-time layout migration before switching accounts.
class ReaderViewModel @JvmOverloads constructor(application: Application,
    private var source: BookSource = SignedOutBookSource(),
    private val connectionGate: BookHubConnectionGate = BookHubConnectionGate(),
    private val sessionVault: SessionVault = EncryptedSessionVault(application), private val offlineRuntime:OfflineDownloads?=null,
    independentSources:List<BookSource> = emptyList(), private val linkBookHubShelf:Boolean=true, private val automaticShelfSync:ShelfSyncRuntime?=null, private val opdsFactory:(OpdsSubscription)->OpdsSource={OpdsSource(application,it)}, private val legadoFactory:(LegadoDefinition)->LegadoSource={LegadoSource(application,it)}, private val automaticSourceDownloads:AutomaticSourceDownloads?=null, private val clock: () -> Long = { System.currentTimeMillis() }) : AndroidViewModel(application) {
    private val localSource = source
    private val globalDownloadPrefs=GlobalDownloadPreferences(application)
    private val autoSources=automaticSourceDownloads?:AutomaticSourceDownloads.get(application)
    private val subscriptions=OpdsSubscriptions(application)
    private val legadoRegistry=LegadoSources(application)
    private val independent=(independentSources+subscriptions.all().filter{it.retainedRights}.map{opdsFactory(it)}+legadoRegistry.all().map{legadoFactory(it)}).associateBy{it.id}.also{map->require(map.values.all{it.independentOffline && !it.id.startsWith("bookhub-v1-")})}.mapValues{(_,value)->if(value is IndependentBookSource)autoSources.register(value)else value}.toMutableMap()
    private val catalogPrefs=application.getSharedPreferences("find-source-v1",Application.MODE_PRIVATE)
    private var selectedCatalog=catalogPrefs.getString("selected","bookhub")?.takeIf{it=="bookhub" || it in independent}?:"bookhub"
    private fun findSource():BookSource=independent[selectedCatalog]?:source
    fun publicSources()=independent.values.filterIsInstance<IndependentBookSource>()
    private fun publicSource(book:Book)=independent[book.sourceId] as? IndependentBookSource
    private var catalogPath:String?=null
    private var effectiveFindTerm:String?=null
    private val catalogHistory=mutableListOf<String?>()
    private var addCatalogJob:Job?=null
    private fun catalogName(id:String)=if(id=="bookhub")"BookHub"else (independent[id] as? IndependentBookSource)?.label?:"书源"
    private val publicDownloads=java.util.concurrent.ConcurrentHashMap<String,Job>()
    private val publicDownloadTokens=java.util.concurrent.ConcurrentHashMap<String,Any>()
    private val publicLabels=java.util.concurrent.ConcurrentHashMap<String,String>()
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
    private val state = MutableStateFlow(ReaderState(catalogId=selectedCatalog,catalogLabel=catalogName(selectedCatalog),publicCatalog=selectedCatalog!="bookhub",settings = initialSettings, shelfBooks = unifiedShelf(),localShelfWarning=shelfIndex.warning,retentionNote=if(retentionPrefs.getBoolean("show",false))OfflineRetention.NOTE else null,automaticWifi=globalDownloadPrefs.enabled(),automaticWifiOnly=globalDownloadPrefs.wifiOnly(),offline=offlineRuntime?.connection?.online==false))
    val ui = state.asStateFlow()
    private val cleanupRuntime by lazy { offlineRuntime?:OfflineDownloads(getApplication(),scheduleJobs=false) }
    private val cleanupJobs=mutableMapOf<String,Job>()
    private val bookOperations=java.util.concurrent.ConcurrentHashMap<String,Long>()
    private val chapterChecks=kotlinx.coroutines.sync.Semaphore(2)
    private val membershipMutex=kotlinx.coroutines.sync.Mutex()
    private val membershipJobs=mutableMapOf<String,Job>()
    private val membershipTokens=mutableMapOf<String,Long>()
    private var membershipEpoch=0L
    private var choicePlan:ShelfChoicePlan?=null
    private var choiceJob:Job?=null
    private var choiceToken=0L
    private var loginComparisonActive=false
    private var loginPromptShown=false
    private var shelfMemberEpoch=0L
    private var shelfRuntimeEpoch=0L
    private var shelfSourceContext:BookSource?=null
    private var shelfSessionContext=0L
    private var shelfSnapshotSafe=true
    private var shelfPriorKnown:Set<String> = emptySet()
    private var manualShelfSync:Job?=null
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
        // Only resume explicit new-version cleanup intents; never sweep existing hidden books.
        for(owner in shelfIndex.sources())for(id in ReadingStore(application,owner).pendingCleanups())
            finishBookCleanup(Book(id,"","","","",0,0,sourceId=owner))
        automaticShelfSync?.let { runtime->
            viewModelScope.launch {runtime.revision.collectLatest {refreshShelfOrder();state.update{it.copy(membershipBusy=runtime.running.value,membershipRevision=it.membershipRevision+1)}}}
            viewModelScope.launch {runtime.running.collectLatest {keys->state.update{it.copy(membershipBusy=keys)}}}
            viewModelScope.launch {runtime.completions.collectLatest { event->
                val b=event.intent.book
                if(!event.intent.present && event.intent.clearOnRemove && state.value.book?.key==b.key)backToShelf()
                (source as? BookHubSource)?.takeIf{it.id==b.sourceId}?.invalidateDownloadedMemory(b.id)
                if(!event.intent.present && event.intent.clearOnRemove)withContext(Dispatchers.IO){covers.clear(b.sourceId,b.id)}
                refreshShelfOrder()
            }}
        }
        viewModelScope.launch {
            OfflineEvents.revision.collectLatest {
                delay(100)
                // Per-book integrity is checked only when its actions are opened, never across the shelf.
                state.update{it.copy(automaticWifi=globalDownloadPrefs.enabled(),automaticWifiOnly=globalDownloadPrefs.wifiOnly(),offline=offlineRuntime?.connection?.online==false,downloadRevision=it.downloadRevision+1)}
            }
        }
    }
    private fun retainOfflineNote(reason:String="UNAVAILABLE") {
        retentionPrefs.edit().putBoolean("show",true).putString("reason",reason).commit()
        state.update{it.copy(retentionNote=OfflineRetention.NOTE)}
    }
    private fun identityKey(c:BookHubClient)=digest(c.origin.value+"|"+c.accountId)
    private fun attachClient(candidate: BookHubClient, tab: String, persist: Boolean) {
        val next=automaticShelfSync?.activate(candidate,persist)?:candidate
        if(!identityPrefs.contains(identityKey(next)))identityPrefs.edit().putString(identityKey(next),next.storageOrigin).commit()
        retentionPrefs.edit().putBoolean("show",false).remove("reason").commit()
        client = next; source = offlineRuntime?.source(next)?:BookHubSource(next); store = ReadingStore(getApplication(), source.id); sessionAvailable = true
        shelfIndex.remember(source.id)
        choicePlan=null;loginComparisonActive=linkBookHubShelf && persist;loginPromptShown=false
        if(linkBookHubShelf && persist)store.requireShelfChoice()
        serviceShelf = store.savedServiceShelf(); freshServiceShelf = emptyList(); shelfCursor = null
        val generation = sessionGeneration
        next.onSessionChanged = { snapshot ->
            if (client === next && generation == sessionGeneration) {
                if (snapshot == null) {
                    retainOfflineNote("EXPIRED");offlineRuntime?.deactivate()
                    sessionAvailable = false; sessionVault.clear();choicePlan=null
                    state.update { s->val affected=s.book?.sourceId==source.id
                        s.copy(loading=if(affected)false else s.loading,shelfLoading=false,findLoading=false,shelfDifference=null,showShelfChoice=false,shelfKnownComplete=false,
                            canReturnPage=if(affected)false else s.canReturnPage,
                            error=if(affected && automaticShelfSync==null)"本书的 BookHub 会话已失效。该来源未确认无会话离线许可，请恢复原账号授权；副本和进度保留。\n诊断：BH_AUTH" else s.error,
                            notice=if(affected && automaticShelfSync==null)"BookHub 登录已失效，请重新登录。\n"+OfflineRetention.NOTE else s.notice,
                            sourceLabel="BookHub · 会话需重新登录") }
                } else if (!sessionVault.save(snapshot)) {
                    retainOfflineNote()
                    state.update { it.copy(notice = "设备无法安全保存登录；当前会话仍可用，重启后需要重新登录。密码不会保存。\n"+OfflineRetention.NOTE) }
                }
            }
        }
        if(automaticShelfSync==null)offlineRuntime?.activate(next)
        serverPrefs.edit().putString("origin", next.origin.value).apply()
        state.value = ReaderState(catalogId=selectedCatalog,catalogLabel=catalogName(selectedCatalog),publicCatalog=selectedCatalog!="bookhub",settings = state.value.settings, clearingBooks=state.value.clearingBooks,membershipBusy=state.value.membershipBusy, remote = true, sourceLabel = "BookHub · 已连接",shelfChoicePending=linkBookHubShelf && store.shelfChoicePending(),
            topTab = tab, shelfBooks = combinedShelf(),localShelfWarning=shelfIndex.warning, connectionRevision = state.value.connectionRevision + 1,automaticWifi=globalDownloadPrefs.enabled(),automaticWifiOnly=globalDownloadPrefs.wifiOnly(),offline=offlineRuntime?.connection?.online==false)
        if (persist) next.snapshot()?.let { next.onSessionChanged?.invoke(it) }
        if(persist && automaticShelfSync!=null) {
            val current=source;state.update{it.copy(shelfLoading=true)}
            viewModelScope.launch {automaticShelfSync.onLogin(next);if(source===current && sessionAvailable)loadShelf()else state.update{it.copy(shelfLoading=false)}}
        } else {if(persist || (combinedShelf().isEmpty() && !state.value.shelfChoicePending))loadShelf() else state.update{it.copy(shelfLoading=false)};if(!persist)automaticShelfSync?.kick(next)}
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
        publicSource(book)?.let{return it.thumbnail(book.id)}
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
    fun canAddBook(book:Book):Boolean = !membershipBusy(book) && (isOnShelf(book) || independent.containsKey(book.sourceId) || source !is BookHubSource || state.value.shelfKnownComplete || book.id in storeFor(book).allHiddenShelfIds())

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
    fun selectCatalog(id:String) {
        if(id!="bookhub" && id !in independent || id==selectedCatalog)return
        catalogPath=null;catalogHistory.clear();clearSearch();selectedCatalog=id;catalogPrefs.edit().putString("selected",id).apply()
        state.update{it.copy(catalogId=id,catalogLabel=catalogName(id),publicCatalog=id!="bookhub",catalogBrowsing=false)}
    }
    fun browsePublic(){if(state.value.publicCatalog){catalogPath=null;catalogHistory.clear();searchCatalog("",true)}}
    fun navigateCatalog(section:CatalogSection){if(!state.value.publicCatalog)return;catalogHistory+=catalogPath;catalogPath=section.href;searchCatalog("",true)}
    fun catalogBack(){if(catalogHistory.isEmpty())return;catalogPath=catalogHistory.removeAt(catalogHistory.lastIndex);searchCatalog("",true)}
    fun clearCatalogImportResult(){state.update{it.copy(catalogImportErrors=emptyList(),catalogImportedCount=0,catalogAddError=null)}}
    fun cancelAddCatalog(){addCatalogJob?.cancel();state.update{it.copy(addingCatalog=false,catalogAddError=null,catalogImportErrors=emptyList(),catalogImportedCount=0)}}
    fun addOpds(name:String,url:String,rights:Boolean,done:()->Unit) {
        addCatalogJob?.cancel();state.update{it.copy(addingCatalog=true,catalogAddError=null)}
        addCatalogJob=viewModelScope.launch {try {
            require(name.trim().length in 1..80 && rights){"请填写名称并确认下载与离线保留权"}
            val descriptor=OpdsSubscription(name.trim(),CatalogOrigin(url).root.toString(),rights)
            val provider=opdsFactory(descriptor)
            provider.verify();coroutineContext.ensureActive()
            withContext(Dispatchers.IO){check(subscriptions.save(descriptor)){"订阅未能保存"}}
            independent[provider.id]=autoSources.register(provider);shelfIndex.remember(provider.id);selectCatalog(provider.id)
            state.update{it.copy(addingCatalog=false,topTab="find",shelfRevision=it.shelfRevision+1)};done()
        }catch(e:CancellationException){throw e}catch(e:Exception){state.update{it.copy(addingCatalog=false,catalogAddError=if(e is SourceProblem)e.message else e.message?.takeIf{e is IllegalArgumentException}?:"添加失败，请检查地址后重试")}}}
    }
    override fun onCleared(){super.onCleared();independent.values.filterIsInstance<IndependentBookSource>().filterNot{autoSources.runningFor(it.id)}.forEach{it.close()}}
    fun importLegado(json:String,rights:Boolean,done:()->Unit) {
        addCatalogJob?.cancel();state.update{it.copy(addingCatalog=true,catalogAddError=null,catalogImportErrors=emptyList(),catalogImportedCount=0)}
        addCatalogJob=viewModelScope.launch {try {
            val result=withContext(Dispatchers.IO){LegadoRules.review(json,rights)}
            val definitions=result.accepted
            if(definitions.isEmpty()){state.update{it.copy(addingCatalog=false,catalogImportErrors=result.rejected,catalogImportedCount=0,catalogAddError=null)};return@launch}
            val providers=definitions.map(legadoFactory)
            withContext(Dispatchers.IO){check(legadoRegistry.save(definitions)){"来源未能保存"}}
            coroutineContext.ensureActive();providers.forEach{independent[it.id]=autoSources.register(it);shelfIndex.remember(it.id)};selectCatalog(providers.first().id)
            state.update{it.copy(addingCatalog=false,topTab="find",shelfRevision=it.shelfRevision+1,catalogImportErrors=result.rejected,catalogImportedCount=if(result.rejected.isEmpty())0 else providers.size)};if(result.rejected.isEmpty())done()
        }catch(e:CancellationException){throw e}catch(e:Exception){state.update{it.copy(addingCatalog=false,catalogAddError=if(e is SourceProblem)e.message else "导入失败，请检查 JSON")}}}
    }
    fun catalogCanBrowse()=(findSource()as? IndependentBookSource)?.canBrowse!=false
    suspend fun bookDetails(book:Book)=publicSource(book)?.details(book)?:book
    fun selectTab(tab: String) { if(tab in listOf("shelf","find","settings"))state.update{it.copy(topTab=tab,shelfManaging=it.shelfManaging && tab==it.topTab,shelfSelection=if(tab==it.topTab)it.shelfSelection else emptySet())} }
    fun manageShelf() {state.update{it.copy(shelfManaging=true,shelfSelection=emptySet())}}
    fun endShelfManagement() {state.update{it.copy(shelfManaging=false,shelfSelection=emptySet())}}
    fun toggleShelfSelection(key:String) {state.update{if(!it.shelfManaging || it.shelfBooks.none{b->b.key==key})it else it.copy(shelfSelection=if(key in it.shelfSelection)it.shelfSelection-key else it.shelfSelection+key)}}
    fun selectAllShelf() {state.update {val keys=it.shelfBooks.map{b->b.key}.toSet();if(!it.shelfManaging)it else it.copy(shelfSelection=if(it.shelfSelection.intersect(keys)==keys)emptySet() else keys)}}
    fun isOnShelf(bookId: String): Boolean = isOnShelf(Book(bookId,"","","","",0,0,sourceId=source.id))
    fun isOnShelf(book: Book): Boolean = state.value.shelfBooks.any { it.key == tagged(book).key }
    fun addToShelf(book: Book): Boolean {
        val owned=tagged(book)
        if(linkBookHubShelf && owned.sourceId.startsWith("bookhub-v1-")) {
            if(!canAddBook(owned) || cleanupPending(owned))return false
            if(automaticShelfSync!=null && hasShelfSession(owned)) {enqueueAutomatic(owned,true);return isOnShelf(owned)}
            if(willSyncMembership(owned)){scheduleMembership(owned,true);return isOnShelf(owned)}
            automaticShelfSync?.cancelLocal(owned)
            if(owned.sourceId !in shelfIndex.sources())return false
            val added=addLocalToShelf(owned,localOverride=true)
            if(added){storeFor(owned).saveShelfOperation(owned.id,ShelfOperationRecord(true,"LOCAL_ONLY",clock()));state.update{it.copy(membershipFeedback=null,membershipRevision=it.membershipRevision+1)}}
            return added
        }
        return addLocalToShelf(owned)
    }
    private fun addLocalToShelf(owned:Book,remoteConfirmed:Boolean=false,localOverride:Boolean=false):Boolean {
        if(storeFor(owned).cleanupPending(owned.id)) {state.update{it.copy(notice="这本书正在清理，完成后可重新加入。")};return false}
        if(!remoteConfirmed && !canAddBook(owned))return false
        if(!linkBookHubShelf && owned.sourceId !in independent && owned.sourceId!=localSource.id && (owned.sourceId!=source.id || !sessionAvailable)) return false
        val added=!isOnShelf(owned)
        if(!storeFor(owned).addToShelf(owned,remoteConfirmed,localOverride)){state.update{it.copy(notice="加入书架未能完整保存，请重试；原记录保留。")};return false};
        if(owned.sourceId==source.id && serviceShelf.any{it.id==owned.id}) {serviceShelf=serviceShelf.map{if(it.id==owned.id)owned else it};store.rememberServiceShelf(serviceShelf)}
        membershipEpoch++;refreshShelfOrder()
        if(added && globalDownloadPrefs.enabled())publicSource(owned)?.takeIf{it.supportsAutomaticDownload}?.let{provider->
            val operation=bookOperations[owned.key]?:0L
            viewModelScope.launch(Dispatchers.IO){try{
                val queued=autoSources.enqueue(provider,owned){globalDownloadPrefs.enabled()&&(bookOperations[owned.key]?:0L)==operation&&!cleanupPending(owned)&&publicDownloads[owned.key]?.isActive!=true&&isOnShelf(owned)}
                if(queued&&!autoSources.scheduleJobs)autoSources.request(provider.id,owned.id)?.let{autoSources.execute(it)}
            }catch(e:CancellationException){throw e}catch(_:Exception){publicLabels[owned.key]="自动下载未能排队，可手动下载";OfflineEvents.changed()}}
        }
        if(added && publicSource(owned)==null && downloadsAvailable(owned) && globalDownloadPrefs.enabled() && offlineRuntime?.automaticWifi()==true) {
            val current=source;val generation=sessionGeneration;val operation=bookOperations[owned.key]?:0L
            viewModelScope.launch(Dispatchers.IO) {try {
                if(source===current && generation==sessionGeneration)offlineRuntime.enqueueIfNeeded(current.id,owned,automatic=true,stillWanted={source===current && generation==sessionGeneration && (bookOperations[owned.key]?:0L)==operation && !storeFor(owned).cleanupPending(owned.id)})
            }catch(_:Exception){if(source===current && generation==sessionGeneration)state.update{it.copy(notice="书架已保存，自动下载未能排队；可手动重试。")}}}
        }
        return added
    }
    fun removeFromShelf(keys:Set<String>,expectedConnectionRevision:Int,localOnly:Boolean=false) {
        if(expectedConnectionRevision!=state.value.connectionRevision)return
        val targets=state.value.shelfBooks.filter{it.key in keys}.distinctBy{it.key}
        for(book in targets) {
            if(!localOnly && automaticShelfSync!=null && hasShelfSession(book))enqueueAutomatic(book,false)
            else if(!localOnly && willSyncMembership(book))scheduleMembership(book,false)
            else if(!membershipBusy(book)) {
                automaticShelfSync?.cancelLocal(book);membershipEpoch++;beginBookCleanup(book,hide=true)
                if(linkBookHubShelf && book.sourceId.startsWith("bookhub-v1-"))storeFor(book).saveShelfOperation(book.id,ShelfOperationRecord(false,"LOCAL_ONLY",clock()))
            }
        }
        refreshShelfOrder()
        val visible=state.value.shelfBooks.map{it.key}.toSet()
        state.update{it.copy(shelfSelection=it.shelfSelection.intersect(visible))}
    }
    private fun hasShelfSession(book:Book)=source is BookHubSource && sessionAvailable && client?.snapshot()!=null && book.sourceId==source.id
    private fun enqueueAutomatic(book:Book,present:Boolean) {
        val active=client?:return
        if(automaticShelfSync?.enqueue(active,book,present)!=true)state.update{it.copy(notice="操作未保存，请重试")}
    }
    fun pendingShelfRemoval(book:Book)=automaticShelfSync?.pending(book)?.let{!it.present && it.state!="APPLYING"}==true
    fun cancelShelfRemoval(book:Book){automaticShelfSync?.cancel(book)}
    fun membershipBusy(book:Book)=book.key in state.value.membershipBusy
    fun willSyncMembership(book:Book)=linkBookHubShelf && source is BookHubSource && sessionAvailable &&
        book.sourceId==source.id && offlineRuntime?.connection?.online!=false
    fun offerJoinBookHub(book:Book)=willSyncMembership(book) && (book.id !in storeFor(book).remoteKnownIds() || book.id in storeFor(book).localOnlyIds() || (state.value.shelfChoicePending && state.value.shelfKnownComplete && freshServiceShelf.none{it.id==book.id}))
    fun shelfOperation(book:Book):ShelfOperationRecord {
        val op=storeFor(book).shelfOperation(book.id)
        return if(op.state=="RUNNING" && !membershipBusy(book))op.copy(state="UNCONFIRMED",reason="请求已中断；请刷新核对或明确重试，不会自动补发")else op
    }
    fun retryShelfOperation(book:Book) {
        val op=shelfOperation(book)
        if(!willSyncMembership(book)){state.update{it.copy(notice="请先联网并连接这本书的原服务、原账号；未向当前其他来源发出请求。")};return}
        scheduleMembership(book,op.present)
    }
    fun joinBookHub(book:Book){if(automaticShelfSync!=null && hasShelfSession(book))enqueueAutomatic(book,true)else if(willSyncMembership(book))scheduleMembership(book,true)}
    fun consumeMembershipFeedback(message:String){state.update{if(it.membershipFeedback==message)it.copy(membershipFeedback=null)else it}}
    private fun scheduleMembership(book:Book,present:Boolean) {
        if(!willSyncMembership(book) || membershipBusy(book) || cleanupPending(book))return
        val current=source;val activeClient=client?:return;val generation=sessionGeneration;val target=storeFor(book)
        val token=(membershipTokens[book.key]?:0L)+1;membershipTokens[book.key]=token;membershipEpoch++
        val at=clock()
        if(!target.saveShelfOperation(book.id,ShelfOperationRecord(present,"RUNNING",at))){state.update{it.copy(notice="无法保存操作状态，本次未发出请求。")};return}
        state.update{it.copy(membershipBusy=it.membershipBusy+book.key,membershipRevision=it.membershipRevision+1)}
        membershipJobs[book.key]=viewModelScope.launch {
            fun currentContext()=source===current && sessionGeneration==generation && sessionAvailable && client===activeClient && membershipTokens[book.key]==token
            var sent=false
            try {
                membershipMutex.lock()
                try {
                    if(!currentContext())throw CancellationException()
                    val before=activeClient.shelfDetail(book.id)
                    if(!currentContext())throw CancellationException()
                    var latest=before.book
                    if(before.present!=present) {
                        sent=true
                        activeClient.changeShelf(book.id,present,before.revision)
                        if(!currentContext())throw CancellationException()
                        if(present) {
                            val after=activeClient.shelfDetail(book.id,verifyAccount=false)
                            if(!after.present)throw SourceProblem(FailureKind.VERSION,"服务书架已再次变化，请刷新核对",diagnostic="BH_SHELF_REVISION_CONFLICT")
                            latest=after.book
                        }
                    }
                    if(!currentContext())throw CancellationException()
                    membershipEpoch++
                    val owned=latest.copy(sourceId=book.sourceId)
                    if(present) {
                        if(!addLocalToShelf(owned,remoteConfirmed=true) && !isOnShelf(owned))throw java.io.IOException()
                    } else if(!beginBookCleanup(book,remoteHide=true))throw java.io.IOException("本机清理状态保存失败")
                    target.saveShelfOperation(book.id,ShelfOperationRecord(present,"SUCCESS",at))
                    refreshShelfOrder()
                    state.update{it.copy(membershipFeedback=null,membershipRevision=it.membershipRevision+1)}
                }finally{membershipMutex.unlock()}
            }catch(e:Exception) {
                if(membershipTokens[book.key]==token) {
                    val reason=when {
                        e is SourceProblem && e.kind==FailureKind.VERSION->"书架修订冲突，请重试"
                        e is SourceProblem && e.kind==FailureKind.AUTH->"登录已失效，请重新登录"
                        e is SourceProblem && e.kind==FailureKind.FORBIDDEN->"无权同步书架，本机保留"
                        sent->"同步结果未确认，请重试"
                        e is CancellationException->"连接已切换，请回原账号重试"
                        else->"同步失败，请重试"
                    }
                    target.saveShelfOperation(book.id,ShelfOperationRecord(present,if(sent || e is CancellationException)"UNCONFIRMED" else "FAILED",at,reason))
                    if(source===current && generation==sessionGeneration)state.update{it.copy(notice=reason,membershipRevision=it.membershipRevision+1)}
                }
                if(e is CancellationException)throw e
            }finally {
                if(membershipTokens[book.key]==token)state.update{it.copy(membershipBusy=it.membershipBusy-book.key,membershipRevision=it.membershipRevision+1)}
            }
        }
    }
    private fun beginBookCleanup(book:Book,hide:Boolean=false,remoteHide:Boolean=false):Boolean {
        if(cleanupJobs[book.key]?.isActive==true)return false
        if(state.value.book?.key==book.key){backToShelf();activeReadingSource=null}
        bookOperations.merge(book.key,1L,Long::plus)
        if(!storeFor(book).requestCleanup(book.id,hide,remoteHide)) {
            state.update{it.copy(notice="清理操作未能保存，请重试。")};return false
        }
        finishBookCleanup(book);return true
    }
    private fun finishBookCleanup(book:Book) {
        val token=storeFor(book).cleanupToken(book.id)?:return
        if(cleanupJobs[book.key]?.isActive==true)return
        state.update{it.copy(clearingBooks=it.clearingBooks+book.key,downloadRevision=it.downloadRevision+1)}
        cleanupJobs[book.key]=viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    publicDownloadTokens.remove(book.key);publicDownloads.remove(book.key)?.cancel()
                    autoSources.cancel(book.sourceId,book.id)
                    publicSource(book)?.clear(book.id)
                    publicLabels.remove(book.key)
                    if(Regex("bookhub-v1-[a-f0-9]{32}").matches(book.sourceId))cleanupRuntime.eraseAndCancel(book.sourceId,book.id,token)
                    covers.clear(book.sourceId,book.id)
                }
                (source as? BookHubSource)?.takeIf{it.id==book.sourceId}?.invalidateDownloadedMemory(book.id)
                val done=if(Regex("bookhub-v1-[a-f0-9]{32}").matches(book.sourceId))cleanupRuntime.finishErase(book.sourceId,book.id,token) else storeFor(book).finishCleanup(book.id,token)
                if(!done)throw java.io.IOException()
            }catch(e:CancellationException){throw e}
            catch(_:Exception){state.update{it.copy(notice="本书清理未完成，进度已清除；请重试清除。待完成的清理会在下次启动继续。")}}
            finally {state.update{it.copy(clearingBooks=it.clearingBooks-book.key,downloadRevision=it.downloadRevision+1)};refreshShelfOrder()}
        }
    }
    fun cleanupPending(book:Book)=storeFor(book).cleanupPending(book.id)
    suspend fun inspectDownload(book:Book,chapterId:String?=null):OfflineStatus {
        publicSource(book)?.let{return it.status(book.id)}
        val runtime=offlineRuntime?:return OfflineStatus()
        if(!canManageOffline(book) || cleanupPending(book))return OfflineStatus()
        return withContext(Dispatchers.IO){
            val cache=runtime.cache(book.sourceId)
            if(chapterId!=null) {
                val m=cache.manifest(book.id,chapterId)?:return@withContext OfflineStatus()
                if(chapterId.split(':').getOrNull(1)!=digest(m.getString("contentVersion")).take(16))return@withContext OfflineStatus()
            }
            cache.status(book.id,verify=true,chapterId=chapterId)
        }
    }
    suspend fun chapterOfflineStatus(book:Book,chapterId:String):String {
        if(cleanupPending(book))return "正在清理"
        val current=try{sourceFor(book)}catch(_:Exception){return "需原账号授权"}
        if(current is IndependentBookSource)return current.chapterStatus(book.id,chapterId)
        if(current !is BookHubSource)return "本地内容"
        chapterChecks.acquire()
        try{return current.localChapterStatus(book.id,chapterId)}catch(e:CancellationException){throw e}catch(_:Exception){return "状态不可用"}finally{chapterChecks.release()}
    }
    fun downloadTaskLabel(book:Book):String? {
        if(publicSource(book)!=null)return publicLabels[book.key]?:autoSources.label(book.sourceId,book.id)
        if(!canManageOffline(book))return null
        val r=offlineRuntime?.request(offlineRuntime.key(book.sourceId,book.id))?:return null
        return when(r.state){"QUEUED"->"已排队 · ${r.message}";"WAITING"->"等待下载 · ${r.message}";"RUNNING"->"下载中 · ${r.message}";"PAUSED"->"已暂停 · ${r.message}";"FAILED"->"下载失败 · ${r.message}";"CANCELLED"->"已取消";else->null}
    }
    fun canManageOffline(book:Book)=publicSource(book)!=null || offlineRuntime!=null && Regex("bookhub-v1-[a-f0-9]{32}").matches(book.sourceId) && book.sourceId in shelfIndex.sources()
    fun sourceCaption(book:Book)=(publicSource(book)?.label?:"书源")+if(publicSource(book)is LegadoSource)" · 基础规则"else" · 公开源"
    fun isPublicBook(book:Book)=publicSource(book)!=null
    fun downloadsAvailable(book:Book)=publicSource(book)!=null || offlineRuntime!=null && source is BookHubSource && sessionAvailable && book.format in listOf("TXT","EPUB") && (book.sourceId.isBlank() || book.sourceId==source.id)
    fun downloadLabel(book:Book)=state.value.downloadLabels[tagged(book).key]?:"未下载"
    fun downloadBusy(book:Book)=publicDownloads[book.key]?.isActive==true || (publicSource(book)!=null&&autoSources.busy(book.sourceId,book.id)) || canManageOffline(book) && offlineRuntime?.busy(book.sourceId,book.id)==true
    fun download(book:Book,allowMetered:Boolean=false) {
        publicSource(book)?.let { provider->
            if(cleanupPending(book) || publicDownloads[book.key]?.isActive==true || autoSources.busy(book.sourceId,book.id))return
            autoSources.cancel(book.sourceId,book.id)
            val token=Any();publicDownloadTokens[book.key]=token
            publicLabels[book.key]="下载中"
            publicDownloads[book.key]=viewModelScope.launch {try{provider.download(book.id,allowMetered);if(publicDownloadTokens[book.key]===token)publicLabels.remove(book.key)}catch(e:CancellationException){if(publicDownloadTokens[book.key]===token && !cleanupPending(book))publicLabels[book.key]="已取消";throw e}catch(e:Exception){if(publicDownloadTokens[book.key]===token){publicLabels[book.key]="下载失败";state.update{it.copy(notice=safeError(e))}}}finally{state.update{it.copy(downloadRevision=it.downloadRevision+1)};OfflineEvents.changed()}}
            state.update{it.copy(downloadRevision=it.downloadRevision+1)};return
        }
        if(cleanupPending(book)){state.update{it.copy(notice="本书清理尚未完成，请完成清理后再下载。")};return}
        val accepted=try{!cleanupPending(book) && downloadsAvailable(book) && offlineRuntime?.enqueue(source.id,tagged(book),allowMetered)==true}catch(_:Exception){false}
        if(!accepted)
            state.update{it.copy(notice="请登录这本书所属的 BookHub 账号后下载。")}
        else state.update{it.copy(downloadRevision=it.downloadRevision+1)}
    }
    fun cancelDownload(book:Book) {
        if(publicSource(book)!=null){autoSources.cancel(book.sourceId,book.id);publicDownloadTokens.remove(book.key);publicDownloads.remove(book.key)?.cancel();publicLabels[book.key]="已取消";state.update{it.copy(downloadRevision=it.downloadRevision+1)};return}
        if(!canManageOffline(book))return
        val owner=book.sourceId
        viewModelScope.launch(Dispatchers.IO){try{offlineRuntime?.cancel(owner,book.id);state.update{it.copy(downloadRevision=it.downloadRevision+1)}}catch(_:Exception){state.update{it.copy(notice="取消任务未能完整保存，请重试；阅读进度保留。")}}}
    }
    fun clearDownload(book:Book) {
        if(!canManageOffline(book))return
        beginBookCleanup(book);refreshShelfOrder()
    }
    fun offlineSourceEnabled()=offlineRuntime!=null && source is BookHubSource && sessionAvailable
    private fun hiddenClearAllowed(book:Book,revision:Int)=offlineSourceEnabled() && revision==state.value.connectionRevision && book.sourceId==source.id && book.id in store.allHiddenShelfIds()
    fun clearHiddenDownload(book:Book,expectedConnectionRevision:Int) {
        if(hiddenClearAllowed(book,expectedConnectionRevision))beginBookCleanup(book)
    }
    fun automaticWifiOnly(value:Boolean){
        if(!globalDownloadPrefs.enabled())return
        if(globalDownloadPrefs.wifiOnly()==value)return
        globalDownloadPrefs.setWifiOnly(value)
        offlineRuntime?.networkPolicyChanged();autoSources.networkPolicyChanged()
        state.update{it.copy(automaticWifiOnly=value)}
    }
    fun automaticWifi(value:Boolean){globalDownloadPrefs.set(value);offlineRuntime?.setAutomaticWifi(value);if(value)autoSources.scheduleMore()else autoSources.cancelAutomatic();state.update{it.copy(automaticWifi=value)}}
    private fun refreshShelfOrder() {
        val shelf=combinedShelf();val keys=shelf.map{it.key}.toSet();val findKeys=knownShelfKeys()
        state.update { it.copy(shelfBooks=shelf,shelfSelection=it.shelfSelection.intersect(keys),localShelfWarning=shelfIndex.warning,books=it.books.filterNot{b->b.key in findKeys},shelfRevision=it.shelfRevision+1) }
    }
    /** Resolve exact current shelf keys, never titles/bare IDs. Queue identity and policy stay in OfflineDownloads. */
    fun downloadSelection(keys: Set<String>, expectedConnectionRevision: Int, allowMetered: Boolean = false) {
        if(expectedConnectionRevision!=state.value.connectionRevision)return
        state.value.shelfBooks.filter{it.key in keys && publicSource(it)!=null}.forEach{download(it,allowMetered)}
        val current=source as? BookHubSource?:return
        val runtime=offlineRuntime?:return
        val generation=sessionGeneration
        if(expectedConnectionRevision!=state.value.connectionRevision || !sessionAvailable)return
        val targets=state.value.shelfBooks.filter{it.key in keys && downloadsAvailable(it) && publicSource(it)==null}.distinctBy{it.key}
        val operations=targets.associate{it.key to (bookOperations[it.key]?:0L)}
        viewModelScope.launch(Dispatchers.IO) {
            var added=0;var existing=0;var failed=0
            for(book in targets) {
                if(generation!=sessionGeneration || source!==current || !sessionAvailable)return@launch
                try {
                    when(runtime.enqueueIfNeeded(current.id,book,allowMetered,stillWanted={generation==sessionGeneration && source===current && (bookOperations[book.key]?:0L)==operations[book.key] && !cleanupPending(book)})) {
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
        OfflineManagementSnapshot(totalBytes=GlobalOfflineUsage.bytes(GlobalOfflineUsage.roots(getApplication())),available=true)
    }
    suspend fun hiddenCopiesSnapshot():OfflineManagementSnapshot=withContext(Dispatchers.IO) {
        val current=source as? BookHubSource?:return@withContext OfflineManagementSnapshot()
        val runtime=offlineRuntime?:return@withContext OfflineManagementSnapshot()
        if(!sessionAvailable)return@withContext OfflineManagementSnapshot()
        val generation=sessionGeneration;val activeStore=store;val hidden=activeStore.allHiddenShelfIds()
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
    private fun cancelSourceJobs() { choiceJob?.cancel();choicePlan=null;loginComparisonActive=false;manualShelfSync?.cancel();membershipJobs.values.toList().forEach{it.cancel()};activeReadingSource=null;pending?.cancel(); clearSearch(); shelfJob?.cancel() }
    fun cancelConnection() { connection?.cancel(); state.update { it.copy(connecting = false, connectionError = null) } }
    fun signOut() {
        automaticShelfSync?.deactivate()
        retainOfflineNote("SIGNED_OUT")
        val destination=if(state.value.topTab=="settings") "settings" else "find"
        offlineRuntime?.deactivate()
        sessionGeneration++; connection?.cancel(); cancelSourceJobs(); sessionVault.clear(); client?.clearSession(); client = null
        source = localSource; store = localStore; sessionAvailable = true
        serviceShelf = store.savedServiceShelf(); freshServiceShelf = emptyList(); shelfCursor = null
        state.value = ReaderState(catalogId=selectedCatalog,catalogLabel=catalogName(selectedCatalog),publicCatalog=selectedCatalog!="bookhub",settings = state.value.settings, clearingBooks=state.value.clearingBooks,membershipBusy=state.value.membershipBusy, topTab = destination,retentionNote=OfflineRetention.NOTE,notice=OfflineRetention.NOTE, shelfBooks = combinedShelf(),localShelfWarning=shelfIndex.warning, connectionRevision = state.value.connectionRevision + 1,automaticWifi=globalDownloadPrefs.enabled(),automaticWifiOnly=globalDownloadPrefs.wifiOnly(),offline=offlineRuntime?.connection?.online==false)
        loadShelf()
    }

    /** User refresh is coalesced; existing entries stay visible until and after the result. */
    fun refreshShelf() { if(source is BookHubSource){syncShelfFromBookHub();return};if(!state.value.shelfLoading && shelfJob?.isActive!=true) {state.update{it.copy(shelfRevision=it.shelfRevision+1)};loadShelf()} }
    fun shelfUpdateRecord():ShelfUpdateRecord {
        val record=store.shelfUpdateRecord()
        return if(record.state=="RUNNING" && !state.value.syncingShelf)record.copy(state="INTERRUPTED") else record
    }
    fun bookHubConnectionLabel():String = if(bookHubConnected())"BookHub 已连接" else when(retentionPrefs.getString("reason",null)) {
        "EXPIRED"->"登录已失效，请重新连接后更新"
        "UNAVAILABLE"->"登录无法恢复，请重新连接后更新"
        "SIGNED_OUT"->"已退出 BookHub"
        else->"未连接 BookHub"
    }
    fun bookHubConnected()=source is BookHubSource && sessionAvailable
    /** Membership sync only. Never reads or writes remote reading-state endpoints. */
    fun syncShelfFromBookHub() {
        if(state.value.shelfChoiceBusy || manualShelfSync?.isActive==true || state.value.shelfLoading || state.value.membershipBusy.any{it.startsWith(source.id+"|")})return
        if(!bookHubConnected()){state.update{it.copy(notice="请先登录 BookHub")};return}
        val owner=source.id;val current=source;val generation=sessionGeneration;val target=store
        val previous=target.shelfUpdateRecord();val attempt=clock()
        if(!target.saveShelfUpdate(previous.copy(state="RUNNING",attemptAt=attempt,failure=""))){state.update{it.copy(notice="书架检查未开始，请重试")};return}
        state.update{it.copy(syncingShelf=true,syncRevision=it.syncRevision+1)}
        manualShelfSync=viewModelScope.launch {
            try {
                if(offlineRuntime?.connection?.online==false)throw SourceProblem(FailureKind.NETWORK,"当前离线")
                loadShelf();var pages=0
                do {
                    shelfJob?.join();coroutineContext.ensureActive()
                    if(source!==current || sessionGeneration!=generation)throw CancellationException()
                    shelfFailure?.let{throw it}
                    if(!state.value.shelfHasMore)break
                    if(++pages>=250)throw SourceProblem(FailureKind.PROTOCOL,"书架分页超过本次更新上限")
                    loadMoreShelf()
                }while(true)
                target.saveShelfUpdate(ShelfUpdateRecord("SUCCESS",attempt,clock(),freshServiceShelf.size))
            }catch(e:CancellationException){target.saveShelfUpdate(previous.copy(state="INTERRUPTED",attemptAt=attempt));throw e}
            catch(e:Exception){target.saveShelfUpdate(previous.copy(state="FAILED",attemptAt=attempt,failure=if(e is SourceProblem)e.kind.name else "OTHER"))}
            finally{if(source===current && sessionGeneration==generation)state.update{it.copy(syncingShelf=false,syncRevision=it.syncRevision+1)}}
        }
    }
    /** Empty query refreshes server/local shelf; nonempty keeps the previous public method compatible as a search. */
    fun loadShelf(query: String = "") {
        if (query.isNotBlank()) { search(query); return }
        if(source is SignedOutBookSource){val books=combinedShelf();state.update{it.copy(shelfBooks=books,localShelfWarning=shelfIndex.warning,shelfLoading=false,shelfError=null,shelfHasMore=false)};return}
        shelfJob?.cancel(); if(state.value.shelfChoicePending){choicePlan=null;state.update{it.copy(shelfDifference=null,showShelfChoice=false)}};shelfFailure=null; shelfCursors.clear(); shelfCursor = null; freshServiceShelf = emptyList()
        shelfMemberEpoch=membershipEpoch;shelfRuntimeEpoch=automaticShelfSync?.membershipEpoch?:0;shelfSourceContext=source;shelfSessionContext=sessionGeneration;shelfSnapshotSafe=true
        shelfPriorKnown=store.remoteKnownIds()-store.remoteRemovedIds()-store.hiddenShelfIds()-store.localOnlyIds()
        state.update { it.copy(shelfLoading = true, shelfError = null, shelfHasMore = false,shelfKnownComplete=false) }
        shelfJob = viewModelScope.launch {
            try {
                requireSession(); val page = source.browse("")
                coroutineContext.ensureActive();checkShelfContext()
                shelfSnapshotSafe=shelfSnapshotSafe && page.membershipSafe
                applyReadDenial(page.items,source.id)
                freshServiceShelf = page.items.map { tagged(it) }.distinctBy { it.id }
                if(!state.value.shelfChoicePending)serviceShelf = (freshServiceShelf + serviceShelf).distinctBy { it.id }
                shelfCursor = page.nextCursor
                coroutineContext.ensureActive(); if(!state.value.shelfChoicePending)store.rememberServiceShelf(serviceShelf)
                if(!page.hasMore)reconcileShelfSnapshot()
                val shelf=combinedShelf();val keys=shelf.map{it.key}.toSet();val findKeys=knownShelfKeys()
                state.update { it.copy(shelfBooks=shelf,shelfSelection=it.shelfSelection.intersect(keys),localShelfWarning=shelfIndex.warning,shelfLoading=false,shelfHasMore=page.hasMore,shelfKnownComplete=!page.hasMore && offlineRuntime?.connection?.online!=false,books=it.books.filterNot{b->b.key in findKeys}) }
                if(state.value.shelfChoicePending && loginComparisonActive && page.hasMore)loadMoreShelf()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                shelfFailure=e;val failureMessage=shelfFailureMessage(e);state.update { it.copy(shelfLoading = false, shelfKnownComplete=false,shelfError = failureMessage,shelfBooks=combinedShelf()) }
            }
        }
    }
    fun loadMoreShelf() {
        val s = state.value; val cursor = shelfCursor ?: return
        if (s.shelfLoading || !s.shelfHasMore) return
        shelfFailure=null;state.update { it.copy(shelfLoading = true, shelfError = null) }
        shelfJob = viewModelScope.launch {
            try {
                requireSession(); if(shelfCursors.size>=250)throw SourceProblem(FailureKind.PROTOCOL,"书架分页超过本次比较上限");val page = source.browse("", cursor)
                coroutineContext.ensureActive()
                if (page.nextCursor != null && page.nextCursor in shelfCursors) throw SourceProblem(FailureKind.PROTOCOL, "服务分页出现循环，请刷新书架")
                checkShelfContext()
                shelfSnapshotSafe=shelfSnapshotSafe && page.membershipSafe && freshServiceShelf.none{old->page.items.any{it.id==old.id}}
                applyReadDenial(page.items,source.id)
                shelfCursors += cursor
                freshServiceShelf = (freshServiceShelf + page.items.map { tagged(it) }).distinctBy { it.id }
                // Keep metadata through partial pages. Reconcile membership only after a verified full snapshot.
                if(!state.value.shelfChoicePending)serviceShelf = (freshServiceShelf + serviceShelf).distinctBy { it.id }
                shelfCursor = page.nextCursor
                coroutineContext.ensureActive(); if(!state.value.shelfChoicePending)store.rememberServiceShelf(serviceShelf)
                if(!page.hasMore)reconcileShelfSnapshot()
                val shelf=combinedShelf();val keys=shelf.map{it.key}.toSet();val findKeys=knownShelfKeys()
                state.update { it.copy(shelfBooks=shelf,shelfSelection=it.shelfSelection.intersect(keys),localShelfWarning=shelfIndex.warning,shelfLoading=false,shelfHasMore=page.hasMore,shelfKnownComplete=!page.hasMore && offlineRuntime?.connection?.online!=false,books=it.books.filterNot{b->b.key in findKeys}) }
                if(state.value.shelfChoicePending && loginComparisonActive && page.hasMore)loadMoreShelf()
            } catch (e: Exception) { if (e is CancellationException) throw e; shelfFailure=e;val failureMessage=shelfFailureMessage(e);state.update { it.copy(shelfLoading = false, shelfKnownComplete=false,shelfError = failureMessage,shelfBooks=combinedShelf()) } }
        }
    }
    private fun checkShelfContext() {
        if(source!==shelfSourceContext || sessionGeneration!=shelfSessionContext)throw CancellationException()
        if(linkBookHubShelf && source is BookHubSource && (membershipEpoch!=shelfMemberEpoch || (automaticShelfSync?.membershipEpoch?:0)!=shelfRuntimeEpoch))
            throw SourceProblem(FailureKind.VERSION,"书架操作期间名单已变化，请重新刷新；未应用服务端移出",diagnostic="BH_SHELF_SNAPSHOT_CHANGED")
    }
    private suspend fun reconcileShelfSnapshot() {
        if(!linkBookHubShelf || source !is BookHubSource)return
        if(offlineRuntime?.connection?.online==false)throw SourceProblem(FailureKind.NETWORK,"当前离线，尚未取得远端书架快照")
        checkShelfContext()
        if(!shelfSnapshotSafe || state.value.membershipBusy.any{it.startsWith(source.id+"|")})
            throw SourceProblem(FailureKind.VERSION,"书架分页或操作状态不稳定，请重新刷新；未应用服务端移出",diagnostic="BH_SHELF_SNAPSHOT_CHANGED")
        if(state.value.shelfChoicePending){if(loginComparisonActive && !loginPromptShown)prepareShelfDifference();return}
        val activeClient=client?:throw SourceProblem(FailureKind.AUTH,"请恢复原账号登录")
        val access=activeClient.snapshot()?.accessToken?:throw SourceProblem(FailureKind.AUTH,"请恢复原账号登录")
        val missing=shelfPriorKnown-freshServiceShelf.map{it.id}.toSet()-pendingIntentIds()
        if(missing.size>100)throw SourceProblem(FailureKind.PROTOCOL,"待核对的服务端移出超过 100 本，本次未批量调整；请在原版核对后重试",diagnostic="BH_SHELF_RECONCILE_LIMIT")
        val details=mutableListOf<Book>()
        for(id in missing) {
            val detail=activeClient.shelfDetail(id,verifyAccount=false);checkShelfContext()
            if(detail.present)throw SourceProblem(FailureKind.VERSION,"书架清单与逐书状态不一致，请重新刷新；未应用服务端移出",diagnostic="BH_SHELF_SNAPSHOT_CHANGED")
            details+=detail.book.copy(sourceId=source.id)
        }
        if(missing.isNotEmpty())activeClient.verifyShelfAccount()
        checkShelfContext()
        if(client!==activeClient || !sessionAvailable || activeClient.snapshot()?.accessToken!=access)
            throw SourceProblem(FailureKind.VERSION,"核对期间登录状态变化，请重新刷新；未应用服务端移出",diagnostic="BH_SHELF_CONTEXT_CHANGED")
        if(!store.acceptRemoteSnapshot(freshServiceShelf,missing))throw java.io.IOException("书架名单未能保存")
        applyReadDenial(details,source.id)
        serviceShelf=store.savedServiceShelf()
    }
    fun postponeShelfDifference(){
        loginComparisonActive=false;loginPromptShown=true;choicePlan=null
        state.update{it.copy(showShelfChoice=false,shelfDifference=null)}
    }
    private fun shelfFailureMessage(e:Exception):String? {
        val silent=automaticShelfSync!=null && e is SourceProblem && e.kind in listOf(FailureKind.NETWORK,FailureKind.VERSION,FailureKind.PREPARING,FailureKind.RATE_LIMIT)
        if(loginComparisonActive){
            loginComparisonActive=false
            val message=when {
                e is SourceProblem && e.kind==FailureKind.AUTH->"登录已失效，请重新登录"
                e is SourceProblem && e.diagnostic=="BH_SHELF_CHOICE_LIMIT"->"书架差异超过 100 本，暂不能合并"
                else->"书架检查失败，请重新登录后重试"
            }
            if(!silent)state.update{it.copy(notice=message)}
        }
        return if(silent)null else if(linkBookHubShelf && source is BookHubSource)"书架加载失败，请重试" else safeError(e)
    }
    private fun knownShelfKeys():Set<String> = combinedShelf().map{it.key}.toSet()+
        if(source is BookHubSource && state.value.shelfChoicePending)freshServiceShelf.filterNot{it.id in store.allHiddenShelfIds()}.map{it.key}.toSet() else emptySet()
    private fun pendingIntentIds()=automaticShelfSync?.intents?.all()?.filter{it.owner==source.id && it.pending}?.map{it.book.id}?.toSet().orEmpty()
    private fun ownerVisibleKeys(owner:String)=combinedShelf().filter{it.sourceId==owner}.map{it.id}.toSet()
    private suspend fun prepareShelfDifference() {
        val current=source;val activeClient=client?:throw SourceProblem(FailureKind.AUTH,"请恢复原账号登录")
        val token=activeClient.snapshot()?.accessToken?:throw SourceProblem(FailureKind.AUTH,"请恢复原账号登录")
        val pendingIds=pendingIntentIds()
        val local=combinedShelf().filter{it.sourceId==current.id && it.id !in pendingIds};val localIds=local.map{it.id}.toSet()
        val remoteIds=freshServiceShelf.map{it.id}.toSet();val missingLocal=freshServiceShelf.filter{it.id !in localIds && it.id !in pendingIds};val missingRemote=local.filter{it.id !in remoteIds}
        if(missingLocal.size+missingRemote.size>100)throw SourceProblem(FailureKind.PROTOCOL,"本次书架差异超过 100 本，未应用任何覆盖；请分批在原版整理后再核对",diagnostic="BH_SHELF_CHOICE_LIMIT")
        val details=linkedMapOf<String,ShelfMembershipDetail>();val skipped=mutableListOf<Book>()
        for(book in missingLocal+missingRemote) {
            try {
                val detail=activeClient.shelfDetail(book.id,verifyAccount=false);checkShelfContext()
                if(detail.present!=(book.id in remoteIds))throw SourceProblem(FailureKind.VERSION,"比较期间书架已变化，请重新同步",diagnostic="BH_SHELF_SNAPSHOT_CHANGED")
                details[book.id]=detail
            }catch(e:SourceProblem) {
                if(book.id !in remoteIds && (e.httpStatus==404 || e.kind==FailureKind.FORBIDDEN)){skipped+=book;continue}
                throw e
            }
        }
        activeClient.verifyShelfAccount();checkShelfContext()
        if(client!==activeClient || activeClient.snapshot()?.accessToken!=token || (ownerVisibleKeys(current.id)-pendingIds)!=localIds)throw SourceProblem(FailureKind.VERSION,"比较期间账号或本机名单变化，请重新同步")
        loginComparisonActive=false;loginPromptShown=true
        if(missingLocal.isEmpty() && missingRemote.isEmpty()) {
            if(!store.acceptShelfChoice(freshServiceShelf,emptySet()))throw java.io.IOException()
            serviceShelf=store.savedServiceShelf();state.update{it.copy(shelfChoicePending=false,shelfDifference=null,showShelfChoice=false)};return
        }
        val view=ShelfDifference(++choiceToken,missingRemote.filter{it.id in details}.map{details.getValue(it.id).book.copy(sourceId=current.id)},missingLocal,skipped,localIds)
        choicePlan=ShelfChoicePlan(view,freshServiceShelf.toList(),details,current.id,sessionGeneration,membershipEpoch,automaticShelfSync?.membershipEpoch?:0)
        state.update{it.copy(shelfDifference=view,showShelfChoice=true)}
    }
    /** Direction and destructive remote membership changes are explicitly confirmed by the UI. */
    fun chooseShelfDifference(token:Long,localWins:Boolean) {
        val plan=choicePlan?:return
        if(plan.view.token!=token || state.value.shelfChoiceBusy || !state.value.shelfChoicePending)return
        val current=source;val activeClient=client?:return;val generation=sessionGeneration;val target=store
        fun valid()=source===current && client===activeClient && sessionGeneration==generation && sessionAvailable &&
            membershipEpoch==plan.epoch && (automaticShelfSync?.membershipEpoch?:0)==plan.runtimeEpoch && (ownerVisibleKeys(plan.owner)-pendingIntentIds())==plan.view.localKeys
        if(!valid()){choicePlan=null;state.update{it.copy(showShelfChoice=false,shelfDifference=null,notice="书架已变化，请重新登录后核对")};return}
        state.update{it.copy(shelfChoiceBusy=true,showShelfChoice=false)}
        choiceJob=viewModelScope.launch {
            var succeeded=0;var failed=0;var remoteCount=plan.remote.size
            try {
                automaticShelfSync?.mutex?.lock()
                membershipMutex.lock()
                try {
                    // Re-read all pages and individual revisions before any write; never execute an old preview.
                    val fresh=readCompleteShelf(current)
                    if(!valid() || fresh.map{it.id}.toSet()!=plan.remote.map{it.id}.toSet())throw SourceProblem(FailureKind.VERSION,"名单已变化，请重新比较确认")
                    for((id,old) in plan.details) {
                        val now=activeClient.shelfDetail(id,verifyAccount=false)
                        if(!valid() || now.present!=old.present || now.revision!=old.revision)throw SourceProblem(FailureKind.VERSION,"书架修订已变化，请重新比较确认")
                    }
                    if(!localWins) {
                        val retained=plan.view.localKeys-fresh.map{it.id}.toSet()
                        if(!target.acceptShelfChoice(fresh,retained))throw java.io.IOException()
                    }else {
                        for((book,present) in plan.view.addRemote.map{it to true}+plan.view.removeRemote.map{it to false}) {
                            coroutineContext.ensureActive();if(!valid())throw CancellationException()
                            try {
                                activeClient.changeShelf(book.id,present,plan.details.getValue(book.id).revision)
                                if(!valid())throw CancellationException()
                                // Local-wins never clears content/progress: removed remote items were absent locally.
                                if(present){if(!target.addToShelf(book,remoteConfirmed=true))throw java.io.IOException()}
                                else if(!target.recordRemoteAbsent(book.id))throw java.io.IOException()
                                succeeded++
                            }catch(e:Exception){if(e is CancellationException || (e is SourceProblem && e.kind==FailureKind.AUTH))throw e;failed++}
                        }
                        if(failed>0)throw SourceProblem(FailureKind.VERSION,"已确认 $succeeded 项，另有 $failed 项未确认；未自动重试，请重新比较")
                        val after=readCompleteShelf(current);remoteCount=after.size
                        if(!valid())throw CancellationException()
                        val expected=(plan.remote.map{it.id}.toSet()+plan.view.addRemote.map{it.id})-plan.view.removeRemote.map{it.id}.toSet()
                        if(after.map{it.id}.toSet()!=expected)throw SourceProblem(FailureKind.VERSION,"提交后服务名单又有变化，请重新比较；未报告全部完成")
                        if(!target.acceptShelfChoice(after,plan.view.localKeys-after.map{it.id}.toSet()))throw java.io.IOException()
                    }
                    choicePlan=null;membershipEpoch++;serviceShelf=target.savedServiceShelf();target.saveShelfUpdate(ShelfUpdateRecord("SUCCESS",clock(),clock(),remoteCount));refreshShelfOrder()
                    state.update{it.copy(shelfChoicePending=false,shelfDifference=null,shelfKnownComplete=true,membershipFeedback=if(localWins && plan.view.skipped.isNotEmpty())"已跳过 ${plan.view.skipped.size} 本不可识别的书" else null)}
                }finally{membershipMutex.unlock();automaticShelfSync?.mutex?.unlock()}
            }catch(e:Exception) {
                if(source===current && generation==sessionGeneration) {
                    target.saveShelfUpdate(target.shelfUpdateRecord().copy(state="FAILED",attemptAt=clock(),failure=if(e is SourceProblem)e.kind.name else "OTHER"))
                    choicePlan=null
                    state.update{it.copy(shelfDifference=null,shelfKnownComplete=false,notice=if(e is SourceProblem && e.kind==FailureKind.AUTH)"登录已失效，请重新登录" else "同步未全部完成：已确认 $succeeded 项。请重新登录后核对")}
                }
                if(e is CancellationException)throw e
            }finally{if(source===current && generation==sessionGeneration)state.update{it.copy(shelfChoiceBusy=false)}}
        }
    }
    private suspend fun readCompleteShelf(current:BookSource):List<Book> {
        if(offlineRuntime?.connection?.online==false)throw SourceProblem(FailureKind.NETWORK,"当前离线，不能确认远端名单")
        val contextClient=client;val contextGeneration=sessionGeneration
        val result=mutableListOf<Book>();val seen=mutableSetOf<String>();var cursor:String?=null
        repeat(250) {
            if(source!==current || client!==contextClient || sessionGeneration!=contextGeneration || !sessionAvailable)throw CancellationException()
            val page=current.browse("",cursor)
            if(offlineRuntime?.connection?.online==false)throw SourceProblem(FailureKind.NETWORK,"网络已断开，未取得完整名单")
            coroutineContext.ensureActive()
            if(source!==current || client!==contextClient || sessionGeneration!=contextGeneration || !sessionAvailable)throw CancellationException()
            if(!page.membershipSafe || page.items.any{b->result.any{it.id==b.id}})throw SourceProblem(FailureKind.PROTOCOL,"书架分页重复，未执行覆盖")
            result+=page.items.map{tagged(it,current.id)}
            if(!page.hasMore)return result
            val next=page.nextCursor?:throw SourceProblem(FailureKind.PROTOCOL,"分页缺少游标")
            if(!seen.add(next))throw SourceProblem(FailureKind.PROTOCOL,"书架分页循环")
            cursor=next
        }
        throw SourceProblem(FailureKind.PROTOCOL,"书架分页超过上限")
    }
    /** Editing/clearing invalidates pending results; blank input never browses the service shelf. */
    fun clearSearch() {
        searchGeneration++;searchJob?.cancel();cursors.clear();findRetry={}
        effectiveFindTerm=null
        state.update{it.copy(catalogNote=null,catalogSections=emptyList(),catalogCanBack=false,catalogBrowsing=false,query="",books=emptyList(),nextCursor=null,hasMore=false,findLoading=false,findError=null,error=if(it.book==null)null else it.error)}
    }
    private fun searchItems(items:List<Book>,owner:String):List<Book> {
        val shelfKeys=knownShelfKeys()
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
    fun search(query: String){catalogPath=null;catalogHistory.clear();searchCatalog(query,false)}
    private fun searchCatalog(query:String,browsing:Boolean) {
        clearSearch()
        val term=query.trim();if(term.isEmpty() && !browsing)return
        val request=searchGeneration;val generation=sessionGeneration;val current=findSource()
        state.update { it.copy(findLoading=true,query=term,catalogBrowsing=browsing,catalogCanBack=catalogHistory.isNotEmpty()) }
        findRetry={searchCatalog(term,browsing)}
        searchJob=viewModelScope.launch {
            try {
                if(current===source)requireSession()
                var actualTerm=term
                var page=current.browse(term,if(browsing)catalogPath?.let{"nav:$it"}else null);coroutineContext.ensureActive()
                fun validContext()=request==searchGeneration && generation==sessionGeneration && findSource()===current
                if(!validContext())return@launch
                // Keep the source's original result/order/cursor. Only a verified empty success can try spelling variants.
                if(!browsing && term.isNotBlank() && page.items.isEmpty() && !page.hasMore && page.nextCursor==null && page.sections.isEmpty()) {
                    val variants=withContext(Dispatchers.Default){app.leafreader.text.ChineseDisplay.searchVariants(getApplication(),term)}
                    for(variant in variants.drop(1)) {
                        if(!validContext())return@launch
                        page=current.browse(variant,null);coroutineContext.ensureActive()
                        if(!validContext())return@launch
                        actualTerm=variant
                        if(page.items.isNotEmpty() || page.hasMore || page.nextCursor!=null || page.sections.isNotEmpty())break
                    }
                }
                effectiveFindTerm=actualTerm
                applyReadDenial(page.items,current.id)
                state.update{it.copy(catalogNote=page.catalogNote,catalogSections=page.sections,books=searchItems(page.items,current.id),nextCursor=page.nextCursor,hasMore=page.hasMore,findLoading=false)}
                // Membership discovery continues after rendering; uncertain results remain non-addable.
                try {if(current===source)completeShelfForSearch(current,request,generation)} catch(e:CancellationException){throw e} catch(e:Exception){if(request==searchGeneration && generation==sessionGeneration)state.update{it.copy(findError=safeError(e),error=if(it.book==null)safeError(e) else it.error)}}
            } catch(e:Exception) {
                if(e is CancellationException)throw e
                if(request==searchGeneration && generation==sessionGeneration)state.update{it.copy(findLoading=false,findError=safeError(e),error=if(it.book==null)safeError(e) else it.error)}
            }
        }
    }
    fun loadMore() {
        val s=state.value;val cursor=s.nextCursor?:return
        if((s.query.isBlank() && !s.catalogBrowsing) || s.findLoading || !s.hasMore) { return }
        val request=searchGeneration;val generation=sessionGeneration;val current=findSource()
        findRetry={loadMore()};state.update{it.copy(findLoading=true,findError=null,error=if(it.book==null)null else it.error)}
        searchJob=viewModelScope.launch {
            try {
                if(current===source)requireSession()
                val page=current.browse(effectiveFindTerm?:s.query,cursor)
                coroutineContext.ensureActive()
                if(request!=searchGeneration || generation!=sessionGeneration || findSource()!==current) { return@launch }
                if(page.nextCursor!=null && (page.nextCursor==cursor || page.nextCursor in cursors))throw SourceProblem(FailureKind.PROTOCOL,"服务分页出现循环，请重新搜索")
                applyReadDenial(page.items,current.id)
                cursors+=cursor
                state.update{it.copy(catalogNote=page.catalogNote,catalogSections=(it.catalogSections+page.sections).distinctBy{section->section.href},books=searchItems(it.books+page.items,current.id),nextCursor=page.nextCursor,hasMore=page.hasMore,findLoading=false)}
                // Membership discovery continues after rendering; uncertain results remain non-addable.
                try {if(current===source)completeShelfForSearch(current,request,generation)} catch(e:CancellationException){throw e} catch(e:Exception){if(request==searchGeneration && generation==sessionGeneration)state.update{it.copy(findError=safeError(e),error=if(it.book==null)safeError(e) else it.error)}}
            } catch(e:Exception) {
                if(e is CancellationException)throw e
                if(request==searchGeneration && generation==sessionGeneration)state.update{it.copy(findLoading=false,findError=safeError(e),error=if(it.book==null)safeError(e) else it.error)}
            }
        }
    }

    /** All Read/Continue actions use this route; a saved invalid locator is an error, never a silent first chapter. */
    fun open(book: Book, chapterId: String? = null, fromStart:Boolean=false) {
        if(cleanupPending(book)){state.update{it.copy(notice="本书正在清理，请完成清理后再阅读。")};return}
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
    fun allowPageTurn():Boolean {
        if(state.value.loading || state.value.error!=null)return false
        val b=state.value.book?:return false
        return try{sourceFor(b);true}catch(e:Exception){fail(e);false}
    }
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
