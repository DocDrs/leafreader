package app.leafreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag as coverSemanticsTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.leafreader.data.*
import app.leafreader.reader.*
import app.leafreader.offline.OfflineDownloads
import app.leafreader.offline.OfflineUsageFormatting
import app.leafreader.offline.OfflineStatus
import app.leafreader.offline.OfflineEvents
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException

internal val SavedBookIdentity = androidx.compose.ui.semantics.SemanticsPropertyKey<String>("SavedBookIdentity")

private val Cream = Color(0xFFF7F3EB)
private val Ink = Color(0xFF2F302A)
private val Muted = Color(0xFF807D71)
private val Vermilion = Color(0xFFA94435)
private val HomePaper = Color(0xFFFCFBF8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Vermilion, background = Cream, surface = Cream,
                onBackground = Ink, onSurface = Ink, surfaceContainer = Color(0xFFF0EAE0))) {
                val vm: ReaderViewModel = viewModel(factory=object:androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T:androidx.lifecycle.ViewModel> create(modelClass:Class<T>):T =
                        ReaderViewModel(application,offlineRuntime=OfflineDownloads.get(application)) as T
                })
                ReaderApp(vm) { immersive, dark ->
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
                        if (immersive) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderApp(vm: ReaderViewModel, bars: (Boolean, Boolean) -> Unit = { _, _ -> }) {
    val state by vm.ui.collectAsStateWithLifecycle()
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var directoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var hiddenCleaner by rememberSaveable { mutableStateOf(false) }
    val offlineEnabled=vm.offlineSourceEnabled()
    var hiddenClearTarget by remember(state.connectionRevision,offlineEnabled){mutableStateOf<Book?>(null)}
    var sourceInfo by rememberSaveable { mutableStateOf(false) }
    var downloadId by rememberSaveable { mutableStateOf<String?>(null) }
    var clearDownloadId by rememberSaveable { mutableStateOf<String?>(null) }
    val shelfListState=rememberLazyListState()
    val findListState=rememberLazyListState()
    val settingsScrollState=rememberScrollState()
    var findSearch by rememberSaveable(state.connectionRevision) { mutableStateOf(state.query) }
    var displayedConnectionRevision by rememberSaveable { mutableIntStateOf(state.connectionRevision) }
    LaunchedEffect(state.connectionRevision) {
        if (state.connectionRevision > 0) { sourceInfo = false; detailId = null; directoryId = null;downloadId=null;clearDownloadId=null }
        if(displayedConnectionRevision!=state.connectionRevision) {shelfListState.scrollToItem(0);findListState.scrollToItem(0);displayedConnectionRevision=state.connectionRevision}
    }
    val reading = state.book != null
    val managing=state.shelfManaging
    val selectedKeys=state.shelfSelection
    var batchDownloadKeys by rememberSaveable(state.connectionRevision,state.topTab,reading) { mutableStateOf<List<String>?>(null) }
    var removeKeys by rememberSaveable(state.connectionRevision,state.topTab,reading) { mutableStateOf<List<String>?>(null) }
    val selected=state.shelfBooks.filter{it.key in selectedKeys}
    val snackbar=remember{SnackbarHostState()}
    var addedFeedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(addedFeedback){addedFeedback?.let{snackbar.showSnackbar(it);addedFeedback=null}}
    var shelfActionId by remember(state.connectionRevision, state.topTab, reading) { mutableStateOf<String?>(null) }
    val offlineRevision by OfflineEvents.revision.collectAsStateWithLifecycle()
    LaunchedEffect(state.connectionRevision,offlineEnabled,state.topTab,reading) {
        if(!offlineEnabled || state.topTab!="settings" || reading){hiddenCleaner=false;hiddenClearTarget=null}
    }
    val allBooks = (state.shelfBooks + state.books).distinctBy { it.key }
    val detail = allBooks.find { it.key == detailId }
    val directory = allBooks.find { it.key == directoryId }
    val read: (Book, String?) -> Unit = { book, chapter -> detailId = null; directoryId = null; sheet = null; menu = false; vm.open(book, chapter) }
    LaunchedEffect(reading, menu, sheet, state.settings.palette) { bars(reading && !menu && sheet == null, reading && state.settings.palette == 3) }
    BackHandler(managing || reading || detail != null || directory != null || state.topTab != "shelf") {
        when { managing -> vm.endShelfManagement(); sheet != null -> sheet = null; menu -> menu = false; reading -> vm.backToShelf(); directory != null -> directoryId = null; detail != null -> detailId = null; else -> vm.selectTab("shelf") }
    }
    Surface(Modifier.fillMaxSize(), color = HomePaper) {
        when {
            reading -> ReadingScreen(state, vm, menu, { menu = !menu }, { sheet = it })
            directory != null -> DirectoryScreen(directory, vm, { directoryId = null }) { id -> read(directory, id) }
            detail != null -> BookDetails(detail, vm, { detailId = null }, { directoryId = detail.key },{downloadId=it.key},{clearDownloadId=it.key}) { read(detail, null) }
            else -> Scaffold(containerColor = HomePaper, contentWindowInsets = WindowInsets.safeDrawing, snackbarHost={SnackbarHost(snackbar)},
                bottomBar = {
                    NavigationBar(containerColor = HomePaper) {
                        val colors=NavigationBarItemDefaults.colors(selectedIconColor=Vermilion,selectedTextColor=Vermilion,indicatorColor=Color(0xFFEFE9E1),unselectedIconColor=Muted,unselectedTextColor=Muted)
                        NavigationBarItem(selected = state.topTab == "shelf", onClick = { vm.selectTab("shelf") }, colors=colors,modifier = Modifier.testTag("tab-shelf"), icon = { Text("▥", fontSize = 21.sp) }, label = { Text("书架") })
                        NavigationBarItem(selected = state.topTab == "find", onClick = { vm.selectTab("find") }, colors=colors,modifier = Modifier.testTag("tab-find"), icon = { Text("⌕", fontSize = 23.sp) }, label = { Text("找书") })
                        NavigationBarItem(selected = state.topTab == "settings", onClick = { vm.selectTab("settings") }, colors=colors,modifier = Modifier.testTag("tab-settings"), icon = { Text("⚙", fontSize = 21.sp) }, label = { Text("设置") })
                    }
                }) { padding ->
                when(state.topTab) {
                    "settings" -> HomeSettings(state,vm,Modifier.padding(padding),settingsScrollState,{hiddenCleaner=true}) { sourceInfo = true }
                    "find" -> FindBooks(state, vm, Modifier.padding(padding), findListState, findSearch, {findSearch=it;vm.clearSearch()}, { detailId = it.key }, { read(it, null) }, { directoryId = it.key }, {if(vm.addToShelf(it))addedFeedback="已加入书架"})
                    else -> Shelf(state, vm, Modifier.padding(padding), shelfListState, { read(it, null) }, { shelfActionId = it.key },
                        managing,selected.map{it.key}.toSet(),
                        manage=vm::manageShelf,exit=vm::endShelfManagement,
                        toggle=vm::toggleShelfSelection,selectAll=vm::selectAllShelf,
                        download={batchDownloadKeys=selected.map{it.key}},remove={removeKeys=selected.map{it.key}})
                }
            }
        }
    }
    if (sheet == "toc" && state.book != null) {
        ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Cream) {
            DirectoryContent(state.book!!, vm, Modifier.fillMaxWidth().heightIn(max = 500.dp)) { id -> read(state.book!!, id) }
        }
    }
    if (sheet == "settings") ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Cream) { ReaderControls(state.settings, vm::settings) }
    if(hiddenCleaner && offlineEnabled)HiddenCopyCleaner(state,vm,{hiddenCleaner=false}) { hiddenClearTarget=it }
    if (sourceInfo) SourceConnectionDialog(state, vm.ownOrigin(), { vm.cancelConnection(); sourceInfo = false }, vm::connect, vm::signOut)
    allBooks.firstOrNull { it.key == shelfActionId }?.let { book ->
        ShelfBookActions(book,state,vm,
            dismiss = { shelfActionId = null },
            remove = { shelfActionId=null;removeKeys=listOf(book.key) },
            download = { shelfActionId = null; downloadId = book.key },
            cancel = { vm.cancelDownload(book) },
            clear = { clearDownloadId = book.key })
    }
    allBooks.firstOrNull{it.key==downloadId}?.let { book->
        var metered by remember(book.key){mutableStateOf(false)}
        AlertDialog(onDismissRequest={downloadId=null},title={Text("下载整本")},text={Column(Modifier.heightIn(max=minOf(360.dp,androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp*.5f)).verticalScroll(rememberScrollState())){
            Text(book.title)
            Text("默认只在不计费 Wi-Fi 下下载。任务由系统后台安排，已校验内容可在中断后继续使用。",fontSize=13.sp,modifier=Modifier.padding(vertical=12.dp))
            DownloadNetworkChoice(metered){metered=it}
        }},confirmButton={TextButton(onClick={vm.download(book,metered);downloadId=null}){Text("加入下载队列")}},dismissButton={TextButton(onClick={downloadId=null}){Text("取消")}})
    }
    batchDownloadKeys?.let { keys ->
        val targets=state.shelfBooks.filter{it.key in keys && vm.downloadsAvailable(it)}
        var metered by remember(keys){mutableStateOf(false)}
        val revision=state.connectionRevision
        AlertDialog(onDismissRequest={batchDownloadKeys=null},modifier=Modifier.testTag("batch-download-dialog"),title={Text("批量下载")},
            text={Column(Modifier.heightIn(max=minOf(360.dp,androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp*.5f)).verticalScroll(rememberScrollState())) {
                Text("选中 ${keys.size} 本，当前账号可下载 ${targets.size} 本。已有完整下载或在途任务会跳过。",fontSize=14.sp)
                Text(targets.joinToString("、"){it.title},fontSize=12.sp,color=Muted,maxLines=4,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(vertical=12.dp))
                DownloadNetworkChoice(metered){metered=it}
            }},confirmButton={TextButton(enabled=targets.isNotEmpty(),onClick={vm.downloadSelection(keys.toSet(),revision,metered);batchDownloadKeys=null}){Text("加入下载队列")}},
            dismissButton={TextButton(onClick={batchDownloadKeys=null}){Text("取消")}})
    }
    removeKeys?.let { keys ->
        val targets=state.shelfBooks.filter{it.key in keys};val revision=state.connectionRevision
        AlertDialog(onDismissRequest={removeKeys=null},modifier=Modifier.testTag("remove-shelf-confirm"),title={Text("从本机书架移出 ${targets.size} 本？")},
            text={Column(Modifier.heightIn(max=minOf(360.dp,androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp*.5f)).verticalScroll(rememberScrollState())) {
                Text(targets.joinToString("、"){it.title},fontSize=14.sp,modifier=Modifier.padding(bottom=12.dp))
                Text("仅隐藏栖页本机书架中的这些书。不修改 BookHub 远端书架、不删除原书，阅读进度和离线下载都会保留。可在找书重新加入并继续阅读。",fontSize=14.sp,lineHeight=22.sp)
            }},confirmButton={TextButton(enabled=targets.isNotEmpty(),onClick={vm.removeFromShelf(keys.toSet(),revision);removeKeys=null},modifier=Modifier.testTag("confirm-remove-shelf")){Text("仅从本机移出")}},
            dismissButton={TextButton(onClick={removeKeys=null}){Text("取消")}})
    }
    (hiddenClearTarget ?: allBooks.firstOrNull{it.key==clearDownloadId})?.let { book->
        val hidden=hiddenClearTarget!=null
        val revision=state.connectionRevision
        val close={hiddenClearTarget=null;clearDownloadId=null}
        AlertDialog(onDismissRequest=close,title={Text("清除下载")},text={Column(Modifier.heightIn(max=androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp*.5f).verticalScroll(rememberScrollState())) {
            Text(book.title,modifier=Modifier.padding(bottom=12.dp))
            Text(if(hidden)"只清除当前启用账号这本已移出书架的书的离线副本，并取消其下载任务。隐藏状态、阅读进度和登录保留，不影响其他账号。" else "只清除此账号这本书的离线内容副本。书架、阅读进度和登录会保留。")
        }},confirmButton={TextButton(onClick={if(hidden)vm.clearHiddenDownload(book,revision) else vm.clearDownload(book);close()}){Text("清除副本")}},dismissButton={TextButton(onClick=close){Text("取消")}})
    }
    if (state.notice != null) AlertDialog(onDismissRequest = vm::dismissNotice, text = { Text(state.notice!!) },
        confirmButton = { TextButton(onClick = vm::dismissNotice) { Text("知道了") } })
}

