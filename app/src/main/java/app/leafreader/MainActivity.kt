package app.leafreader

import androidx.compose.ui.platform.LocalContext

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
            MaterialTheme(colorScheme = if(androidx.compose.foundation.isSystemInDarkTheme())darkColorScheme(primary=Color(0xFFFFB69E),background=Color(0xFF17221F),surface=Color(0xFF223029),onBackground=Color(0xFFEEF2EB),onSurface=Color(0xFFEEF2EB),surfaceContainer=Color(0xFF2B3B32)) else lightColorScheme(primary = Vermilion, background = Cream, surface = Cream,
                onBackground = Ink, onSurface = Ink, surfaceContainer = Color(0xFFF0EAE0))) {
                val vm: ReaderViewModel = viewModel(factory=object:androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T:androidx.lifecycle.ViewModel> create(modelClass:Class<T>):T =
                        app.leafreader.sync.ShelfSyncRuntime.get(application).let{runtime->ReaderViewModel(application,sessionVault=runtime.vault,offlineRuntime=runtime.downloads,automaticShelfSync=runtime,independentSources=listOf(GutenbergSource(application)))} as T
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
    val visualContext=androidx.compose.ui.platform.LocalContext.current
    val visualPrefs=remember(visualContext){visualContext.getSharedPreferences("visual-effects-v1",android.content.Context.MODE_PRIVATE)}
    var navRefraction by remember(visualPrefs){mutableStateOf(visualPrefs.getBoolean("nav-refraction",true))}
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var directoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var hiddenCleaner by rememberSaveable { mutableStateOf(false) }
    val offlineEnabled=vm.offlineSourceEnabled()
    var hiddenClearTarget by remember(state.connectionRevision,offlineEnabled){mutableStateOf<Book?>(null)}
    var addOpds by rememberSaveable{mutableStateOf(false)}
    var addLegado by rememberSaveable{mutableStateOf(false)}
    var sourceManager by rememberSaveable{mutableStateOf<String?>(null)}
    var sourceInfo by rememberSaveable { mutableStateOf(false) }
    var downloadId by rememberSaveable { mutableStateOf<String?>(null) }
    var clearDownloadId by rememberSaveable { mutableStateOf<String?>(null) }
    val shelfListState=rememberLazyListState()
    val findListState=rememberLazyListState()
    val settingsScrollState=rememberScrollState()
    var findSearch by rememberSaveable(state.connectionRevision,state.catalogId) { mutableStateOf(state.query) }
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
    LaunchedEffect(state.membershipFeedback){state.membershipFeedback?.let{message->snackbar.showSnackbar(message);vm.consumeMembershipFeedback(message)}}
    var shelfActionId by remember(state.connectionRevision, state.topTab, reading) { mutableStateOf<String?>(null) }
    val offlineRevision by OfflineEvents.revision.collectAsStateWithLifecycle()
    LaunchedEffect(state.connectionRevision,offlineEnabled,state.topTab,reading) {
        if(!offlineEnabled || state.topTab!="settings" || reading){hiddenCleaner=false;hiddenClearTarget=null}
    }
    val allBooks = (state.shelfBooks + state.books).distinctBy { it.key }
    val detail = allBooks.find { it.key == detailId }
    val directory = allBooks.find { it.key == directoryId }
    val read: (Book, String?) -> Unit = { book, chapter -> detailId = null; directoryId = null; sheet = null; menu = false; vm.open(book, chapter) }
    val homeDark=glassColors().dark
    LaunchedEffect(reading, menu, sheet, state.settings.palette,homeDark) { bars(reading && !menu && sheet == null, if(reading)state.settings.palette == 3 else homeDark) }
    BackHandler(managing || reading || detail != null || directory != null || state.topTab != "shelf") {
        when { managing -> vm.endShelfManagement(); sheet != null -> sheet = null; menu -> menu = false; reading -> vm.backToShelf(); directory != null -> directoryId = null; detail != null -> detailId = null; state.topTab=="find" && state.catalogCanBack -> vm.catalogBack(); else -> vm.selectTab("shelf") }
    }
    val glassFrame:()->Long={shelfListState.firstVisibleItemIndex.toLong()*1000003+shelfListState.firstVisibleItemScrollOffset+findListState.firstVisibleItemIndex.toLong()*1000033+findListState.firstVisibleItemScrollOffset+settingsScrollState.value}
    GlassScene(navRefraction && !reading && detail==null && directory==null,glassFrame,
        motionSample={when(state.topTab){"shelf"->glassMotionSample("shelf",shelfListState);"find"->glassMotionSample("find",findListState);else->glassMotionSample("settings",settingsScrollState)}}) {
    Surface(Modifier.fillMaxSize(), color = HomeCanvas,contentColor=HomeInk) {
        when {
            reading -> ReadingScreen(state, vm, menu, { menu = !menu }, { sheet = it },sheet!=null)
            directory != null -> DirectoryScreen(directory, vm, { directoryId = null }) { id -> read(directory, id) }
            detail != null -> BookDetails(detail, vm, { detailId = null }, { directoryId = detail.key },{downloadId=it.key},{clearDownloadId=it.key}) { read(detail, null) }
            else -> RefractionHomeScaffold(state.topTab,vm::selectTab,navRefraction,
                frameSignal=glassFrame,
                snackbar={SnackbarHost(snackbar)}) { padding ->
                val direction=androidx.compose.ui.platform.LocalLayoutDirection.current
                val body=Modifier.padding(start=padding.calculateStartPadding(direction),top=padding.calculateTopPadding(),end=padding.calculateEndPadding(direction))
                val bottom=padding.calculateBottomPadding()
                when(state.topTab) {
                    "settings" -> HomeSettings(state,vm,body,settingsScrollState,{hiddenCleaner=true},source={sourceInfo=true},addCatalog={sourceManager="opds"},addRules={sourceManager="legado"},bottomSpace=bottom,refraction=navRefraction,refractionChange={navRefraction=it;visualPrefs.edit().putBoolean("nav-refraction",it).apply()})
                    "find" -> FindBooks(state,vm,body,findListState,findSearch,{findSearch=it;vm.clearSearch()},{detailId=it.key},{read(it,null)},{directoryId=it.key},{vm.addToShelf(it)},bottomSpace=bottom)
                    else -> Shelf(state,vm,body,shelfListState,{read(it,null)},{shelfActionId=it.key},managing,selected.map{it.key}.toSet(),
                        manage=vm::manageShelf,exit=vm::endShelfManagement,toggle=vm::toggleShelfSelection,selectAll=vm::selectAllShelf,
                        download={batchDownloadKeys=selected.map{it.key}},remove={removeKeys=selected.map{it.key}},bottomSpace=bottom)
                }
            }
        }
    }
    if (sheet == "toc" && state.book != null) {
        ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = HomeCanvas,contentColor=HomeInk) {
            DirectoryContent(state.book!!, vm, Modifier.fillMaxWidth().heightIn(max = 500.dp)) { id -> read(state.book!!, id) }
        }
    }
    if (sheet == "settings") ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = HomeCanvas,contentColor=HomeInk) { ReaderControls(state.settings, vm::settings) }
    if(hiddenCleaner && offlineEnabled)HiddenCopyCleaner(state,vm,{hiddenCleaner=false}) { hiddenClearTarget=it }
    if(addOpds)OpdsSubscriptionDialog(state,vm){addOpds=false}
    sourceManager?.let{kind->
        val providers=vm.publicSources().filter{if(kind=="opds")it is OpdsSource else it is LegadoSource}
        SourceManagement(kind,providers,{sourceManager=null},add={sourceManager=null;if(kind=="opds")addOpds=true else addLegado=true},open={id->sourceManager=null;vm.selectCatalog(id);vm.selectTab("find")})
    }
    if(addLegado)LegadoImportScreen(state,vm){addLegado=false}
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
        AlertDialog(onDismissRequest={downloadId=null},title={DisplayText("下载整本")},text={Column(Modifier.heightIn(max=minOf(360.dp,androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp*.5f)).verticalScroll(rememberScrollState())){
            DisplayText(book.title)
            DisplayText(if(vm.isPublicBook(book))"按当前书源下载整本，完成后可离线阅读。"else"默认只在不计费 Wi-Fi 下下载。任务由系统后台安排，已校验内容可在中断后继续使用。",fontSize=13.sp,modifier=Modifier.padding(vertical=12.dp))
            DownloadNetworkChoice(metered){metered=it}
        }},confirmButton={TextButton(onClick={vm.download(book,metered);downloadId=null}){DisplayText(if(vm.isPublicBook(book))"下载"else"加入下载队列")}},dismissButton={TextButton(onClick={downloadId=null}){DisplayText("取消")}})
    }
    batchDownloadKeys?.let { keys ->
        val targets=state.shelfBooks.filter{it.key in keys && vm.downloadsAvailable(it)}
        var metered by remember(keys){mutableStateOf(false)}
        val revision=state.connectionRevision
        AlertDialog(onDismissRequest={batchDownloadKeys=null},modifier=Modifier.testTag("batch-download-dialog"),title={DisplayText("批量下载")},
            text={Column(Modifier.heightIn(max=minOf(360.dp,androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp*.5f)).verticalScroll(rememberScrollState())) {
                DisplayText(if(targets.any{vm.isPublicBook(it)})"选中 ${keys.size} 本，可下载 ${targets.size} 本。已有完整副本或在途任务会跳过。"else"选中 ${keys.size} 本，当前账号可下载 ${targets.size} 本。已有完整下载或在途任务会跳过。",fontSize=14.sp)
                DisplayText(targets.joinToString("、"){it.title},fontSize=12.sp,color=HomeMuted,maxLines=4,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(vertical=12.dp))
                DownloadNetworkChoice(metered){metered=it}
            }},confirmButton={TextButton(enabled=targets.isNotEmpty(),onClick={vm.downloadSelection(keys.toSet(),revision,metered);batchDownloadKeys=null}){DisplayText(if(targets.any{vm.isPublicBook(it)})"开始下载"else"加入下载队列")}},
            dismissButton={TextButton(onClick={batchDownloadKeys=null}){DisplayText("取消")}})
    }
    removeKeys?.let { keys ->
        val targets=state.shelfBooks.filter{it.key in keys};val revision=state.connectionRevision
        GlassRemoveConfirmation(targets.isNotEmpty() && targets.none{vm.membershipBusy(it)},dismiss={removeKeys=null},
            remove={vm.removeFromShelf(keys.toSet(),revision);removeKeys=null})
    }
    (hiddenClearTarget ?: allBooks.firstOrNull{it.key==clearDownloadId})?.let { book->
        val hidden=hiddenClearTarget!=null
        val revision=state.connectionRevision
        val close={hiddenClearTarget=null;clearDownloadId=null}
        AlertDialog(onDismissRequest=close,title={DisplayText("清除下载")},text={Column(Modifier.heightIn(max=androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp*.5f).verticalScroll(rememberScrollState())) {
            DisplayText(book.title,modifier=Modifier.padding(bottom=12.dp))
            DisplayText(if(hidden)"清除当前账号这本已移出书架的书的缓存、当前进度和历史恢复位置，并取消下载任务。保持已移出状态，不影响登录和其他书。" else "清除此书的本机缓存、当前阅读进度和历史恢复位置，并取消下载任务。保留书架项和登录，下次从头阅读。")
        }},confirmButton={TextButton(onClick={if(hidden)vm.clearHiddenDownload(book,revision) else vm.clearDownload(book);close()}){DisplayText("清除缓存与进度")}},dismissButton={TextButton(onClick=close){DisplayText("取消")}})
    }
    var confirmLocalChoice by remember(state.connectionRevision,state.shelfDifference?.token){mutableStateOf(false)}
    state.shelfDifference?.takeIf{state.showShelfChoice}?.let { difference ->
        val enabled=!state.shelfChoiceBusy && vm.bookHubConnected() && !state.offline
        if(!confirmLocalChoice)AlertDialog(onDismissRequest=vm::postponeShelfDifference,modifier=Modifier.testTag("shelf-difference"),title={DisplayText("书架不一致")},text={
            Column(Modifier.heightIn(max=androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp*.5f).verticalScroll(rememberScrollState())) {
                DisplayText("本机多 ${difference.addRemote.size} 本 · BookHub 多 ${difference.removeRemote.size} 本",fontSize=13.sp,color=HomeMuted)
                if(difference.skipped.isNotEmpty())DisplayText("${difference.skipped.size} 本无法识别，将跳过",fontSize=12.sp,color=HomeMuted)
                TextButton(onClick={if(difference.removeRemote.isNotEmpty())confirmLocalChoice=true else vm.chooseShelfDifference(difference.token,true)},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("shelf-choice-local")){DisplayText("以本机为准")}
                TextButton(onClick={vm.chooseShelfDifference(difference.token,false)},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("shelf-choice-merge")){DisplayText("从 BookHub 补到本机")}
            }
        },confirmButton={TextButton(onClick=vm::postponeShelfDifference,modifier=Modifier.testTag("shelf-choice-later")){DisplayText("暂不")}})
        if(confirmLocalChoice)AlertDialog(onDismissRequest={confirmLocalChoice=false},modifier=Modifier.testTag("shelf-local-confirm"),title={DisplayText("从 BookHub 移出 ${difference.removeRemote.size} 本？")},text={
            Column(Modifier.heightIn(max=androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp*.5f).verticalScroll(rememberScrollState())) {
                DisplayText("仅改书架名单，保留本机缓存和进度。",fontSize=12.sp,color=HomeMuted,modifier=Modifier.padding(bottom=12.dp))
                difference.removeRemote.forEach{DisplayText(it.title,fontSize=14.sp,modifier=Modifier.padding(vertical=4.dp))}
            }
        },confirmButton={TextButton(enabled=enabled,onClick={confirmLocalChoice=false;vm.chooseShelfDifference(difference.token,true)},modifier=Modifier.testTag("shelf-local-execute")){DisplayText("移出并同步")}},dismissButton={TextButton(onClick={confirmLocalChoice=false}){DisplayText("返回")}})
    }
    if (state.notice != null) AlertDialog(onDismissRequest = vm::dismissNotice, text = { DisplayText(state.notice!!) },
        confirmButton = { TextButton(onClick = vm::dismissNotice) { DisplayText("知道了") } })
    }
}

