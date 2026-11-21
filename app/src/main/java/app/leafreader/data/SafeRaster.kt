package app.leafreader.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object SafeRaster {
    suspend fun decode(bytes: ByteArray): Bitmap = withContext(Dispatchers.Default) {
        val png=bytes.size>=8 && bytes.take(8)==listOf(137,80,78,71,13,10,26,10).map{it.toByte()}
        val jpeg=bytes.size>=3 && bytes[0]==0xff.toByte() && bytes[1]==0xd8.toByte() && bytes[2]==0xff.toByte()
        val gif=bytes.size>=6 && String(bytes,0,6,Charsets.US_ASCII) in setOf("GIF87a","GIF89a")
        val webp=bytes.size>=12 && String(bytes,0,4,Charsets.US_ASCII)=="RIFF" && String(bytes,8,4,Charsets.US_ASCII)=="WEBP"
        if(!png && !jpeg && !gif && !webp) fail()
        val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
        if(options.outWidth !in 1..16000 || options.outHeight !in 1..16000 || options.outWidth.toLong()*options.outHeight>64_000_000) fail()
        options.inJustDecodeBounds=false;options.inSampleSize=1
        while(options.outWidth/options.inSampleSize>2048 || options.outHeight/options.inSampleSize>2048 ||
            (options.outWidth/options.inSampleSize).toLong()*(options.outHeight/options.inSampleSize)>2_000_000) options.inSampleSize*=2
        options.inPreferredConfig=Bitmap.Config.ARGB_8888
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,options) ?: fail()
    }
    private fun fail():Nothing=throw SourceProblem(FailureKind.UNSUPPORTED,"图片格式或尺寸不受支持，原进度已保留",diagnostic="BH_IMAGE_DECODE")
}