@OptIn(ExperimentalFoundationApi::class,ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable
private fun Shelf(state: ReaderState, vm: ReaderViewModel, modifier: Modifier, listState: LazyListState,
    read: (Book) -> Unit, actions:(Book)->Unit,managing:Boolean,selected:Set<String>,manage:()->Unit,exit:()->Unit,
    toggle:(String)->Unit,selectAll:()->Unit,download:()->Unit,remove:()->Unit) {
    PullToRefreshBox(isRefreshing=state.shelfLoading,onRefresh=vm::refreshShelf,modifier=modifier.fillMaxSize().testTag("shelf-refresh").semantics {
        customActions=listOf(CustomAccessibilityAction("刷新书架"){vm.refreshShelf();true})
    }) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp).testTag("shelf-list"),state=listState) {
        item {
            Text("书架",fontSize=26.sp,lineHeight=32.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=18.dp))
            if(managing) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("已选 ${selected.size} 本",fontSize=15.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f).testTag("shelf-selected-count"))
                    TextButton(onClick=exit,modifier=Modifier.testTag("shelf-manage-exit")){Text("退出管理")}
                }
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick=selectAll,enabled=state.shelfBooks.isNotEmpty(),modifier=Modifier.testTag("shelf-select-all")){Text(if(selected.size==state.shelfBooks.size && selected.isNotEmpty())"取消全选" else "全选当前列表")}
                    FilledTonalButton(colors=ButtonDefaults.filledTonalButtonColors(containerColor=Color(0xFFEFE9E1),contentColor=Vermilion),onClick=download,enabled=state.shelfBooks.any{it.key in selected && vm.downloadsAvailable(it)},modifier=Modifier.testTag("shelf-batch-download")){Text("下载选中")}
                    TextButton(onClick=remove,enabled=selected.isNotEmpty(),modifier=Modifier.testTag("shelf-batch-remove")){Text("移出书架")}
                }
                Text("${state.shelfBooks.size} 本 · 点书籍选择，可多选",color=Muted,fontSize=11.sp,lineHeight=16.sp,modifier=Modifier.padding(top=6.dp,bottom=12.dp))
            } else {
                Row(Modifier.fillMaxWidth().padding(bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("${state.shelfBooks.size} 本 · 最近阅读排序"+if(state.remote && state.offline)" · 离线" else "",color=Muted,fontSize=11.sp,lineHeight=16.sp,modifier=Modifier.weight(1f).testTag("shelf-book-count"))
                    TextButton(onClick=manage,modifier=Modifier.testTag("shelf-manage")){Text("管理",fontSize=13.sp)}
                }
            }
        }
        items(state.shelfBooks,key={it.key}) { book ->
            val touch=if(managing)Modifier.toggleable(value=book.key in selected,role=Role.Checkbox,onValueChange={toggle(book.key)})
                else Modifier.combinedClickable(role=Role.Button,onClickLabel="阅读或继续阅读",onClick={read(book)},onLongClickLabel="书籍操作",onLongClick={actions(book)})
            CompactBookRow(book,state,vm,Modifier.then(touch).testTag("shelf-book-${book.id}").semantics { this[SavedBookIdentity]=book.key },
                if(vm.positionOf(book)!=null)"从上次位置继续阅读" else book.description.ifBlank{"尚未开始阅读"}) {
                if(managing)Checkbox(checked=book.key in selected,onCheckedChange=null)
            }
            HorizontalDivider(color=Color(0xFFE6E0D6),thickness=.5.dp)
        }
        if(state.shelfHasMore && !state.shelfLoading)item{TextButton(onClick=vm::loadMoreShelf,modifier=Modifier.fillMaxWidth()){Text("加载更多书架")}}
        if(state.localShelfWarning!=null)item{Text(state.localShelfWarning,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
        if(state.shelfError!=null)item{Column(Modifier.padding(vertical=16.dp)){Text(state.shelfError,color=MaterialTheme.colorScheme.error,fontSize=12.sp);TextButton(onClick=vm::refreshShelf){Text("重试")}}}
        if(!state.shelfLoading && state.shelfBooks.isEmpty() && state.shelfError==null)item{
            Text(if(state.remote)"书架还是空的，到找书页选择一本吧。" else "书架还是空的，登录 BookHub 后即可找书。",color=Muted,modifier=Modifier.padding(top=30.dp))
            TextButton(onClick={vm.selectTab("find")},modifier=Modifier.testTag("empty-shelf-find")){Text("去找书")}
        }
        item{Spacer(Modifier.height(20.dp))}
    }
    }
}

