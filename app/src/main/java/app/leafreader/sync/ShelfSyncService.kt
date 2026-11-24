package app.leafreader.sync

import android.app.job.JobService
import android.app.job.JobParameters
import kotlinx.coroutines.*

class ShelfSyncService:JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    // The periodic and one-off jobs may overlap; stopping one must not cancel the other.
    private val tasks=mutableMapOf<Int,Job>()
    override fun onStartJob(params:JobParameters):Boolean {
        tasks.remove(params.jobId)?.cancel()
        val task=scope.launch(start=CoroutineStart.LAZY) {
            try { ShelfSyncRuntime.get(this@ShelfSyncService).runScheduled(params.jobId==ShelfSyncRuntime.DAILY) }
            catch(e:CancellationException){throw e}
            catch(_:Exception){/* Durable work remains for the next constrained run. */}
            finally {
                if(tasks[params.jobId]===coroutineContext[Job]) {
                    tasks.remove(params.jobId);jobFinished(params,false)
                }
            }
        }
        tasks[params.jobId]=task;task.start();return true
    }
    override fun onStopJob(params:JobParameters):Boolean {tasks.remove(params.jobId)?.cancel();return true}
    override fun onDestroy(){tasks.clear();scope.cancel();super.onDestroy()}
}
