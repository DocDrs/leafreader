package app.leafreader.offline

import android.app.job.JobService
import android.app.job.JobParameters
import app.leafreader.data.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class OfflineDownloadService:JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private data class Running(val job:Job,val stopped:AtomicBoolean,val nonce:String)
    private val running=ConcurrentHashMap<Int,Running>()
    override fun onStartJob(params:JobParameters):Boolean {
        val manager=OfflineDownloads.get(this)
        val request=params.extras.getString("request")?.let{manager.request(it)}?:return false
        if(params.extras.getString("nonce")!=request.nonce || request.state in listOf("CANCELLED","COMPLETE","FAILED","PAUSED"))return false
        if(request.automatic&&!manager.automaticWifi()){manager.cancel(request.owner,request.bookId);return false}
        val stopped=AtomicBoolean(false)
        lateinit var entry:Running
        val job=scope.launch(start=CoroutineStart.LAZY) {
            var reschedule=false
            try {
                val client=manager.resolveClient(request)
                if(client==null){manager.update(request,"PAUSED","等待对应账号登录");return@launch}
                manager.update(request,"RUNNING","正在准备下载")
                BookDownloader(client,manager.cache(request.owner),manager.connection).download(request.bookId,request.automatic||request.allowMetered,
                    {manager.canTransfer(request,client)}){manager.update(request,"RUNNING",it)}
                manager.update(request,"COMPLETE","整本已下载")
            }catch(e:CancellationException){throw e}
            catch(_:DownloadStopped){manager.update(request,"WAITING",if(request.automatic)GlobalDownloadPreferences(this@OfflineDownloadService).waitingLabel()else"等待 Wi-Fi 或网络恢复");reschedule=true}
            catch(e:Exception) {
                when {
                    e is SourceProblem && e.kind==FailureKind.PREPARING->{manager.update(request,"WAITING","服务正在准备，稍后自动重试");reschedule=true}
                    e is SourceProblem && e.kind in listOf(FailureKind.AUTH,FailureKind.FORBIDDEN)->manager.update(request,"PAUSED","登录或下载权限失效，请重新登录或重试")
                    e is SourceProblem && e.kind==FailureKind.NETWORK && (e.httpStatus==null || e.httpStatus>=500)->{manager.update(request,"WAITING","网络暂不可用，稍后自动重试");reschedule=true}
                    else->manager.update(request,"FAILED",if(e is java.io.IOException && listOf("空间","ENOSPC","No space").any{e.message?.contains(it,ignoreCase=true)==true}) "空间不足，请释放空间后重试" else "下载失败，可重试；已有内容保留")
                }
            }finally {
                running.remove(params.jobId,entry)
                if(!stopped.get()&&manager.request(request.key)?.nonce==request.nonce){jobFinished(params,reschedule);manager.scheduleMore(request.owner,if(reschedule)null else params.jobId)}
            }
        }
        entry=Running(job,stopped,request.nonce);running[params.jobId]=entry;job.start();return true
    }
    override fun onStopJob(params:JobParameters):Boolean {
        val manager=OfflineDownloads.get(this)
        val nonce=params.extras.getString("nonce")
        params.extras.getString("request")?.let{manager.request(it)}?.takeIf{it.nonce==nonce&&it.state in listOf("QUEUED","WAITING","RUNNING")}?.let{manager.update(it,"WAITING","下载已暂停，等待系统继续")}
        running[params.jobId]?.takeIf{it.nonce==nonce}?.let{if(running.remove(params.jobId,it)){it.stopped.set(true);it.job.cancel()}};return params.extras.getString("request")?.let{manager.request(it)}?.let{it.nonce==nonce&&it.state in listOf("QUEUED","WAITING","RUNNING")}==true
    }
    override fun onDestroy(){running.values.forEach{it.stopped.set(true)};scope.cancel();super.onDestroy()}
}