@OptIn(ExperimentalFoundationApi::class,ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable
private fun Shelf(state: ReaderState, vm: ReaderViewModel, modifier: Modifier, listState: LazyListState,
    read: (Book) -> Unit, actions:(Book)->Unit,managing:Boolean,selected:Set<String>,manage:()->Unit,exit:()->Unit,
    toggle:(String)->Unit,selectAll:()->Unit,download:()->Unit,remove:()->Unit,bottomSpace:androidx.compose.ui.unit.Dp=0.dp) {
    PullToRefreshBox(isRefreshing=state.shelfLoading || state.syncingShelf,onRefresh=vm::refreshShelf,modifier=modifier.fillMaxSize().testTag("shelf-refresh").semantics {
        customActions=listOf(CustomAccessibilityAction("刷新书架"){vm.refreshShelf();true})
    }) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp).testTag("shelf-list").semantics{this[GlassMotionObservation]=glassMotionSample("shelf",listState)},state=listState,contentPadding=PaddingValues(bottom=bottomSpace)) {
        item {
            DisplayText("书架",fontSize=26.sp,lineHeight=32.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=18.dp))
            if(managing) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    DisplayText("已选 ${selected.size} 本",fontSize=15.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f).testTag("shelf-selected-count"))
                    TextButton(onClick=exit,modifier=Modifier.testTag("shelf-manage-exit")){DisplayText("退出管理")}
                }
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick=selectAll,enabled=state.shelfBooks.isNotEmpty(),modifier=Modifier.testTag("shelf-select-all")){DisplayText(if(selected.size==state.shelfBooks.size && selected.isNotEmpty())"取消全选" else "全选当前列表")}
                    FilledTonalButton(colors=ButtonDefaults.filledTonalButtonColors(containerColor=Color(0xFFEFE9E1),contentColor=Vermilion),onClick=download,enabled=state.shelfBooks.any{it.key in selected && vm.downloadsAvailable(it)},modifier=Modifier.testTag("shelf-batch-download")){DisplayText("下载选中")}
                    TextButton(onClick=remove,enabled=selected.isNotEmpty(),modifier=Modifier.testTag("shelf-batch-remove")){DisplayText("移出书架")}
                }
                DisplayText("${state.shelfBooks.size} 本 · 点书籍选择，可多选",color=HomeMuted,fontSize=11.sp,lineHeight=16.sp,modifier=Modifier.padding(top=6.dp,bottom=12.dp))
            } else {
                Row(Modifier.fillMaxWidth().padding(bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    DisplayText("${state.shelfBooks.size} 本 · 最近阅读排序"+if(state.remote && state.offline)" · 离线" else "",color=HomeMuted,fontSize=11.sp,lineHeight=16.sp,modifier=Modifier.weight(1f).testTag("shelf-book-count"))
                    TextButton(onClick=manage,modifier=Modifier.testTag("shelf-manage")){DisplayText("管理",fontSize=13.sp)}
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
            HorizontalDivider(color=HomeLine,thickness=.5.dp,modifier=Modifier.glassPart())
        }
        if(state.shelfHasMore && !state.shelfLoading)item{TextButton(onClick=vm::loadMoreShelf,modifier=Modifier.fillMaxWidth()){DisplayText("加载更多书架")}}
        if(state.localShelfWarning!=null)item{DisplayText(state.localShelfWarning,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
        if(state.shelfError!=null)item{Column(Modifier.padding(vertical=16.dp)){DisplayText(state.shelfError,color=MaterialTheme.colorScheme.error,fontSize=12.sp);TextButton(onClick=vm::refreshShelf){DisplayText("重试")}}}
        if(!state.shelfLoading && state.shelfBooks.isEmpty() && state.shelfError==null)item{
            DisplayText(if(state.remote || vm.publicSources().isNotEmpty())"书架还是空的，到找书页选择一本吧。" else "书架还是空的，登录 BookHub 后即可找书。",color=HomeMuted,fontSize=if(glassColors().dark)16.sp else 14.sp,lineHeight=if(glassColors().dark)androidx.compose.ui.unit.TextUnit.Unspecified else 22.sp,modifier=Modifier.padding(top=30.dp))
            TextButton(onClick={vm.selectTab("find")},modifier=Modifier.testTag("empty-shelf-find")){DisplayText("去找书")}
        }
        item{Spacer(Modifier.height(20.dp))}
    }
    }
}

/** Shelf and search use the same cover dimensions, typography, gaps and summary. */
@Composable
private fun CompactBookRow(book:Book,state:ReaderState,vm:ReaderViewModel,modifier:Modifier,summary:String,displayTitle:String=book.title,
    trailing:@Composable RowScope.()->Unit={}) {
    Row(modifier.fillMaxWidth().padding(vertical=7.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        val cover by produceState<android.graphics.Bitmap?>(null,book.key,vm,state.connectionRevision,state.shelfRevision,state.downloadRevision){value=null;value=vm.shelfCover(book)}
        ShelfCover(book,cover,displayTitle)
        Column(Modifier.weight(1f)) {
            DisplayText(displayTitle,fontSize=16.sp,lineHeight=21.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("book-title-${book.id}"))
            DisplayText(book.author.ifBlank{"作者未提供"},color=HomeMuted,fontSize=12.sp,lineHeight=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=3.dp))
            DisplayText(summary.replace(Regex("\\s+")," ").trim(),color=HomeMuted.copy(alpha=.85f),fontSize=11.sp,lineHeight=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=3.dp))
        }
        trailing()
    }
}