/** Shelf and search use the same cover dimensions, typography, gaps and summary. */
@Composable
private fun CompactBookRow(book:Book,state:ReaderState,vm:ReaderViewModel,modifier:Modifier,summary:String,
    trailing:@Composable RowScope.()->Unit={}) {
    Row(modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(13.dp),verticalAlignment=Alignment.CenterVertically) {
        val cover by produceState<android.graphics.Bitmap?>(null,book.key,vm,state.connectionRevision,state.shelfRevision,state.downloadRevision){value=null;value=vm.shelfCover(book)}
        ShelfCover(book,cover)
        Column(Modifier.weight(1f)) {
            Text(book.title,fontSize=17.sp,lineHeight=22.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(book.author.ifBlank{"作者未提供"},color=Muted,fontSize=12.sp,lineHeight=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))
            Text(summary.replace(Regex("\\s+")," ").trim(),color=Muted.copy(alpha=.85f),fontSize=11.sp,lineHeight=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))
        }
        trailing()
    }
}

@Composable
private fun DownloadNetworkChoice(metered:Boolean,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("download-wifi").selectable(selected=!metered,onClick={change(false)},role=Role.RadioButton),verticalAlignment=Alignment.CenterVertically){RadioButton(selected=!metered,onClick=null);Text("仅不计费 Wi-Fi")}
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("download-metered").selectable(selected=metered,onClick={change(true)},role=Role.RadioButton),verticalAlignment=Alignment.CenterVertically){RadioButton(selected=metered,onClick=null);Text("允许本次使用流量或其他网络")}
}

