package app.leafreader.data

import java.text.Normalizer
import java.util.Locale

/** Local catalog spelling/display variants only; original metadata, identifiers and book text stay unchanged. */
internal object GutenbergSearch {
    private val aliases=mapOf("confucius" to "孔子 孔丘", "cao,xueqin" to "曹雪芹", "luo,guanzhong" to "罗贯中 羅貫中", "shi,naian" to "施耐庵", "wu,cheng'en" to "吴承恩 吳承恩", "mencius" to "孟子 孟轲 孟軻", "laozi" to "老子 老聃", "zhuangzi" to "庄子 莊子", "sunzi" to "孙子 孫子 孙武 孫武")
    internal fun fold(value:String,converter:app.leafreader.text.ChineseConverter)=Normalizer.normalize(converter.convert(value),Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter{it.isLetterOrDigit()}
    fun matches(book:Book,query:String,converter:app.leafreader.text.ChineseConverter):Boolean {
        val q=fold(query,converter);if(q.isEmpty())return true
        val author=book.author.lowercase(Locale.ROOT).replace(" ","")
        val names=aliases.filterKeys{author.contains(it)}.values.joinToString(" ")
        return fold(book.title,converter).contains(q)||fold(book.author+names,converter).contains(q)
    }
}