@Composable
private fun DownloadNetworkChoice(metered:Boolean,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("download-wifi").selectable(selected=!metered,onClick={change(false)},role=Role.RadioButton),verticalAlignment=Alignment.CenterVertically){RadioButton(selected=!metered,onClick=null);DisplayText("仅不计费 Wi-Fi")}
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("download-metered").selectable(selected=metered,onClick={change(true)},role=Role.RadioButton),verticalAlignment=Alignment.CenterVertically){RadioButton(selected=metered,onClick=null);DisplayText("允许本次使用流量或其他网络")}
}

/** Local typographic cover placeholder: never fetches images or opens a reading package. */
@Composable
private fun ShelfCover(book:Book,image:android.graphics.Bitmap?,displayTitle:String=book.title) {
    Box(Modifier.size(48.dp,68.dp).glassPart().clearAndSetSemantics{coverSemanticsTag=if(image==null)"shelf-placeholder-${book.id}" else "shelf-real-cover-${book.id}"}.clip(RoundedCornerShape(6.dp)).background(Color(book.color.takeIf{it ushr 24!=0L}?:0xFF536B67))) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.White.copy(alpha=.1f),size=androidx.compose.ui.geometry.Size(size.width*.12f,size.height))
            drawLine(Color.White.copy(alpha=.23f),Offset(size.width*.2f,size.height*.82f),Offset(size.width*.8f,size.height*.82f),1.dp.toPx())
        }
        androidx.compose.material3.Text(app.leafreader.text.ChineseDisplay.text(androidx.compose.ui.platform.LocalContext.current,displayTitle).take(9).chunked(3).joinToString("\n"),fontFamily=FontFamily.Serif,fontWeight=FontWeight.SemiBold,color=Color(0xFFFFF5DF),fontSize=10.sp,lineHeight=15.sp,maxLines=if(androidx.compose.ui.platform.LocalDensity.current.fontScale>=1.8f)1 else if(androidx.compose.ui.platform.LocalDensity.current.fontScale>=1.35f)2 else 3,modifier=Modifier.align(Alignment.TopCenter).padding(top=12.dp))
        if(image!=null)Image(image.asImageBitmap(),contentDescription=null,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().background(Color(0xFFEEE9DF)))
    }
}

