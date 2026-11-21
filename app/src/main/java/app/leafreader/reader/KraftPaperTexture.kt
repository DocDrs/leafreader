package app.leafreader.reader

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import app.leafreader.R
import kotlin.math.max

/** One original full sheet, decoded once. Cover/crop prevents seams and repeated tiles. */
internal object KraftPaperTexture {
    internal var generationCount = 0; private set
    private var bitmap: Bitmap? = null
    private var paint: Paint? = null
    private val matrix = Matrix()
    private var drawnWidth = 0
    private var drawnHeight = 0
    fun draw(canvas: Canvas, resources: Resources, width: Int = canvas.width, height: Int = canvas.height) {
        if (paint == null) {
            val image = requireNotNull(BitmapFactory.decodeResource(resources, R.drawable.kraft_paper_texture,
                BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }))
            bitmap = image
            paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { shader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            generationCount++
        }
        if (width != drawnWidth || height != drawnHeight) {
            val image = bitmap!!
            val scale = max(width.toFloat()/image.width, height.toFloat()/image.height)
            matrix.setScale(scale, scale)
            matrix.postTranslate((width-image.width*scale)/2, (height-image.height*scale)/2)
            paint!!.shader.setLocalMatrix(matrix)
            drawnWidth = width; drawnHeight = height
        }
        canvas.drawPaint(paint!!)
    }
}
