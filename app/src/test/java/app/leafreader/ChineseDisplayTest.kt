package app.leafreader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import app.leafreader.text.*
import app.leafreader.reader.NativeReaderView
import app.leafreader.reader.ReadingStatusSnapshot
import app.leafreader.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChineseDisplayTest {
 private val app=ApplicationProvider.getApplicationContext<Application>()
 private val engine get()=ChineseDisplay.converter(app)
 private fun reference()=JSONObject(javaClass.classLoader!!.getResourceAsStream("opencc-reference.json")!!.bufferedReader().readText())
 private fun sha(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
 @Test fun all4391DictionaryKeysAndSelfAuthoredPhrasesMatchOfficialNativeOpencc119() {
  val ref=reference();val keys=listOf("TSPhrases","TSCharacters").flatMap{n->app.assets.open("opencc/$n.txt").bufferedReader().use{ChineseConverter.dictionary(it.readText())}}.map{it.first}
  assertEquals(4391,keys.size);val input=keys.joinToString(""){"【$it】\n"}
  assertEquals(ref.getString("dictionary_conversion_sha256"),sha(engine.convert(input)))
  val cases=ref.getJSONArray("cases");repeat(cases.length()){val c=cases.getJSONObject(it);assertEquals(c.getString("expected"),engine.convert(c.getString("source")))}
 }
 @Test fun longParagraphsPreserveOriginalCoordinatesIncludingBmpToSupplementaryConversion() {
  val ref=reference();val raw=ref.getString("self_passage").repeat(ref.getInt("long_repeat"));val mapped=engine.project(raw)
  assertEquals(ref.getString("long_conversion_sha256"),sha(mapped.text));assertNotEquals(raw.length,mapped.text.length)
  var previous=0
  for(i in 0..mapped.text.length){val source=mapped.sourceOffset(i);assertTrue(source>=previous&&source<=raw.length);assertFalse(source>0&&source<raw.length&&Character.isLowSurrogate(raw[source])&&Character.isHighSurrogate(raw[source-1]));previous=source}
  assertEquals(raw.length,mapped.sourceOffset(mapped.text.length))
  for(i in 0..raw.length){if(i<raw.length&&i>0&&Character.isLowSurrogate(raw[i])&&Character.isHighSurrogate(raw[i-1]))continue;assertEquals(i,mapped.sourceOffset(mapped.displayOffset(i)))}
  val anchor=raw.indexOf("二噁英")+2;assertEquals('英',mapped.text[mapped.displayOffset(anchor)]);assertEquals(anchor,mapped.sourceOffset(mapped.displayOffset(anchor)))
 }
 @Test fun variableCodepointCountCustomRulesRemainMonotonicAndGraphicBreaksDoNotJoinPhrases() {
  val c=ChineseConverter(listOf("甲乙" to "丙","丁" to "戊己","乾隆" to "乾隆"),listOf("乾" to "干"))
  val m=c.project("甲乙丁😀");assertEquals("丙戊己😀",m.text);assertEquals(0,m.sourceOffset(0));assertEquals(2,m.sourceOffset(1));assertEquals(5,m.sourceOffset(m.text.length))
  assertEquals("乾隆",c.project("乾隆").text);assertEquals("干隆",c.project("乾隆",listOf(1)).text)
  for(i in 1..m.text.length)assertTrue(m.sourceOffset(i)>=m.sourceOffset(i-1))
 }
 @Test fun nativePaginationUsesSimplifiedGlyphsButReportsOriginalAnchorsAndColdResume() {
  val raw=reference().getString("self_passage").repeat(45);val projection=engine.project(raw);val oldAnchor=raw.indexOf("二噁英",400)+2
  val store=ReadingStore(app,"synthetic-original-coordinates");store.save("same-id","same-chapter",oldAnchor)
  var saved=-1
  val view=NativeReaderView(app).apply{onPosition={saved=it};layout(0,0,360,640);bind(raw,"繁體書名 · 第一章啟程",oldAnchor,1,ReaderSettings())}
  assertEquals(oldAnchor,view.currentOffset);assertEquals(projection.text,view.currentLayout!!.layout.text.toString());assertTrue(view.contentDescription.contains("繁体书名"))
  val ranges=view.visualTextRanges;assertEquals(0,ranges.first().first);assertEquals(raw.length,ranges.last().last+1)
  ranges.zipWithNext().forEach{(a,b)->assertEquals(a.last+1,b.first)}
  view.turn(1);assertTrue(saved>oldAnchor);store.save("same-id","same-chapter",saved)
  val cold=NativeReaderView(app).apply{layout(0,0,460,320);bind(raw,"繁體書名",store.position("same-id")!!.offset,1,ReaderSettings(fontSp=28f,lineSpacing=1.87f))}
  assertEquals(saved,cold.currentOffset);assertEquals(projection.text,cold.currentLayout!!.layout.text.toString())
  assertTrue(cold.visualTextRanges.any{saved in it});assertEquals("same-chapter",store.position("same-id")!!.chapterId)
  val passes=cold.layoutPasses;cold.updateSystemStatus(ReadingStatusSnapshot("9:00","50%"));assertEquals(passes,cold.layoutPasses)
 }
 @Test fun imageAnchorAndTextRangesRemainInOriginalCoordinates() {
  val raw="繁體標題\n\n二噁英與頭髮。\n\n"+"繼續讀書。".repeat(200);val cut=raw.indexOf("繼續")
  val bitmap=Bitmap.createBitmap(8,12,Bitmap.Config.ARGB_8888);val image=ChapterGraphic("original-image-id",cut,bitmap,"書中圖片")
  val view=NativeReaderView(app).apply{layout(0,0,360,600);bind(raw,"書名",cut,1,ReaderSettings(),images=listOf(image),imageId=image.id)}
  assertEquals(image.id,view.currentGraphicId);assertEquals(cut,view.currentOffset)
  view.turn(1);assertEquals(cut,view.currentOffset);assertTrue(view.contentDescription.contains("继续读书"));assertTrue(view.visualTextRanges.all{it.first>=0&&it.last<raw.length})
  bitmap.recycle()
 }
 @Test fun queryVariantsPreserveTypedInputAndAsciiPlus() {
  assertEquals(listOf("红楼梦","紅樓夢"),ChineseDisplay.searchVariants(app,"红楼梦"))
  assertEquals(listOf("紅樓夢","红楼梦"),ChineseDisplay.searchVariants(app,"紅樓夢"))
  assertEquals(listOf("English Ａ + B，かな、한글、😀"),ChineseDisplay.searchVariants(app,"English Ａ + B，かな、한글、😀"))
  val keys=listOf("STPhrases","STCharacters").flatMap{n->app.assets.open("opencc/$n.txt").bufferedReader().use{ChineseConverter.dictionary(it.readText())}}.map{it.first}
  val query=keys.filterIndexed{i,_->i%61==0}.joinToString(""){"【$it】\n"}
  assertTrue(ChineseDisplay.searchVariants(app,query).map(::sha).contains(reference().getString("search_pipeline_sha256")))
 }
}