@Composable
private fun HomeSettings(state:ReaderState,vm:ReaderViewModel,modifier:Modifier,scroll:androidx.compose.foundation.ScrollState,downloads:()->Unit,source:()->Unit,addCatalog:()->Unit={},addRules:()->Unit={},bottomSpace:androidx.compose.ui.unit.Dp=0.dp,refraction:Boolean=true,refractionChange:(Boolean)->Unit={}) {
    val versionContext=LocalContext.current
    val installedVersion=remember(versionContext){runCatching{versionContext.packageManager.getPackageInfo(versionContext.packageName,0).versionName?.takeIf{it.isNotBlank()}}.getOrNull()?:"—"}
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
    Column(modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal=24.dp).padding(bottom=bottomSpace).testTag("settings-page")) {
        DisplayText("设置",fontSize=26.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=18.dp,bottom=28.dp))
        DisplayText("书源",fontSize=12.sp,color=HomeMuted)
        SourceSettingsRow("BookHub",vm.bookHubConnectionLabel(),"settings-source",open=source)
        val gutenberg=vm.publicSources().firstOrNull{it.id==GutenbergSource.SOURCE}
        SourceSettingsRow("古登堡计划","中文公版书","settings-gutenberg",enabled=gutenberg!=null){gutenberg?.let{vm.selectCatalog(it.id);vm.selectTab("find")}}
        SourceSettingsRow("OPDS 订阅","${vm.publicSources().count{it is OpdsSource}} 个订阅","settings-opds",open=addCatalog)
        SourceSettingsRow("Legado 书源","${vm.publicSources().count{it is LegadoSource}} 个书源","settings-legado",open=addRules)

        Spacer(Modifier.height(20.dp))
        DownloadPreferenceRow("加书后自动下载",state.automaticWifi,true,"automatic-wifi",vm::automaticWifi)
        DownloadPreferenceRow("仅在不计费 Wi-Fi 下自动下载",state.automaticWifiOnly,state.automaticWifi,"automatic-wifi-only",vm::automaticWifiOnly)
        if(usage.available) {
            DisplayText("离线副本总占用："+OfflineUsageFormatting.bytes(usage.totalBytes),fontSize=15.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=18.dp).testTag("offline-total-bytes"))
            DisplayText("本机全部书源 · 含部分缓存与历史版本",fontSize=11.sp,lineHeight=18.sp,color=HomeMuted)
        } else if(!loading && !error)DisplayText("登录原账号后可查看离线副本",fontSize=12.sp,color=HomeMuted,modifier=Modifier.padding(top=18.dp))
        if(error)DisplayText("占用统计失败，请重试",fontSize=12.sp,color=HomeMuted)
        TextButton(onClick={reload++},enabled=!loading,modifier=Modifier.testTag("offline-refresh-usage")){DisplayText(if(loading)"正在统计本机占用…" else "刷新本机占用")}
        TextButton(onClick=downloads,enabled=enabled,modifier=Modifier.testTag("offline-management")){DisplayText("清理已移出书架的副本")}
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
            DisplayText("玻璃效果",fontSize=16.sp,modifier=Modifier.weight(1f))
            Switch(checked=refraction,onCheckedChange=refractionChange,enabled=android.os.Build.VERSION.SDK_INT>=33,modifier=Modifier.testTag("nav-refraction-toggle").semantics{contentDescription="玻璃效果"})
        }
        Row(Modifier.fillMaxWidth().padding(top=24.dp,bottom=20.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            DisplayText("版本",fontSize=12.sp,color=HomeMuted)
            DisplayText(installedVersion,fontSize=12.sp,color=HomeMuted,modifier=Modifier.testTag("settings-version-value"))
        }
        Spacer(Modifier.height(28.dp))
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
    AlertDialog(onDismissRequest=dismiss,modifier=Modifier.testTag("hidden-copy-cleaner"),title={DisplayText("清理已移出书架的副本")},text={
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=440.dp).testTag("hidden-copy-list")) {
            item {DisplayText("仅当前启用服务与账号中已移出的书。按文件大小列出，不逐本校验正文；清除会单独确认，同时删除该书缓存和进度，保持已移出状态。",fontSize=12.sp,lineHeight=20.sp)}
            items(snapshot.books,key={it.key}){book->
                Column(Modifier.fillMaxWidth().padding(vertical=8.dp)) {
                    DisplayText(book.title,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                    DisplayText("占用："+OfflineUsageFormatting.bytes(snapshot.bytesByBook[book.key]?:0L),fontSize=12.sp,modifier=Modifier.testTag("offline-book-bytes-${book.id}"))
                    vm.downloadTaskLabel(book)?.let{DisplayText(it,fontSize=12.sp,color=HomeMuted)}
                    TextButton(onClick={clear(book)},enabled=!loading && !error,modifier=Modifier.testTag("hidden-clear-${book.id}")){DisplayText("清除副本…")}
                }
            }
            if(loading)item{DisplayText("正在读取已移出项目的本机占用…",fontSize=12.sp)}
            else if(error)item{DisplayText("读取失败，原文件与进度保留；已有项目为上次快照。",fontSize=12.sp);TextButton(onClick={reload++}){DisplayText("重试")}}
            else if(snapshot.books.isEmpty())item{DisplayText("没有当前账号已移出书架的副本或待取消任务。",fontSize=12.sp,modifier=Modifier.testTag("hidden-copy-empty"))}
        }
    },confirmButton={TextButton(onClick=dismiss){DisplayText("关闭")}})
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
    val clearing=book.key in state.clearingBooks
    val memberBusy=vm.membershipBusy(book)
    val pendingCleanup=vm.cleanupPending(book)
    val content=copy?.let{if(it.complete)"完整缓存" else if(it.saved>0)"部分缓存" else "未缓存"}
    val headline=when {
        clearing->"正在清理"
        pendingCleanup->"清理未完成"
        copy?.complete==true->"完整缓存"
        task!=null->task.substringBefore(" · ")
        inspectionError->"状态暂不可用"
        copy==null->"缓存状态…"
        else->content.orEmpty()
    }
    val moss=if(glassColors().dark)Color(0xFFB6D6BC)else Color(0xFF506D5C)
    val tint=when {copy?.complete==true->moss;inspectionError || task?.startsWith("下载失败")==true->glassColors().accent;else->HomeMuted}
    val maxHeight=androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp*.86f
    val glassBackdrop=LocalGlassBackdrop.current
    val readability=remember{GlassReadability()}
    ModalBottomSheet(onDismissRequest=dismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=Color.Transparent,contentColor=HomeInk,shape=RoundedCornerShape(28.dp),
        contentWindowInsets={WindowInsets.safeDrawing},
        dragHandle=null,
        modifier=Modifier.padding(horizontal=12.dp).padding(bottom=8.dp).testTag("shelf-book-actions")) {
        CompositionLocalProvider(LocalGlassReadability provides readability) {
        Column(Modifier.fillMaxWidth().heightIn(max=maxHeight).softGlass(RoundedCornerShape(28.dp),glassColors(),paintBackground=glassBackdrop==null)
            .refractNav(glassBackdrop,glassColors(),glassBackdrop?.frameSignal?:{0L},cornerDp=28f,clear=true,readability=readability)
            .testTag("book-actions-glass").verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=12.dp)) {
            Box(Modifier.fillMaxWidth().padding(top=10.dp,bottom=10.dp),contentAlignment=Alignment.Center){Box(Modifier.size(32.dp,4.dp).clip(RoundedCornerShape(2.dp)).background(HomeMuted.copy(alpha=.3f)))}
            Row(Modifier.fillMaxWidth().padding(top=4.dp),verticalAlignment=Alignment.Top) {
                Column(Modifier.weight(1f).padding(end=8.dp)) {
                    DisplayText(book.title,fontFamily=FontFamily.Serif,fontSize=20.sp,lineHeight=27.sp,fontWeight=FontWeight.SemiBold,
                        maxLines=if(androidx.compose.ui.platform.LocalDensity.current.fontScale>=1.4f)2 else 3,
                        overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("book-actions-title").glassReadable())

                }
                TextButton(onClick=dismiss,contentPadding=PaddingValues(horizontal=8.dp)){DisplayText("关闭",fontSize=13.sp,color=HomeInk,modifier=Modifier.glassReadable())}
            }
            Column(Modifier.padding(top=14.dp,bottom=12.dp).then(if(recheck>0)Modifier.testTag("download-check-result-${book.id}")else Modifier)
                .semantics{liveRegion=androidx.compose.ui.semantics.LiveRegionMode.Polite}) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Canvas(Modifier.size(6.dp)){drawCircle(tint)}
                    DisplayText(headline,fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium,color=HomeInk,
                        modifier=Modifier.padding(start=8.dp).testTag("download-status-${book.id}").glassReadable())
                }

            }
            if(vm.canManageOffline(book)) {
                if(!clearing && busy)BookActionRow("取消下载",2,Modifier.testTag("cancel-download-${book.id}"),onClick=cancel,highlight=true)
                else if(!clearing && !pendingCleanup && available && copy!=null && copy?.complete!=true)BookActionRow(
                    if(task?.startsWith("下载失败")==true)"重试下载" else if(copy!!.saved>0)"继续下载" else "下载整本",0,
                    Modifier.testTag("download-book-${book.id}"),onClick=download,highlight=true)

            }
            if(vm.canManageOffline(book) || vm.isOnShelf(book)) {
                HorizontalDivider(color=HomeLine,modifier=Modifier.padding(vertical=10.dp).testTag("book-actions-separator"))
                if(vm.canManageOffline(book))BookActionRow("清除下载",null,Modifier.testTag("clear-download-${book.id}"),
                    enabled=!clearing,onClick=clear)
                if(vm.isOnShelf(book)) {
                    if(vm.pendingShelfRemoval(book))BookActionRow("取消移除",null,Modifier.testTag("cancel-remove-${book.id}"),enabled=!clearing,onClick={vm.cancelShelfRemoval(book)})
                    else BookActionRow("移出书架",null,Modifier.testTag("remove-book-${book.id}"),enabled=!clearing && !memberBusy,onClick=remove)
                }
            }
        }
        }
    }
}

