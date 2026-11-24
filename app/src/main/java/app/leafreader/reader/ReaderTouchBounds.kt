package app.leafreader.reader

/** Insets and the panel position share window/root coordinates; a padded panel edge is not a screen edge. */
internal data class ReaderTouchRegion(val left:Float,val right:Float) {
    fun contains(x:Float)=x>=left && x<right
}
internal object ReaderTouchBounds {
    fun interior(panelWidth:Float,rootWidth:Float,panelLeft:Float,systemLeft:Float,systemRight:Float,fallback:Float):ReaderTouchRegion {
        val width=panelWidth.coerceAtLeast(0f)
        val left=(maxOf(systemLeft,fallback)-panelLeft).coerceIn(0f,width)
        val right=(rootWidth-maxOf(systemRight,fallback)-panelLeft).coerceIn(0f,width)
        return ReaderTouchRegion(left,right)
    }
}
