package app.leafreader.offline

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/** A prior BookHub-only opt-in is never permission to fetch other sources. No content/progress migration. */
@android.annotation.SuppressLint("ApplySharedPref")
class GlobalDownloadPreferences(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences("global-auto-download-v1",Context.MODE_PRIVATE)
    init {synchronized(GlobalDownloadPreferences::class.java){if(!prefs.contains("enabled")) {
        val legacy=context.getSharedPreferences("offline-queue-v1",Context.MODE_PRIVATE)
        check(prefs.edit().putBoolean("enabled",false).putBoolean("legacy-bookhub-enabled",legacy.getBoolean("auto-wifi",true)).commit())
        check(legacy.edit().putBoolean("auto-wifi",false).putBoolean("global-policy-cancel-pending",true).commit())
    }}}
    // Missing in 1.1.9 and older means the original strict network rule, never an opt-out.
    fun wifiOnly()=prefs.getBoolean("wifi-only",true)
    fun setWifiOnly(value:Boolean){check(prefs.edit().putBoolean("wifi-only",value).commit())}
    fun networkAllowed(connection:ConnectionState)=enabled()&&connection.online&&(!wifiOnly()||connection.wifi)
    fun waitingLabel()=if(wifiOnly())"等待不计费 Wi-Fi"else"等待网络"
    fun enabled()=prefs.getBoolean("enabled",false)
    fun set(value:Boolean){check(prefs.edit().putBoolean("enabled",value).commit())}
}

/** Byte inventory only, never opens book/account metadata or validates every resource hash. Called on IO. */
object GlobalOfflineUsage {
    fun roots(context:Context)=listOf("offline-v1","gutenberg-public-v1","independent-epub-v1","legado-text-v1","cover-thumbs-v1").map{File(context.noBackupFilesDir,it)}
    fun bytes(roots:List<File>):Long {
        var total=0L;val stack=java.util.ArrayDeque<File>();roots.forEach{if(it.exists())stack.add(it)}
        while(stack.isNotEmpty()) {
            val file=stack.removeLast();if(Files.isSymbolicLink(file.toPath()))continue
            if(file.isDirectory){val children=file.listFiles();if(children==null&&file.exists())throw java.io.IOException("缓存占用暂不可读");children?.forEach{stack.add(it)}}
            else try{val length=Files.size(file.toPath());total=if(Long.MAX_VALUE-total<length)Long.MAX_VALUE else total+length}catch(_:NoSuchFileException){/* Concurrent successful cleanup. */}
        }
        return total
    }
}
