package app.leafreader.offline

import java.util.Locale

object OfflineUsageFormatting {
    fun bytes(value:Long):String {
        val n=value.coerceAtLeast(0)
        if(n<1024)return "$n 字节"
        val unit=if(n>=1024L*1024*1024)"GiB" else if(n>=1024L*1024)"MiB" else "KiB"
        val divisor=when(unit){"GiB"->1024.0*1024*1024;"MiB"->1024.0*1024;else->1024.0}
        return String.format(Locale.ROOT,"%.2f %s（%,d 字节）",n/divisor,unit,n)
    }
}