@Composable
private fun BookActionRow(label:String,icon:Int?,modifier:Modifier=Modifier,
                          enabled:Boolean=true,highlight:Boolean=false,onClick:()->Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(if(highlight)HomeInk.copy(alpha=.055f)else Color.Transparent)
        .clickable(enabled=enabled,role=Role.Button,onClick=onClick).heightIn(min=52.dp).padding(horizontal=12.dp,vertical=12.dp),
        verticalAlignment=Alignment.CenterVertically) {
        val color=if(enabled)HomeInk else HomeMuted.copy(alpha=.55f)
        if(icon!=null) {
            Canvas(Modifier.size(22.dp).glassReadable()) {
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
            DisplayText(label,fontSize=16.sp,lineHeight=23.sp,color=color,fontWeight=if(highlight)FontWeight.Medium else FontWeight.Normal,modifier=Modifier.glassReadable())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FindBooks(state:ReaderState,vm:ReaderViewModel,modifier:Modifier,listState:LazyListState,search:String,searchChange:(String)->Unit,
    detail:(Book)->Unit,read:(Book)->Unit,directory:(Book)->Unit,add:(Book)->Unit,bottomSpace:androidx.compose.ui.unit.Dp=0.dp) {
    val keyboard=LocalSoftwareKeyboardController.current
    val submit={if(search.isNotBlank()){keyboard?.hide();vm.search(search)}}
    LazyColumn(modifier.fillMaxSize().padding(horizontal=20.dp).testTag("find-list").semantics{this[GlassMotionObservation]=glassMotionSample("find",listState)},state=listState,contentPadding=PaddingValues(bottom=bottomSpace)) {
        item{DisplayText("找书",fontSize=26.sp,lineHeight=32.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=18.dp,bottom=20.dp))}
        if(vm.publicSources().isNotEmpty())item{
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(bottom=12.dp)) {
                FilterChip(selected=state.catalogId=="bookhub",onClick={vm.selectCatalog("bookhub")},label={DisplayText("BookHub")},modifier=Modifier.testTag("source-bookhub"))
                for(provider in vm.publicSources())FilterChip(selected=state.catalogId==provider.id,onClick={vm.selectCatalog(provider.id)},label={DisplayText(provider.label)},modifier=Modifier.testTag(if(provider.id==GutenbergSource.SOURCE)"source-gutenberg"else"source-${provider.id}"))
            }
        }
        if(!state.remote && !state.publicCatalog)item{
            DisplayText("登录 BookHub，搜索并添加书籍到书架。",color=HomeMuted,fontSize=if(glassColors().dark)16.sp else 14.sp,lineHeight=if(glassColors().dark)androidx.compose.ui.unit.TextUnit.Unspecified else 22.sp)
            Button(onClick={vm.selectTab("settings")},modifier=Modifier.testTag("find-login")){DisplayText("前往设置登录")}
        }
        if(state.remote || state.publicCatalog)item{
            DisplayText(if(state.catalogId==GutenbergSource.SOURCE)"中文公版书"else if(state.publicCatalog)state.catalogLabel else"在我的服务中找书",fontSize=12.sp,color=HomeMuted,modifier=Modifier.padding(bottom=8.dp))
            OutlinedTextField(value=search,onValueChange=searchChange,singleLine=true,modifier=Modifier.fillMaxWidth().testTag("find-search-input").semantics{contentDescription="书名或作者"},
                placeholder={DisplayText("输入书名或作者",fontSize=14.sp)},shape=RoundedCornerShape(18.dp),
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={submit()}),
                trailingIcon={Row {
                    if(search.isNotEmpty())IconButton(onClick={searchChange("")},modifier=Modifier.testTag("find-search-clear").semantics{contentDescription="清空搜索"}){DisplayText("×",fontSize=23.sp)}
                    IconButton(onClick=submit,enabled=search.isNotBlank() && !state.findLoading,modifier=Modifier.testTag("find-search-submit").semantics{contentDescription="搜索"}){if(glassColors().dark)DisplayText("⌕",fontSize=25.sp)else HomeSearchGlyph()}
                }})
            Spacer(Modifier.height(20.dp))
        }
        if((state.remote || state.publicCatalog) && state.query.isBlank() && !state.catalogBrowsing)item{
            if(state.publicCatalog&&vm.catalogCanBrowse())TextButton(onClick={vm.browsePublic()},modifier=Modifier.testTag("browse-gutenberg")){DisplayText(if(state.catalogId==GutenbergSource.SOURCE)"浏览中文书目"else"浏览目录")}
            DisplayText("输入关键词并搜索，结果会显示在这里。",color=HomeMuted,fontSize=if(glassColors().dark)13.sp else 14.sp,lineHeight=if(glassColors().dark)androidx.compose.ui.unit.TextUnit.Unspecified else 22.sp,modifier=Modifier.testTag("find-unsubmitted"))}
        if((state.remote || state.publicCatalog) && (state.query.isNotBlank() || state.catalogBrowsing)) {
            if(state.catalogCanBack)item{TextButton(onClick=vm::catalogBack,modifier=Modifier.testTag("catalog-back")){DisplayText("← 返回上级")}}
            items(state.catalogSections,key={it.href}) { section->
                Row(Modifier.fillMaxWidth().clickable{vm.navigateCatalog(section)}.padding(vertical=15.dp).testTag("catalog-section-${section.title}"),horizontalArrangement=Arrangement.SpaceBetween){DisplayText(section.title,modifier=Modifier.weight(1f));DisplayText("›",color=HomeMuted)}
                HorizontalDivider(color=HomeLine)
            }
            if(state.books.isNotEmpty())item{DisplayText(if(state.catalogBrowsing)"书目"else"搜索结果",fontSize=15.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(bottom=8.dp))}
            items(state.books,key={it.key}) { book ->
                Column {
                    CompactBookRow(book,state,vm,Modifier.clickable{detail(book)}.testTag("find-book-${book.id}"),book.description.ifBlank{"暂无简介"},displayTitle=book.title)
                    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                        TextButton(enabled=vm.canAddBook(book),onClick={add(book)},modifier=Modifier.testTag("add-shelf-${book.id}")){DisplayText(if(vm.membershipBusy(book))"正在加入…" else if(vm.canAddBook(book))"加入书架" else "正在确认书架状态")}
                        TextButton(onClick={directory(book)},modifier=Modifier.testTag("find-directory-${book.id}")){DisplayText("目录")}
                        TextButton(onClick={read(book)},modifier=Modifier.testTag("find-read-${book.id}")){DisplayText(if(vm.positionOf(book)==null)"阅读" else "继续阅读")}
                    }
                    HorizontalDivider(color=HomeLine,thickness=.5.dp,modifier=Modifier.glassPart())
                }
            }
            state.catalogNote?.let{note->item{DisplayText(note,color=HomeMuted,fontSize=11.sp,modifier=Modifier.testTag("catalog-note"))}}
            if(state.findLoading)item{CircularProgressIndicator(Modifier.padding(20.dp))}
            if(state.hasMore && !state.findLoading)item{OutlinedButton(onClick=vm::loadMore,modifier=Modifier.fillMaxWidth().testTag("find-load-more")){DisplayText("加载更多")}}
            if(state.findError!=null)item{Column(Modifier.padding(vertical=16.dp)){DisplayText(state.findError,color=MaterialTheme.colorScheme.error,fontSize=12.sp);TextButton(onClick=vm::retrySearch){DisplayText("重试搜索")}}}
            if(!state.findLoading && state.books.isEmpty() && state.catalogSections.isEmpty() && state.findError==null)item{
                DisplayText(if(state.hasMore)"这一页没有可添加的书籍，可继续加载。" else "没有可添加的书籍：可能已在书架，或没有匹配结果。",color=HomeMuted,fontSize=if(glassColors().dark)13.sp else 14.sp,lineHeight=if(glassColors().dark)androidx.compose.ui.unit.TextUnit.Unspecified else 22.sp,modifier=Modifier.testTag("find-empty"))
            }
            item{DisplayText(if(state.publicCatalog)"仅加入本机书架"else"加入书架保存在当前服务与账号的本机书架。",color=HomeMuted,fontSize=11.sp,modifier=Modifier.padding(top=16.dp,bottom=25.dp))}
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
        androidx.compose.material3.Text(if (compact) app.leafreader.text.ChineseDisplay.text(androidx.compose.ui.platform.LocalContext.current,book.title).take(2) else app.leafreader.text.ChineseDisplay.text(androidx.compose.ui.platform.LocalContext.current,book.title).chunked(2).joinToString("\n"), color = Color(0xFFFFF5DF),
            fontSize = if (compact) 14.sp else 18.sp, lineHeight = if (compact) 20.sp else 26.sp,
            maxLines = if (compact) 1 else 4, overflow = TextOverflow.Ellipsis,
            fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopStart).padding(if (compact) 8.dp else 14.dp, if (compact) 12.dp else 15.dp))
        DisplayText(if (compact) "栖 页" else "栖 页 · 阅 读", color = Color.White.copy(alpha = .65f), fontSize = 6.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
    }
}