/** Local typographic cover placeholder: never fetches images or opens a reading package. */
@Composable
private fun ShelfCover(book:Book,image:android.graphics.Bitmap?) {
    Box(Modifier.size(54.dp,74.dp).clearAndSetSemantics{coverSemanticsTag=if(image==null)"shelf-placeholder-${book.id}" else "shelf-real-cover-${book.id}"}.clip(RoundedCornerShape(3.dp)).background(Color(book.color.takeIf{it ushr 24!=0L}?:0xFF536B67))) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.White.copy(alpha=.1f),size=androidx.compose.ui.geometry.Size(size.width*.12f,size.height))
            drawLine(Color.White.copy(alpha=.23f),Offset(size.width*.2f,size.height*.82f),Offset(size.width*.8f,size.height*.82f),1.dp.toPx())
        }
        Text(book.title.take(9).chunked(3).joinToString("\n"),fontFamily=FontFamily.Serif,fontWeight=FontWeight.SemiBold,color=Color(0xFFFFF5DF),fontSize=10.sp,lineHeight=15.sp,maxLines=3,modifier=Modifier.align(Alignment.TopCenter).padding(top=12.dp))
        if(image!=null)Image(image.asImageBitmap(),contentDescription=null,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().background(Color(0xFFEEE9DF)))
    }
}

@Composable
private fun HomeSettings(state:ReaderState,vm:ReaderViewModel,modifier:Modifier,scroll:androidx.compose.foundation.ScrollState,downloads:()->Unit,source:()->Unit) {
    val revision by OfflineEvents.revision.collectAsStateWithLifecycle()
    val enabled=vm.offlineSourceEnabled()
    var reload by remember{mutableIntStateOf(0)}
    var loading by remember(state.connectionRevision,enabled){mutableStateOf(false)}
    var error by remember(state.connectionRevision,enabled){mutableStateOf(false)}
    var usage by remember(state.connectionRevision,enabled){mutableStateOf(OfflineManagementSnapshot())}
    LaunchedEffect(state.connectionRevision,enabled,revision,reload) {
        loading=true;error=false
        try{delay(250);usage=vm.offlineUsage()}catch(e:CancellationException){throw e}catch(_:Exception){error=true}finally{loading=false}
    }
    Column(modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal=24.dp).testTag("settings-page")) {
        Text("设置",fontSize=26.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=18.dp,bottom=28.dp))
        Text("书源与账号",fontSize=12.sp,color=Muted)
        state.retentionNote?.let{Text(it,fontSize=12.sp,lineHeight=20.sp,modifier=Modifier.padding(vertical=12.dp).testTag("offline-retention-note"))}
        Row(Modifier.fillMaxWidth().clickable(onClick=source).testTag("settings-source").padding(vertical=20.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("BookHub 账号与服务",fontSize=17.sp);Text(state.sourceLabel,fontSize=12.sp,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=6.dp)) }
            Text("›",fontSize=24.sp,color=Muted,modifier=Modifier.padding(start=12.dp))
        }
        HorizontalDivider(color=Color(0xFFE6E0D6))
        Text("离线下载",fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=28.dp,bottom=12.dp))
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("新加入书籍自动下载",fontSize=16.sp,modifier=Modifier.weight(1f))
            Switch(checked=state.automaticWifi,onCheckedChange=vm::automaticWifi,modifier=Modifier.testTag("automatic-wifi").semantics{contentDescription="新加入书籍自动下载"})
        }
        Text("仅在不计费 Wi-Fi 下后台排队；不会补下载升级前的整个书架。关闭会取消未完成的自动任务，已保存内容保留。",fontSize=12.sp,lineHeight=20.sp,color=Muted,modifier=Modifier.padding(top=6.dp))
        if(usage.available) {
            Text("离线副本总占用："+OfflineUsageFormatting.bytes(usage.totalBytes),fontSize=15.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=18.dp).testTag("offline-total-bytes"))
            Text("仅当前启用服务与账号的文件大小快照：含各版本、部分下载、阅读缓存、临时文件及副本元数据；不含封面缩图、安装包、进度／设置、其他账号及文件系统开销。",fontSize=11.sp,lineHeight=18.sp,color=Muted)
        } else if(!loading && !error)Text("请登录当前来源后查看占用；未统计不代表副本已删除。",fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=18.dp))
        if(error)Text("本机占用暂无法统计，文件与进度保留；已有数值为上次快照。",fontSize=12.sp,color=Muted)
        TextButton(onClick={reload++},enabled=!loading,modifier=Modifier.testTag("offline-refresh-usage")){Text(if(loading)"正在统计本机占用…" else "刷新本机占用")}
        TextButton(onClick=downloads,enabled=enabled,modifier=Modifier.testTag("offline-management")){Text("清理已移出书架的副本")}
        Text("只列当前启用账号中已主动移出的项目，不必重新加入书架。",fontSize=12.sp,color=Muted)
        Text("阅读未保存章节仍会按需联网并保存。下载整本、取消或清除副本，请长按书架中的书籍。",fontSize=12.sp,lineHeight=20.sp,color=Muted,modifier=Modifier.padding(top=14.dp,bottom=28.dp))
    }
}

