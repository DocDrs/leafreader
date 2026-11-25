package app.leafreader.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

interface ConnectionState { val online:Boolean; val wifi:Boolean }
object AlwaysOnline:ConnectionState {override val online=true;override val wifi=true}
class DownloadPolicy(val allowed:()->Boolean):AbstractCoroutineContextElement(Key) {companion object Key:CoroutineContext.Key<DownloadPolicy>}
class DownloadIntentGuard(val allowed:()->Boolean):AbstractCoroutineContextElement(Key) {companion object Key:CoroutineContext.Key<DownloadIntentGuard>}
suspend fun checkDownloadPolicy(){coroutineContext.ensureActive();if(coroutineContext[DownloadPolicy]?.allowed?.invoke()==false || coroutineContext[DownloadIntentGuard]?.allowed?.invoke()==false)throw DownloadStopped()}
class AndroidConnectionState(context:Context):ConnectionState {
    private val connectivity=context.getSystemService(ConnectivityManager::class.java)
    private fun capabilities()=connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
    override val online:Boolean get()=capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
    override val wifi:Boolean get()=capabilities()?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }==true
}