@Composable
private fun DownloadPreferenceRow(label:String,checked:Boolean,enabled:Boolean,tag:String,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp)
        .then(Modifier.toggleable(value=checked,enabled=enabled,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=change))
        .testTag(tag).padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        DisplayText(label,fontSize=16.sp,lineHeight=22.sp,color=if(enabled)HomeInk else HomeMuted,modifier=Modifier.weight(1f))
        Switch(checked=checked,onCheckedChange=null,enabled=enabled)
    }
}

@Composable
private fun BookDetails(book:Book,vm:ReaderViewModel,back:()->Unit,directory:()->Unit,download:(Book)->Unit,clear:(Book)->Unit,read:()->Unit) {
    var display by remember(book.key){mutableStateOf(book)};var error by remember(book.key){mutableStateOf<String?>(null)};var reload by remember(book.key){mutableIntStateOf(0)}
    LaunchedEffect(book.key,reload){error=null;try{display=vm.bookDetails(book)}catch(e:CancellationException){throw e}catch(e:Exception){error=if(e is SourceProblem)e.message else "详情读取失败"}}
    Column(Modifier.fillMaxSize()) {
        error?.let{DisplayText(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(16.dp));TextButton(onClick={reload++}){DisplayText("重试详情")}}
        Box(Modifier.weight(1f)){BookDetailsBody(display,vm,back,directory,download,clear,read)}
    }
}
@Composable
private fun BookDetailsBody(book: Book, vm: ReaderViewModel, back: () -> Unit, directory: () -> Unit,download:(Book)->Unit,clear:(Book)->Unit, read: () -> Unit) {
    val state by vm.ui.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)) {
        item {
            TextButton(onClick = back, contentPadding = PaddingValues(0.dp)) { DisplayText("←  返回") }
            Row(Modifier.padding(top = 22.dp, bottom = 25.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                var cover by remember(book.key,state.connectionRevision){mutableStateOf<android.graphics.Bitmap?>(null)}
                LaunchedEffect(book,state.shelfRevision,state.connectionRevision){cover=vm.shelfCover(book)}
                if(cover!=null)Image(cover!!.asImageBitmap(),null,Modifier.size(106.dp,154.dp),contentScale=ContentScale.Fit) else BookCover(book, Modifier.size(106.dp, 154.dp))
                Column(Modifier.padding(top = 10.dp)) {
                    DisplayText(book.title, fontSize = 27.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                    DisplayText(book.author, color = HomeMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                    DisplayText(book.format, color = HomeMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp))
                    DisplayText(if(vm.isPublicBook(book))vm.sourceCaption(book)else state.sourceLabel, color = Vermilion, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }
            DisplayText(book.description, color = HomeMuted, lineHeight = 25.sp, fontSize = 14.sp)
            Button(onClick = read, modifier = Modifier.fillMaxWidth().padding(top = 26.dp).heightIn(min=49.dp).testTag("detail-read")) {
                DisplayText(if (vm.positionOf(book) == null) "阅读" else "继续阅读")
            }
            OutlinedButton(onClick = directory, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("detail-directory")) { DisplayText("目录") }
            OutlinedButton(onClick = { vm.addToShelf(book) }, enabled = !vm.isOnShelf(book) && vm.canAddBook(book), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { DisplayText(if (vm.isOnShelf(book)) "已在书架" else if(vm.membershipBusy(book))"正在加入…" else "加入书架") }
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
        DisplayText(verified?.let{if(it.complete)"完整缓存" else if(it.saved>0)"部分缓存 · 可用章节见目录" else "未缓存"}?:if(verifyError)"状态暂不可用" else "缓存状态…",fontSize=11.sp,color=HomeMuted,modifier=Modifier.testTag("download-status-${book.id}"))
        Row(horizontalArrangement=Arrangement.End,modifier=Modifier.fillMaxWidth()) {
            if(vm.downloadBusy(book))TextButton(onClick={vm.cancelDownload(book)}){DisplayText("取消下载")}
            else if(verified!=null && verified?.complete!=true && !vm.cleanupPending(book))TextButton(onClick={download(book)},modifier=Modifier.testTag("download-book-${book.id}")){DisplayText("下载整本 / 重试")}
            TextButton(onClick={clear(book)},enabled=book.key !in state.clearingBooks,modifier=Modifier.testTag("clear-download-${book.id}")){DisplayText("清除下载")}
        }
    }
}

@Composable
private fun DirectoryScreen(book: Book, vm: ReaderViewModel, back: () -> Unit, read: (String) -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TextButton(onClick = back, modifier = Modifier.padding(horizontal = 15.dp)) { DisplayText("←  返回") }
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
    val state by vm.ui.collectAsStateWithLifecycle()
    val cacheRevision by OfflineEvents.revision.collectAsStateWithLifecycle()
    val copy by produceState<OfflineStatus?>(null,book.key,chapters,state.connectionRevision,cacheRevision) {
        try{value=if(chapters.isEmpty())null else vm.inspectDownload(book,chapters.first().id)}catch(e:CancellationException){throw e}catch(_:Exception){value=OfflineStatus()}
    }
    val listState=rememberLazyListState()
    LaunchedEffect(chapters){val index=chapters.indexOfFirst{it.id==vm.positionOf(book)?.chapterId};if(index>=0)listState.scrollToItem(index+1)}
    LazyColumn(modifier.fillMaxWidth().testTag("directory-list"),state=listState) {
        item {
            DisplayText("目录", Modifier.padding(horizontal = 24.dp), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            DisplayText(book.title, Modifier.padding(24.dp, 8.dp), color = HomeMuted, fontSize = 12.sp)
            DisplayText(if(copy?.complete==true)"完整缓存" else "各章状态只检查本机资源；未缓存完整的章节需联网。",fontSize=12.sp,color=HomeMuted,modifier=Modifier.padding(horizontal=24.dp,vertical=8.dp).testTag("directory-cache-state"))
            TextButton(enabled=!loading,onClick={reload++},modifier=Modifier.padding(horizontal=16.dp)){DisplayText("刷新目录")}
        }
        items(chapters, key = { it.id }) { chapter ->
            val cached by produceState("读取状态…",book.key,chapter.id,state.connectionRevision,cacheRevision,copy?.complete) {
                value=vm.chapterOfflineStatus(book,chapter.id)
            }
            Row(Modifier.fillMaxWidth().clickable(enabled=!loading) { read(chapter.id) }.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Column(Modifier.weight(1f)) {
                    DisplayText(chapter.title, color = if (chapter.id == vm.positionOf(book)?.chapterId) glassColors().accent else HomeInk, fontSize = 14.sp)
                    if(cached.isNotBlank() && !(copy?.complete==true && cached=="可离线读"))DisplayText(cached,fontSize=11.sp,color=HomeMuted,modifier=Modifier.padding(top=5.dp).testTag("chapter-cache-${chapter.id}"))
                }
                DisplayText("→", color = HomeMuted)
            }
        }
        if (loading) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
        if (!loading && chapters.isEmpty() && error == null) item { DisplayText("暂无可读章节。", Modifier.padding(24.dp), color = HomeMuted) }
        if (error != null) item { DisplayText(error!!, Modifier.padding(24.dp), color = Vermilion); TextButton(onClick = { reload++ }) { DisplayText("重试目录") } }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun ReadingScreen(state: ReaderState, vm: ReaderViewModel, menu: Boolean, toggleMenu: () -> Unit, sheet: (String) -> Unit,overlayOpen:Boolean=false) {
    val paper = papers[state.settings.palette]
    Box(Modifier.fillMaxSize().background(Color(paper.background)).safeDrawingPadding()) {
        val chapter = state.chapter
        val originalText=remember(chapter){chapter?.text.orEmpty()}
        if (chapter != null && !state.loading && state.error == null) {
            AndroidView(factory = { context -> NativeReaderView(context) }, modifier = Modifier.fillMaxSize().testTag("native-reader"), update = { view ->
                view.setReadingInput(!menu && !overlayOpen,!overlayOpen)
                view.onBeforeTurn = vm::allowPageTurn
                view.onBoundary = vm::boundary; view.onMenu = toggleMenu; view.onPosition = vm::position
                view.onVisualPosition = vm::visualPosition
                view.bind(originalText, "${state.book!!.title} · ${chapter.title}", state.offset, state.revision, state.settings,
                    "BookHub · 本机进度",chapter.graphics,state.graphicId,chapter.paragraphs.isEmpty() && chapter.graphics.isNotEmpty())
                view.updateProgressContext(state.chapters.indexOfFirst { it.id == chapter.id }, state.chapters.size,
                    state.chaptersComplete && state.progressOrderReliable, state.chapterLengths)
            })
        }
        if (menu) {
            Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), color = Cream, contentColor=Ink, shadowElevation = 4.dp) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::backToShelf) { DisplayText("←  书架") }
                    DisplayText(state.book!!.title, Modifier.weight(1f), fontSize = 15.sp)
                    TextButton(onClick = toggleMenu) { DisplayText("收起") }
                }
            }
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = Cream, contentColor=Ink, shadowElevation = 6.dp) {
                Column(Modifier.padding(16.dp)) {
                    DisplayText("左滑下一页 · 右滑上一页 · 点中间收起菜单", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { vm.boundary(-1) }) { DisplayText("上一章") }
                        TextButton(onClick = { sheet("toc") }) { DisplayText("目录") }
                        TextButton(onClick = { sheet("settings") }) { DisplayText("阅读设置") }
                        TextButton(onClick = { vm.boundary(1) }) { DisplayText("下一章") }
                    }
                }
            }
        }
        if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Vermilion)
        if (state.error != null) Column(Modifier.align(Alignment.Center)) {
            DisplayText("${state.book?.title.orEmpty()} · ${state.book?.format.orEmpty()}", modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 24.dp), fontSize = 13.sp, color = Muted)
            ErrorPanel(vm::retry, vm::backToShelf, state.error)
            if(state.canReturnPage)TextButton(onClick=vm::returnReadablePage,modifier=Modifier.align(Alignment.CenterHorizontally).testTag("return-readable-page")){DisplayText("返回刚才那页")}
            if(state.error.contains("BH_VERSION"))TextButton(onClick=vm::recoverFromStart,modifier=Modifier.align(Alignment.CenterHorizontally)){DisplayText("从首个可读页恢复（保留旧记录）")}
            if(state.error.contains("BH_VERSION") && vm.hasPreservedPosition())TextButton(onClick=vm::restorePreservedPosition,modifier=Modifier.align(Alignment.CenterHorizontally)){DisplayText("恢复保留的旧记录并重试")}
            TextButton(onClick = { sheet("toc") }, modifier = Modifier.align(Alignment.CenterHorizontally)) { DisplayText("主动打开目录") }
        }
    }
}

@Composable
private fun ErrorPanel(retry: () -> Unit, back: () -> Unit, message: String = "请重试。已保存的进度会保留。") {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        DisplayText("这一页，暂时没有打开", fontSize = 20.sp)
        DisplayText(message, color = Muted, modifier = Modifier.padding(vertical = 16.dp))
        Button(onClick = retry) { DisplayText("重试") }
        TextButton(onClick = back) { DisplayText("返回书架") }
    }
}