@Composable
private fun HiddenCopyCleaner(state:ReaderState,vm:ReaderViewModel,dismiss:()->Unit,clear:(Book)->Unit) {
    val revision by OfflineEvents.revision.collectAsStateWithLifecycle()
    var reload by remember{mutableIntStateOf(0)}
    var loading by remember(state.connectionRevision){mutableStateOf(true)}
    var error by remember(state.connectionRevision){mutableStateOf(false)}
    var snapshot by remember(state.connectionRevision){mutableStateOf(OfflineManagementSnapshot())}
    LaunchedEffect(state.connectionRevision,revision,state.shelfRevision,reload) {
        loading=true;error=false
        try{delay(150);snapshot=vm.hiddenCopiesSnapshot()}catch(e:CancellationException){throw e}catch(_:Exception){error=true}finally{loading=false}
    }
    AlertDialog(onDismissRequest=dismiss,modifier=Modifier.testTag("hidden-copy-cleaner"),title={Text("清理已移出书架的副本")},text={
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=440.dp).testTag("hidden-copy-list")) {
            item {Text("仅当前启用服务与账号中已主动移出的书。按文件大小列出，不逐本校验正文；清除会单独确认，隐藏状态和进度保留。",fontSize=12.sp,lineHeight=20.sp)}
            items(snapshot.books,key={it.key}){book->
                Column(Modifier.fillMaxWidth().padding(vertical=8.dp)) {
                    Text(book.title,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text("占用："+OfflineUsageFormatting.bytes(snapshot.bytesByBook[book.key]?:0L),fontSize=12.sp,modifier=Modifier.testTag("offline-book-bytes-${book.id}"))
                    vm.downloadTaskLabel(book)?.let{Text(it,fontSize=12.sp,color=Muted)}
                    TextButton(onClick={clear(book)},enabled=!loading && !error,modifier=Modifier.testTag("hidden-clear-${book.id}")){Text("清除副本…")}
                }
            }
            if(loading)item{Text("正在读取已移出项目的本机占用…",fontSize=12.sp)}
            else if(error)item{Text("读取失败，原文件与进度保留；已有项目为上次快照。",fontSize=12.sp);TextButton(onClick={reload++}){Text("重试")}}
            else if(snapshot.books.isEmpty())item{Text("没有当前账号已移出书架的副本或待取消任务。",fontSize=12.sp,modifier=Modifier.testTag("hidden-copy-empty"))}
        }
    },confirmButton={TextButton(onClick=dismiss){Text("关闭")}})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfBookActions(book:Book,state:ReaderState,vm:ReaderViewModel,
    dismiss:()->Unit,remove:()->Unit,download:()->Unit,cancel:()->Unit,clear:()->Unit) {
    val contentRevision by OfflineEvents.revision.collectAsStateWithLifecycle()
    val available=vm.downloadsAvailable(book);val busy=vm.downloadBusy(book)
    var recheck by remember(book.key){mutableIntStateOf(0)}
    var inspectionError by remember(book.key){mutableStateOf(false)}
    val copy by produceState<OfflineStatus?>(null,book.key,state.connectionRevision,contentRevision,recheck) {
        value=null;inspectionError=false;delay(180)
        try {value=vm.inspectDownload(book)}catch(e:CancellationException){throw e}catch(_:Exception){inspectionError=true}
    }
    val task=vm.downloadTaskLabel(book)
    val content=copy?.let{if(it.complete)"已下载" else if(it.saved>0)"部分离线" else "未下载"}
    val headline=when {
        copy?.complete==true->"已下载"
        task!=null->task.substringBefore(" · ")
        inspectionError->"核验失败"
        copy==null->"正在核验"
        else->content.orEmpty()
    }
    val integrity=when {
        inspectionError->"原文件与进度保留，可重试核验"
        copy==null->"正在检查本机副本"
        copy!!.complete->"完整性已核验 · ${copy!!.saved}/${copy!!.total} 份资源"
        copy!!.saved>0->"已核验 ${copy!!.saved}/${copy!!.total} 份资源 · 部分内容可离线"
        else->"本机暂无完整副本"
    }
    val taskDetail=when {
        task==null->null
        copy?.complete==true && task.startsWith("下载失败")->"最近任务失败，完整副本保留"
        copy?.complete==true->task
        else->task.substringAfter(" · ","").takeIf{it.isNotBlank()}
    }
    val moss=Color(0xFF506D5C)
    val tint=when {copy?.complete==true->moss;inspectionError || task?.startsWith("下载失败")==true->Vermilion;else->Color(0xFF6F6B61)}
    val maxHeight=androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp*.86f
    ModalBottomSheet(onDismissRequest=dismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=HomePaper,contentColor=Ink,shape=RoundedCornerShape(topStart=24.dp,topEnd=24.dp),
        contentWindowInsets={WindowInsets.safeDrawing},
        dragHandle={Box(Modifier.fillMaxWidth().padding(top=10.dp,bottom=6.dp),contentAlignment=Alignment.Center) {
            Box(Modifier.size(width=32.dp,height=4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF6F6B61).copy(alpha=.28f)))
        }},
        modifier=Modifier.testTag("shelf-book-actions")) {
        Column(Modifier.fillMaxWidth().heightIn(max=maxHeight).verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=12.dp)) {
            Row(Modifier.fillMaxWidth().padding(top=4.dp),verticalAlignment=Alignment.Top) {
                Column(Modifier.weight(1f).padding(end=8.dp)) {
                    Text(book.title,fontFamily=FontFamily.Serif,fontSize=22.sp,lineHeight=29.sp,fontWeight=FontWeight.SemiBold,
                        maxLines=if(androidx.compose.ui.platform.LocalDensity.current.fontScale>=1.4f)2 else 3,
                        overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("book-actions-title"))
                    if(book.author.isNotBlank() && book.author!="作者未注明")Text(book.author,fontSize=12.sp,color=Color(0xFF6F6B61),maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))
                }
                TextButton(onClick=dismiss,contentPadding=PaddingValues(horizontal=8.dp)){Text("关闭",fontSize=13.sp,color=Color(0xFF6F6B61))}
            }
            Column(Modifier.padding(top=14.dp,bottom=12.dp).then(if(recheck>0)Modifier.testTag("download-check-result-${book.id}")else Modifier)
                .semantics{liveRegion=androidx.compose.ui.semantics.LiveRegionMode.Polite}) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Canvas(Modifier.size(6.dp)){drawCircle(tint)}
                    Text(headline,fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium,color=tint,
                        modifier=Modifier.padding(start=8.dp).testTag("download-status-${book.id}"))
                }
                Text(listOfNotNull(integrity,taskDetail).joinToString("\n"),fontSize=12.sp,lineHeight=19.sp,color=Color(0xFF6F6B61),
                    maxLines=3,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=5.dp).testTag("download-copy-${book.id}"))
            }
            if(vm.canManageOffline(book)) {
                if(busy)BookActionRow("取消下载",2,Modifier.testTag("cancel-download-${book.id}"),onClick=cancel,highlight=true)
                else if(available && copy!=null && copy?.complete!=true)BookActionRow(
                    if(task?.startsWith("下载失败")==true)"重试下载" else if(copy!!.saved>0)"继续下载" else "下载整本",0,
                    Modifier.testTag("download-book-${book.id}"),onClick=download,highlight=true)
                BookActionRow(if(inspectionError)"重试核验" else "核验本机下载",1,Modifier.testTag("verify-download-${book.id}"),
                    enabled=copy!=null || inspectionError,onClick={recheck++})
            }
            if(vm.canManageOffline(book) || vm.isOnShelf(book)) {
                HorizontalDivider(color=Color(0xFFE6E1D8),modifier=Modifier.padding(vertical=10.dp).testTag("book-actions-separator"))
                if(vm.canManageOffline(book))BookActionRow("清除下载",null,Modifier.testTag("clear-download-${book.id}"),
                    subtitle="只清副本，保留阅读进度",onClick=clear)
                if(vm.isOnShelf(book))BookActionRow("移出书架",null,Modifier.testTag("remove-book-${book.id}"),
                    subtitle="仅本机隐藏，保留进度与下载",onClick=remove)
            }
        }
    }
}

