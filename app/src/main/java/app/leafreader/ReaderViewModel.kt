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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import app.leafreader.covers.BookCoverRepository
import java.io.File

data class ReaderState(
    val books: List<Book> = emptyList(), val book: Book? = null,
    val chapters: List<ChapterRef> = emptyList(), val chapter: Chapter? = null,
    val offset: Int = 0, val revision: Int = 0, val settings: ReaderSettings = ReaderSettings(),
    val loading: Boolean = false, val error: String? = null, val notice: String? = null,
    val shelfRevision: Int = 0, val query: String = "", val nextCursor: String? = null, val hasMore: Boolean = false,
    val remote: Boolean = false, val sourceLabel: String = "BookHub · 未登录", val connecting: Boolean = false,
    val connectionError: String? = null, val connectionRevision: Int = 0,
    val topTab: String = "shelf", val shelfBooks: List<Book> = emptyList(), val shelfLoading: Boolean = true,
    val shelfError: String? = null, val shelfHasMore: Boolean = false, val findLoading: Boolean = false,
    val chapterLengths: List<Int>? = null, val chaptersComplete: Boolean = false, val graphicId: String? = null,
    val downloadLabels:Map<String,String> = emptyMap(),val automaticWifi:Boolean=true,val offline:Boolean=false,
    val shelfManaging:Boolean=false,val shelfSelection:Set<String> = emptySet(),
    val downloadRevision:Int=0
)

