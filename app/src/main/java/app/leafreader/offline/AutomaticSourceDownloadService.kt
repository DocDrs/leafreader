package app.leafreader.offline

import android.app.job.JobService
import android.app.job.JobParameters
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class AutomaticSourceDownloadService:JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private data class Entry(val job:Job,val nonce:String,val stopped:AtomicBoolean)
    private val jobs=ConcurrentHashMap<Int,Entry>()
    override fun onStartJob(params:JobParameters):Boolean {
        val manager=AutomaticSourceDownloads.get(this)
        val request=params.extras.getString("request")?.let{manager.request(it)}?:return false
        if(params.extras.getString("nonce")!=request.nonce||request.state !in listOf("QUEUED","WAITING","RUNNING"))return false
        val stopped=AtomicBoolean(false);lateinit var entry:Entry
        val job=scope.launch(start=CoroutineStart.LAZY){var retry=false
            try{retry=manager.execute(request)}finally{jobs.remove(params.jobId,entry);if(!stopped.get()&&manager.request(request.key)?.nonce==request.nonce){jobFinished(params,retry);manager.scheduleMore(if(retry)null else params.jobId)}}}
        entry=Entry(job,request.nonce,stopped);jobs[params.jobId]=entry;job.start();return true
    }
    override fun onStopJob(params:JobParameters):Boolean {
        val manager=AutomaticSourceDownloads.get(this)
        params.extras.getString("request")?.let{manager.request(it)}?.takeIf{it.nonce==params.extras.getString("nonce")}?.let{manager.pause(it)}
        jobs[params.jobId]?.takeIf{it.nonce==params.extras.getString("nonce")}?.let{if(jobs.remove(params.jobId,it)){it.stopped.set(true);it.job.cancel()}}
        return params.extras.getString("request")?.let{manager.request(it)}?.let{it.nonce==params.extras.getString("nonce")&&it.state in listOf("QUEUED","WAITING","RUNNING")}==true
    }
    override fun onDestroy(){jobs.values.forEach{it.stopped.set(true)};scope.cancel();super.onDestroy()}
}