@Composable
private fun BookActionRow(label:String,icon:Int?,modifier:Modifier=Modifier,subtitle:String?=null,
                          enabled:Boolean=true,highlight:Boolean=false,onClick:()->Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(if(highlight)Color(0xFFF0ECE4)else Color.Transparent)
        .clickable(enabled=enabled,role=Role.Button,onClick=onClick).heightIn(min=52.dp).padding(horizontal=12.dp,vertical=12.dp),
        verticalAlignment=Alignment.CenterVertically) {
        val color=if(enabled)Ink else Color(0xFF6F6B61).copy(alpha=.55f)
        if(icon!=null) {
            Canvas(Modifier.size(22.dp)) {
                val w=size.width;val h=size.height;val stroke=1.6.dp.toPx()
                fun line(x1:Float,y1:Float,x2:Float,y2:Float)=drawLine(color,Offset(w*x1,h*y1),Offset(w*x2,h*y2),strokeWidth=stroke,cap=androidx.compose.ui.graphics.StrokeCap.Round)
                when(icon) {
                    0->{line(.5f,.12f,.5f,.62f);line(.3f,.43f,.5f,.63f);line(.7f,.43f,.5f,.63f);line(.2f,.72f,.2f,.85f);line(.2f,.85f,.8f,.85f);line(.8f,.85f,.8f,.72f)}
                    1->{drawCircle(color,radius=w*.37f,style=androidx.compose.ui.graphics.drawscope.Stroke(stroke));line(.3f,.5f,.44f,.63f);line(.44f,.63f,.7f,.36f)}
                    else->drawRoundRect(color,Offset(w*.26f,h*.26f),androidx.compose.ui.geometry.Size(w*.48f,h*.48f),androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                }
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label,fontSize=16.sp,lineHeight=23.sp,color=color,fontWeight=if(highlight)FontWeight.Medium else FontWeight.Normal)
            if(subtitle!=null)Text(subtitle,fontSize=12.sp,lineHeight=18.sp,color=Color(0xFF6F6B61),modifier=Modifier.padding(top=3.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FindBooks(state:ReaderState,vm:ReaderViewModel,modifier:Modifier,listState:LazyListState,search:String,searchChange:(String)->Unit,
    detail:(Book)->Unit,read:(Book)->Unit,directory:(Book)->Unit,add:(Book)->Unit) {
    val keyboard=LocalSoftwareKeyboardController.current
    val submit={if(search.isNotBlank()){keyboard?.hide();vm.search(search)}}
    LazyColumn(modifier.fillMaxSize().padding(horizontal=20.dp).testTag("find-list"),state=listState) {
        item{Text("找书",fontSize=26.sp,lineHeight=32.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=18.dp,bottom=20.dp))}
        if(!state.remote)item{
            Text("登录 BookHub，搜索并添加书籍到书架。",color=Muted)
            Button(onClick={vm.selectTab("settings")},modifier=Modifier.testTag("find-login")){Text("前往设置登录")}
        }
        if(state.remote)item{
            Text("在我的服务中找书",fontSize=12.sp,color=Muted,modifier=Modifier.padding(bottom=8.dp))
            OutlinedTextField(value=search,onValueChange=searchChange,singleLine=true,modifier=Modifier.fillMaxWidth().testTag("find-search-input").semantics{contentDescription="书名或作者"},
                placeholder={Text("输入书名或作者",fontSize=14.sp)},shape=RoundedCornerShape(12.dp),
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={submit()}),
                trailingIcon={Row {
                    if(search.isNotEmpty())IconButton(onClick={searchChange("")},modifier=Modifier.testTag("find-search-clear").semantics{contentDescription="清空搜索"}){Text("×",fontSize=23.sp)}
                    IconButton(onClick=submit,enabled=search.isNotBlank() && !state.findLoading,modifier=Modifier.testTag("find-search-submit").semantics{contentDescription="搜索"}){Text("⌕",fontSize=25.sp)}
                }})
            Spacer(Modifier.height(20.dp))
        }
        if(state.remote && state.query.isBlank())item{Text("输入关键词并搜索，结果会显示在这里。",color=Muted,fontSize=13.sp,modifier=Modifier.testTag("find-unsubmitted"))}
        if(state.remote && state.query.isNotBlank()) {
            item{Text("搜索结果",fontSize=15.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(bottom=8.dp))}
            items(state.books,key={it.key}) { book ->
                Column {
                    CompactBookRow(book,state,vm,Modifier.clickable{detail(book)}.testTag("find-book-${book.id}"),book.description.ifBlank{"暂无简介"})
                    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                        TextButton(enabled=vm.canAddBook(book),onClick={add(book)},modifier=Modifier.testTag("add-shelf-${book.id}")){Text(if(vm.canAddBook(book))"加入书架" else "正在确认书架状态")}
                        TextButton(onClick={directory(book)},modifier=Modifier.testTag("find-directory-${book.id}")){Text("目录")}
                        TextButton(onClick={read(book)},modifier=Modifier.testTag("find-read-${book.id}")){Text(if(vm.positionOf(book)==null)"阅读" else "继续阅读")}
                    }
                    HorizontalDivider(color=Color(0xFFE6E0D6),thickness=.5.dp)
                }
            }
            if(state.findLoading)item{CircularProgressIndicator(Modifier.padding(20.dp))}
            if(state.hasMore && !state.findLoading)item{OutlinedButton(onClick=vm::loadMore,modifier=Modifier.fillMaxWidth().testTag("find-load-more")){Text("加载更多")}}
            if(state.findError!=null)item{Column(Modifier.padding(vertical=16.dp)){Text(state.findError,color=MaterialTheme.colorScheme.error,fontSize=12.sp);TextButton(onClick=vm::retrySearch){Text("重试搜索")}}}
            if(!state.findLoading && state.books.isEmpty() && state.findError==null)item{
                Text(if(state.hasMore)"这一页没有可添加的书籍，可继续加载。" else "没有可添加的书籍：可能已在书架，或没有匹配结果。",color=Muted,fontSize=13.sp,modifier=Modifier.testTag("find-empty"))
            }
            item{Text("加入书架保存在当前服务与账号的本机书架。",color=Muted,fontSize=11.sp,modifier=Modifier.padding(top=16.dp,bottom=25.dp))}
        }
    }
}
@Composable
private fun BookCover(book: Book, modifier: Modifier) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(4.dp)).background(Color(book.color))) {
        val compact = maxWidth < 80.dp
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White.copy(alpha = .22f), size.width * .23f, Offset(size.width * .74f, size.height * .26f))
            val hill = Path().apply {
                moveTo(0f, size.height * .78f); lineTo(size.width * .37f, size.height * .45f)
                lineTo(size.width * .67f, size.height * .74f); lineTo(size.width, size.height * .54f)
                lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }
            drawPath(hill, Color.Black.copy(alpha = .13f))
            drawLine(Color.White.copy(alpha = .15f), Offset(5.dp.toPx(), 0f), Offset(5.dp.toPx(), size.height), 1.dp.toPx())
        }
        Text(if (compact) book.title.take(2) else book.title.chunked(2).joinToString("\n"), color = Color(0xFFFFF5DF),
            fontSize = if (compact) 14.sp else 18.sp, lineHeight = if (compact) 20.sp else 26.sp,
            maxLines = if (compact) 1 else 4, overflow = TextOverflow.Ellipsis,
            fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopStart).padding(if (compact) 8.dp else 14.dp, if (compact) 12.dp else 15.dp))
        Text(if (compact) "栖 页" else "栖 页 · 阅 读", color = Color.White.copy(alpha = .65f), fontSize = 6.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
    }
}