@android.annotation.SuppressLint("ApplySharedPref") // Complete one-time layout migration before switching accounts.
class ReaderViewModel @JvmOverloads constructor(application: Application,
    private var source: BookSource = SignedOutBookSource(),
    private val connectionGate: BookHubConnectionGate = BookHubConnectionGate(),
    private val sessionVault: SessionVault = EncryptedSessionVault(application), private val offlineRuntime:OfflineDownloads?=null,
    private val clock: () -> Long = { System.currentTimeMillis() }) : AndroidViewModel(application) {
    private val localSource = source
    private val localStore = ReadingStore(application, localSource.id)
    var store = localStore; private set
    private val layoutStore = ReadingStore(application, "layout-global-v1")
    private val layoutPrefs = application.getSharedPreferences("reader-layout-migration", Application.MODE_PRIVATE)
    private val initialSettings = if (layoutPrefs.getBoolean("done", false)) layoutStore.settings() else store.settings().also {
        layoutStore.saveSettings(it); layoutPrefs.edit().putBoolean("done", true).commit()
    }
    @Volatile private var sessionAvailable = true
    private val state = MutableStateFlow(ReaderState(settings = initialSettings, shelfBooks = store.orderedShelf().filterNot(RetiredBundledBooks::contains),automaticWifi=offlineRuntime?.automaticWifi()?:true,offline=offlineRuntime?.connection?.online==false))
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

    init {
        if (!restoreSavedSession()) loadShelf()
        if(offlineRuntime!=null)viewModelScope.launch {
            combine(OfflineEvents.revision,state.map{(it.shelfBooks+it.books).distinctBy{b->b.key}}.distinctUntilChanged()){_,books->books}.collectLatest {books->
                delay(100)
                val current=source as? BookHubSource
                val labels=withContext(Dispatchers.IO){books.filter{it.sourceId==current?.id}.associate{it.key to try{offlineRuntime.label(it.sourceId,it.id)}catch(_:Exception){"离线副本暂不可用，可重试"}}}
                state.update{it.copy(downloadLabels=labels,automaticWifi=offlineRuntime.automaticWifi(),offline=!offlineRuntime.connection.online,downloadRevision=it.downloadRevision+1)}
            }
        }
    }
    private fun attachClient(next: BookHubClient, tab: String, persist: Boolean) {
        client = next; source = offlineRuntime?.source(next)?:BookHubSource(next); store = ReadingStore(getApplication(), source.id); sessionAvailable = true
        serviceShelf = store.savedServiceShelf(); freshServiceShelf = emptyList(); shelfCursor = null
        val generation = sessionGeneration
        next.onSessionChanged = { snapshot ->
            if (client === next && generation == sessionGeneration) {
                if (snapshot == null) {
                    offlineRuntime?.deactivate()
                    sessionAvailable = false; sessionVault.clear()
                    state.update { it.copy(loading = false, shelfLoading = false, findLoading = false,
                        error = if (it.book == null) it.error else "BookHub 登录已失效，请重新登录；原进度已保留。\n诊断：BH_AUTH",
                        notice = "BookHub 登录已失效，请重新登录；本机书架和进度已保留。") }
                } else if (!sessionVault.save(snapshot)) {
                    state.update { it.copy(notice = "设备无法安全保存登录；当前会话仍可用，重启后需要重新登录。密码不会保存。") }
                }
            }
        }
        offlineRuntime?.activate(next)
        serverPrefs.edit().putString("origin", next.origin.value).apply()
        state.value = ReaderState(settings = state.value.settings, remote = true, sourceLabel = "BookHub · ${next.origin.uri.host}",
            topTab = tab, shelfBooks = combinedShelf(), connectionRevision = state.value.connectionRevision + 1,automaticWifi=offlineRuntime?.automaticWifi()?:true,offline=offlineRuntime?.connection?.online==false)
        if (persist) next.snapshot()?.let { next.onSessionChanged?.invoke(it) }
        loadShelf()
    }
    private fun restoreSavedSession(): Boolean = when (val saved = sessionVault.load()) {
        is SessionLoad.Ready -> try { attachClient(connectionGate.restore(saved.session), "shelf", false); true }
            catch (_: Exception) { sessionVault.clear(); state.update { it.copy(notice = "保存的登录无法恢复，请重新登录；本机书架和进度已保留。") }; false }
        SessionLoad.Unavailable -> { state.update { it.copy(notice = "设备无法解密保存的登录，请重新登录；本机书架和进度已保留。") }; false }
        SessionLoad.Empty -> false
    }
    private fun tagged(book: Book, sourceId: String = source.id) = book.copy(sourceId = book.sourceId.ifBlank { sourceId })
    private fun storeFor(book: Book): ReadingStore = if (book.sourceId == localSource.id) localStore else if (book.sourceId.isBlank() || book.sourceId == source.id) store else ReadingStore(getApplication(), book.sourceId)
    private fun sourceFor(book: Book): BookSource {
        if (book.sourceId == localSource.id) return localSource
        if ((book.sourceId.isBlank() || book.sourceId == source.id) && (source.id == localSource.id || sessionAvailable)) return source
        throw SourceProblem(FailureKind.AUTH, "请重新登录这本书所属的 BookHub 账号；原书架和进度已保留")
    }
    fun positionOf(book: Book) = storeFor(book).position(book.id)
    suspend fun shelfCover(book:Book):android.graphics.Bitmap? {
        val current=source as? BookHubSource?:return null
        val generation=sessionGeneration
        if(book.format!="EPUB" || book.sourceId!=current.id || !sessionAvailable)return null
        return try { withContext(Dispatchers.IO){covers.load(current,book.id,"$generation:${state.value.shelfRevision}"){source===current && sessionGeneration==generation && sessionAvailable}} }
        catch(e:CancellationException){throw e}catch(_:Exception){null}
    }
    fun isLocalBook(book: Book) = book.sourceId == localSource.id || (book.sourceId.isBlank() && source.id == localSource.id)
    suspend fun chapterList(book: Book) = sourceFor(book).chapters(book.id)
    suspend fun chapterList(bookId: String) = chapterList(Book(bookId, "", "", "", "", 0, 0))
    private fun combinedShelf(): List<Book> {
        val local = localStore.orderedShelf()
        val remote = if (store.sourceId == localStore.sourceId) emptyList() else store.orderedShelf(serviceShelf)
        return (local + remote).filterNot(RetiredBundledBooks::contains).distinctBy { it.key }.sortedWith(compareByDescending<Book> { storeFor(it).position(it.id) != null || storeFor(it).lastEnteredAt(it.id) > 0 }
            .thenByDescending { storeFor(it).lastEnteredAt(it.id) })
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
        if(owned.sourceId!=localSource.id && (owned.sourceId!=source.id || !sessionAvailable)) return false
        val added=!isOnShelf(owned)
        if(!storeFor(owned).addToShelf(owned)){state.update{it.copy(notice="加入书架未能完整保存，请重试；原记录保留。")};return false}; refreshShelfOrder()
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
        val current=source as? BookHubSource?:return OfflineStatus()
        val generation=sessionGeneration
        if(book.sourceId!=current.id)return OfflineStatus()
        val checked=withContext(Dispatchers.IO){runtime.cache(current.id).status(book.id,verify=true)}
        if(generation!=sessionGeneration || source!==current)throw CancellationException()
        return checked
    }
    fun downloadTaskLabel(book:Book):String? {
        if(book.sourceId!=source.id)return null
        val r=offlineRuntime?.request(offlineRuntime.key(book.sourceId,book.id))?:return null
        return when(r.state){"QUEUED"->"已排队 · ${r.message}";"WAITING"->"等待下载 · ${r.message}";"RUNNING"->"下载中 · ${r.message}";"PAUSED"->"已暂停 · ${r.message}";"FAILED"->"下载失败 · ${r.message}";"CANCELLED"->"已取消";else->null}
    }
    fun downloadsAvailable(book:Book)=offlineRuntime!=null && source is BookHubSource && sessionAvailable && book.format in listOf("TXT","EPUB") && (book.sourceId.isBlank() || book.sourceId==source.id)
    fun downloadLabel(book:Book)=state.value.downloadLabels[tagged(book).key]?:"未下载"
    fun downloadBusy(book:Book)=offlineRuntime?.busy(source.id,book.id)==true && downloadsAvailable(book)
    fun download(book:Book,allowMetered:Boolean=false) {
        val accepted=try{downloadsAvailable(book) && offlineRuntime?.enqueue(source.id,tagged(book),allowMetered)==true}catch(_:Exception){false}
        if(!accepted)
            state.update{it.copy(notice="请登录这本书所属的 BookHub 账号后下载。")}
        else state.update{it.copy(downloadRevision=it.downloadRevision+1)}
    }
    fun cancelDownload(book:Book) {
        if(!downloadsAvailable(book))return
        val owner=source.id
        viewModelScope.launch(Dispatchers.IO){try{offlineRuntime?.cancel(owner,book.id);state.update{it.copy(downloadRevision=it.downloadRevision+1)}}catch(_:Exception){state.update{it.copy(notice="取消任务未能完整保存，请重试；阅读进度保留。")}}}
    }
    fun clearDownload(book:Book) {
        if(!downloadsAvailable(book))return
        val current=source as BookHubSource
        viewModelScope.launch(Dispatchers.IO){try{offlineRuntime?.cancel(current.id,book.id);current.forgetDownloaded(book.id);covers.clear(current.id,book.id)}catch(_:Exception){state.update{it.copy(notice="清除副本未完成，请重试；书架、进度和登录已保留。")}}}
    }
    fun automaticWifi(value:Boolean){offlineRuntime?.setAutomaticWifi(value);state.update{it.copy(automaticWifi=value)}}
    private fun refreshShelfOrder() {
        val shelf=combinedShelf();val keys=shelf.map{it.key}.toSet()
        state.update { it.copy(shelfBooks=shelf,books=it.books.filterNot{b->b.key in keys},shelfRevision=it.shelfRevision+1) }
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
        clearRemoteForServerSelection()
        state.update { it.copy(connecting = true, connectionError = null) }
        connection = viewModelScope.launch {
            try {
                val next = connectionGate.login(origin, username, password)
                coroutineContext.ensureActive()
                cancelSourceJobs(); attachClient(next, destination, true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.update { it.copy(connecting = false, connectionError = safeError(e)) }
            }
        }
    }
    private fun cancelSourceJobs() { pending?.cancel(); clearSearch(); shelfJob?.cancel() }
    fun cancelConnection() { connection?.cancel(); state.update { it.copy(connecting = false, connectionError = null) } }
    fun signOut() {
        val destination=if(state.value.topTab=="settings") "settings" else "find"
        offlineRuntime?.deactivate()
        sessionGeneration++; connection?.cancel(); cancelSourceJobs(); sessionVault.clear(); client?.clearSession(); client = null
        source = localSource; store = localStore; sessionAvailable = true
        serviceShelf = store.savedServiceShelf(); freshServiceShelf = emptyList(); shelfCursor = null
        state.value = ReaderState(settings = state.value.settings, topTab = destination, shelfBooks = combinedShelf(), connectionRevision = state.value.connectionRevision + 1,automaticWifi=offlineRuntime?.automaticWifi()?:true,offline=offlineRuntime?.connection?.online==false)
        loadShelf()
    }

    /** User refresh is coalesced; existing entries stay visible until and after the result. */
    fun refreshShelf() { if(!state.value.shelfLoading && shelfJob?.isActive!=true) {state.update{it.copy(shelfRevision=it.shelfRevision+1)};loadShelf()} }
    /** Empty query refreshes server/local shelf; nonempty keeps the previous public method compatible as a search. */
    fun loadShelf(query: String = "") {
        if (query.isNotBlank()) { search(query); return }
        shelfJob?.cancel(); shelfFailure=null; shelfCursors.clear(); shelfCursor = null; freshServiceShelf = emptyList()
        state.update { it.copy(shelfLoading = true, shelfError = null, shelfHasMore = false) }
        retry = { loadShelf() }
        shelfJob = viewModelScope.launch {
            try {
                requireSession(); val page = source.browse("")
                coroutineContext.ensureActive()
                freshServiceShelf = page.items.map { tagged(it) }.distinctBy { it.id }
                serviceShelf = (freshServiceShelf + serviceShelf).distinctBy { it.id }
                shelfCursor = page.nextCursor
                coroutineContext.ensureActive(); store.rememberServiceShelf(serviceShelf)
                val shelf=combinedShelf();val keys=shelf.map{it.key}.toSet()
                state.update { it.copy(shelfBooks=shelf,shelfLoading=false,shelfHasMore=page.hasMore,books=it.books.filterNot{b->b.key in keys}) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                shelfFailure=e;state.update { it.copy(shelfLoading = false, shelfError = safeError(e)) }
            }
        }
    }
    fun loadMoreShelf() {
        val s = state.value; val cursor = shelfCursor ?: return
        if (s.shelfLoading || !s.shelfHasMore) return
        shelfFailure=null;state.update { it.copy(shelfLoading = true, shelfError = null) }; retry = { loadMoreShelf() }
        shelfJob = viewModelScope.launch {
            try {
                requireSession(); val page = source.browse("", cursor)
                coroutineContext.ensureActive()
                if (page.nextCursor != null && page.nextCursor in shelfCursors) throw SourceProblem(FailureKind.PROTOCOL, "服务分页出现循环，请刷新书架")
                shelfCursors += cursor
                freshServiceShelf = (freshServiceShelf + page.items.map { tagged(it) }).distinctBy { it.id }
                // Server absence is not a local delete operation. Keep cached books and explicit additions.
                serviceShelf = (freshServiceShelf + serviceShelf).distinctBy { it.id }
                shelfCursor = page.nextCursor
                coroutineContext.ensureActive(); store.rememberServiceShelf(serviceShelf)
                val shelf=combinedShelf();val keys=shelf.map{it.key}.toSet()
                state.update { it.copy(shelfBooks=shelf,shelfLoading=false,shelfHasMore=page.hasMore,books=it.books.filterNot{b->b.key in keys}) }
            } catch (e: Exception) { if (e is CancellationException) throw e; shelfFailure=e;state.update { it.copy(shelfLoading = false, shelfError = safeError(e)) } }
        }
    }
    /** Editing/clearing invalidates pending results; blank input never browses the service shelf. */
    fun clearSearch() {
        searchGeneration++;searchJob?.cancel();cursors.clear();findRetry={}
        state.update{it.copy(query="",books=emptyList(),nextCursor=null,hasMore=false,findLoading=false,error=null)}
    }
    private fun searchItems(items:List<Book>,owner:String):List<Book> {
        val shelfKeys=state.value.shelfBooks.map{it.key}.toSet()
        return items.map{tagged(it,owner)}.distinctBy{it.key}.filterNot{it.key in shelfKeys}
    }
    /** An unfetched shelf page is not proof that a catalog book is addable. Read only the official shelf pages. */
    private suspend fun completeShelfForSearch(current:BookSource,request:Long,generation:Long) {
        var pages=0
        if(state.value.shelfError!=null)loadShelf()
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
                requireSession();completeShelfForSearch(current,request,generation)
                val page=current.browse(term);coroutineContext.ensureActive()
                if(request!=searchGeneration || generation!=sessionGeneration || source!==current)return@launch
                state.update{it.copy(books=searchItems(page.items,current.id),nextCursor=page.nextCursor,hasMore=page.hasMore,findLoading=false)}
            } catch(e:Exception) {
                if(e is CancellationException)throw e
                if(request==searchGeneration && generation==sessionGeneration)state.update{it.copy(findLoading=false,error=safeError(e))}
            }
        }
    }
    fun loadMore() {
        val s=state.value;val cursor=s.nextCursor?:return
        if(s.query.isBlank() || s.findLoading || !s.hasMore)return
        val request=searchGeneration;val generation=sessionGeneration;val current=source
        findRetry={loadMore()};state.update{it.copy(findLoading=true,error=null)}
        searchJob=viewModelScope.launch {
            try {
                requireSession();val page=current.browse(s.query,cursor);coroutineContext.ensureActive()
                if(request!=searchGeneration || generation!=sessionGeneration || source!==current)return@launch
                if(page.nextCursor!=null && (page.nextCursor==cursor || page.nextCursor in cursors))throw SourceProblem(FailureKind.PROTOCOL,"服务分页出现循环，请重新搜索")
                cursors+=cursor
                state.update{it.copy(books=searchItems(it.books+page.items,current.id),nextCursor=page.nextCursor,hasMore=page.hasMore,findLoading=false)}
            } catch(e:Exception) {
                if(e is CancellationException)throw e
                if(request==searchGeneration && generation==sessionGeneration)state.update{it.copy(findLoading=false,error=safeError(e))}
            }
        }
    }

    /** All Read/Continue actions use this route; a saved invalid locator is an error, never a silent first chapter. */
    fun open(book: Book, chapterId: String? = null) {
        pending?.cancel()
        val readingStore = storeFor(book)
        val saved = readingStore.position(book.id)
        state.update { it.copy(shelfManaging=false,shelfSelection=emptySet(),book = book, chapter = null, chapters = emptyList(), chaptersComplete = false, chapterLengths = null, loading = true, error = null, notice = null) }
        retry = { open(book, chapterId) }
        pending = viewModelScope.launch {
            try {
                val readingSource = sourceFor(book)
                val refs = readingSource.chaptersFor(book.id,chapterId?:saved?.chapterId)
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
                coroutineContext.ensureActive(); readingStore.save(book.id, chapter.id, offset,graphicId); readingStore.markEntered(book, clock())
                state.update { it.copy(chapters = refs, chapter = chapter, offset = offset, revision = it.revision + 1, loading = false,
                    chapterLengths = lengths, chaptersComplete = true, shelfBooks = combinedShelf(), notice = initial?.notice ?: chapter.navigationNotice,graphicId=graphicId) }
            } catch (e: Exception) { fail(e) }
        }
    }
    private suspend fun completeJump(current: ReaderState, chapterId: String, lastPage: Boolean) {
        val book = current.book ?: return
        if (current.chapters.none { it.id == chapterId }) throw SourceProblem(FailureKind.VERSION, "目录章节已失效，请重新打开目录")
        val readingStore = storeFor(book)
        val chapter = sourceFor(book).chapter(book.id, chapterId)
        coroutineContext.ensureActive()
        if (chapter.paragraphs.none { it.isNotBlank() } && chapter.graphics.isEmpty()) throw SourceProblem(FailureKind.EMPTY, "章节暂时没有正文，请稍后重试")
        val tail = chapter.text.lastIndex
        val offset = if (lastPage) {
            if (tail > 0 && Character.isLowSurrogate(chapter.text[tail])) tail - 1 else tail
        } else 0
        val graphicId=if(lastPage) chapter.graphics.lastOrNull { it.offset>=tail }?.id else chapter.graphics.firstOrNull { it.offset==0 }?.id
        coroutineContext.ensureActive(); readingStore.save(book.id, chapter.id, offset,graphicId); readingStore.markEntered(book, clock())
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
                val next = sourceFor(book).readingNeighbor(book.id,chapter.id,direction,current.chapters)
                coroutineContext.ensureActive()
                if(next == null) state.update { it.copy(loading = false,chaptersComplete = current.chaptersComplete,
                    notice = if(direction < 0) "已经是全书开头" else "全书读完了，感谢阅读") }
                else completeJump(current,next,direction < 0)
            } catch(e: Exception) { fail(e) }
        }
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
    private fun fail(e: Exception) { if (e is CancellationException) throw e; state.update { it.copy(loading = false, error = safeError(e), chaptersComplete = false) } }
    private fun safeError(e: Exception) = if (e is SourceProblem) "${e.message ?: "暂时无法读取，请重试"}\n诊断：${e.diagnostic}" else "暂时无法读取，请重试。已保存的进度会保留。\n诊断：READER_UNKNOWN"
}