@Composable
private fun BookDetails(book: Book, vm: ReaderViewModel, back: () -> Unit, directory: () -> Unit,download:(Book)->Unit,clear:(Book)->Unit, read: () -> Unit) {
    val state by vm.ui.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)) {
        item {
            TextButton(onClick = back, contentPadding = PaddingValues(0.dp)) { Text("←  返回") }
            Row(Modifier.padding(top = 22.dp, bottom = 25.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                var cover by remember(book.key,state.connectionRevision){mutableStateOf<android.graphics.Bitmap?>(null)}
                LaunchedEffect(book.key,state.shelfRevision,state.connectionRevision){cover=vm.shelfCover(book)}
                if(cover!=null)Image(cover!!.asImageBitmap(),null,Modifier.size(106.dp,154.dp),contentScale=ContentScale.Fit) else BookCover(book, Modifier.size(106.dp, 154.dp))
                Column(Modifier.padding(top = 10.dp)) {
                    Text(book.title, fontSize = 27.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                    Text(book.author, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                    Text(book.format, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp))
                    Text(state.sourceLabel, color = Vermilion, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }
            Text(book.description, color = Muted, lineHeight = 25.sp, fontSize = 14.sp)
            Button(onClick = read, modifier = Modifier.fillMaxWidth().padding(top = 26.dp).height(49.dp).testTag("detail-read")) {
                Text(if (vm.positionOf(book) == null) "阅读" else "继续阅读")
            }
            OutlinedButton(onClick = directory, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("detail-directory")) { Text("目录") }
            OutlinedButton(onClick = { vm.addToShelf(book) }, enabled = !vm.isOnShelf(book) && vm.canAddBook(book), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(if (vm.isOnShelf(book)) "已在书架" else "加入书架") }
            if(vm.isOnShelf(book) && vm.downloadsAvailable(book))DownloadControls(book,vm,download,clear)
        }
    }
}

@Composable
private fun DownloadControls(book:Book,vm:ReaderViewModel,download:(Book)->Unit,clear:(Book)->Unit) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val revision by OfflineEvents.revision.collectAsStateWithLifecycle()
    var recheck by remember(book.key){mutableIntStateOf(0)}
    var verifyError by remember(book.key){mutableStateOf(false)}
    val verified by produceState<OfflineStatus?>(null,book.key,state.connectionRevision,revision,recheck){value=null;verifyError=false;try{value=vm.inspectDownload(book)}catch(e:CancellationException){throw e}catch(_:Exception){verifyError=true}}
    Column(Modifier.fillMaxWidth().padding(bottom=8.dp)) {
        Text(verified?.label?:if(verifyError)"核验失败，可重试" else "正在核验本机下载",fontSize=11.sp,color=Muted,modifier=Modifier.testTag("download-status-${book.id}"))
        Row(horizontalArrangement=Arrangement.End,modifier=Modifier.fillMaxWidth()) {
            if(vm.downloadBusy(book))TextButton(onClick={vm.cancelDownload(book)}){Text("取消下载")}
            else if(verified!=null && verified?.complete!=true)TextButton(onClick={download(book)},modifier=Modifier.testTag("download-book-${book.id}")){Text("下载整本 / 重试")}
            TextButton(onClick={recheck++}){Text("核验本机下载")}
            TextButton(onClick={clear(book)},modifier=Modifier.testTag("clear-download-${book.id}")){Text("清除下载")}
        }
    }
}

@Composable
private fun DirectoryScreen(book: Book, vm: ReaderViewModel, back: () -> Unit, read: (String) -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TextButton(onClick = back, modifier = Modifier.padding(horizontal = 15.dp)) { Text("←  返回") }
        DirectoryContent(book, vm, Modifier.weight(1f), read)
    }
}

@Composable
private fun DirectoryContent(book: Book, vm: ReaderViewModel, modifier: Modifier, read: (String) -> Unit) {
    var chapters by remember(book.key) { mutableStateOf<List<ChapterRef>>(emptyList()) }
    var error by remember(book.key) { mutableStateOf<String?>(null) }
    var loading by remember(book.key) { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(book.key, reload) {
        loading = true; error = null
        try { chapters = vm.chapterList(book,refresh=reload>0) }
        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = if (e is SourceProblem) e.message else "目录读取失败，请重试" }
        finally { loading = false }
    }
    val listState=rememberLazyListState()
    LaunchedEffect(chapters){val index=chapters.indexOfFirst{it.id==vm.positionOf(book)?.chapterId};if(index>=0)listState.scrollToItem(index+1)}
    LazyColumn(modifier.fillMaxWidth().testTag("directory-list"),state=listState) {
        item {
            Text("目录", Modifier.padding(horizontal = 24.dp), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(book.title, Modifier.padding(24.dp, 8.dp), color = Muted, fontSize = 12.sp)
            TextButton(enabled=!loading,onClick={reload++},modifier=Modifier.padding(horizontal=16.dp)){Text("刷新目录")}
        }
        items(chapters, key = { it.id }) { chapter ->
            Row(Modifier.fillMaxWidth().clickable(enabled=!loading) { read(chapter.id) }.padding(horizontal = 24.dp, vertical = 20.dp)) {
                Text(chapter.title, color = if (chapter.id == vm.positionOf(book)?.chapterId) Vermilion else Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("→", color = Muted)
            }
        }
        if (loading) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
        if (!loading && chapters.isEmpty() && error == null) item { Text("暂无可读章节。", Modifier.padding(24.dp), color = Muted) }
        if (error != null) item { Text(error!!, Modifier.padding(24.dp), color = Vermilion); TextButton(onClick = { reload++ }) { Text("重试目录") } }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun ReadingScreen(state: ReaderState, vm: ReaderViewModel, menu: Boolean, toggleMenu: () -> Unit, sheet: (String) -> Unit) {
    val paper = papers[state.settings.palette]
    Box(Modifier.fillMaxSize().background(Color(paper.background)).safeDrawingPadding()) {
        val chapter = state.chapter
        if (chapter != null && !state.loading && state.error == null) {
            AndroidView(factory = { context -> NativeReaderView(context) }, modifier = Modifier.fillMaxSize(), update = { view ->
                view.onBoundary = vm::boundary; view.onMenu = toggleMenu; view.onPosition = vm::position
                view.onVisualPosition = vm::visualPosition
                view.bind(chapter.text, "${state.book!!.title} · ${chapter.title}", state.offset, state.revision, state.settings,
                    "BookHub · 本机进度",chapter.graphics,state.graphicId,chapter.paragraphs.isEmpty() && chapter.graphics.isNotEmpty())
                view.updateProgressContext(state.chapters.indexOfFirst { it.id == chapter.id }, state.chapters.size,
                    state.chaptersComplete && state.progressOrderReliable, state.chapterLengths)
            })
        }
        if (menu) {
            Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), color = Cream, shadowElevation = 4.dp) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::backToShelf) { Text("←  书架") }
                    Text(state.book!!.title, Modifier.weight(1f), fontSize = 15.sp)
                    TextButton(onClick = toggleMenu) { Text("收起") }
                }
            }
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = Cream, shadowElevation = 6.dp) {
                Column(Modifier.padding(16.dp)) {
                    Text("左 / 右轻点或横滑翻页 · 点中间收起菜单", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { vm.boundary(-1) }) { Text("上一章") }
                        TextButton(onClick = { sheet("toc") }) { Text("目录") }
                        TextButton(onClick = { sheet("settings") }) { Text("阅读设置") }
                        TextButton(onClick = { vm.boundary(1) }) { Text("下一章") }
                    }
                }
            }
        }
        if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Vermilion)
        if (state.error != null) Column(Modifier.align(Alignment.Center)) {
            Text("${state.book?.title.orEmpty()} · ${state.book?.format.orEmpty()}", modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 24.dp), fontSize = 13.sp, color = Muted)
            ErrorPanel(vm::retry, vm::backToShelf, state.error)
            if(state.canReturnPage)TextButton(onClick=vm::returnReadablePage,modifier=Modifier.align(Alignment.CenterHorizontally).testTag("return-readable-page")){Text("返回刚才那页")}
            if(state.error.contains("BH_VERSION"))TextButton(onClick=vm::recoverFromStart,modifier=Modifier.align(Alignment.CenterHorizontally)){Text("从首个可读页恢复（保留旧记录）")}
            if(state.error.contains("BH_VERSION") && vm.hasPreservedPosition())TextButton(onClick=vm::restorePreservedPosition,modifier=Modifier.align(Alignment.CenterHorizontally)){Text("恢复保留的旧记录并重试")}
            TextButton(onClick = { sheet("toc") }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("主动打开目录") }
        }
    }
}

@Composable
private fun ErrorPanel(retry: () -> Unit, back: () -> Unit, message: String = "请重试。已保存的进度会保留。") {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("这一页，暂时没有打开", fontSize = 20.sp)
        Text(message, color = Muted, modifier = Modifier.padding(vertical = 16.dp))
        Button(onClick = retry) { Text("重试") }
        TextButton(onClick = back) { Text("返回书架") }
    }
}
