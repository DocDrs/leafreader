package app.leafreader.text

import android.content.Context

/** Original UTF-16 coordinates are the storage/network contract; this projection is never persisted. */
class TextProjection internal constructor(val text:String,private val originalLength:Int,private val displayToSource:IntArray) {
    fun sourceOffset(displayOffset:Int)=displayToSource[displayOffset.coerceIn(0,text.length)]
    fun displayOffset(sourceOffset:Int):Int {
        val source=sourceOffset.coerceIn(0,originalLength)
        var lo=0;var hi=displayToSource.lastIndex
        while(lo<hi){val mid=(lo+hi)/2;if(displayToSource[mid]<source)lo=mid+1 else hi=mid}
        return if(displayToSource[lo]>source)(lo-1).coerceAtLeast(0)else lo
    }
}

/** OpenCC 1.1.9 t2s: longest phrase match, then character default; no normalization of Latin/spacing. */
class ChineseConverter(phrases:List<Pair<String,String>>,characters:List<Pair<String,String>>) {
    private class Node {val children=HashMap<Char,Node>();var value:String?=null}
    private fun trie(rows:List<Pair<String,String>>)=Node().also{root->rows.forEach{(key,value)->
        require(key.isNotEmpty()&&value.isNotEmpty());var n=root;key.forEach{n=n.children.getOrPut(it){Node()}};n.value=value
    }}
    private val phrase=trie(phrases);private val character=trie(characters)
    private data class Match(val end:Int,val value:String)
    private fun match(root:Node,text:String,start:Int,end:Int):Match? {
        var node=root;var at=start;var found:Match?=null
        while(at<end){node=node.children[text[at]]?:break;at++;node.value?.let{found=Match(at,it)}}
        return found
    }
    private fun replacement(text:String,start:Int,end:Int)=match(phrase,text,start,end)?:match(character,text,start,end)
    fun convert(text:String):String {
        val out=StringBuilder(text.length);var at=0
        while(at<text.length){val m=replacement(text,at,text.length)
            if(m!=null){out.append(m.value);at=m.end}else{val end=at+Character.charCount(text.codePointAt(at));out.append(text,at,end);at=end}}
        return out.toString()
    }
    fun project(text:String,breaks:List<Int> = emptyList()):TextProjection {
        val out=StringBuilder(text.length);var positions=IntArray(text.length+17);var size=1
        fun push(value:Int){if(size==positions.size)positions=positions.copyOf((positions.size*3/2).coerceAtLeast(size+1));positions[size++]=value}
        fun identity(start:Int,end:Int){out.append(text,start,end);for(i in start+1..end)push(if(i<end&&Character.isLowSurrogate(text[i])&&Character.isHighSurrogate(text[i-1]))i-1 else i)}
        fun replace(start:Int,end:Int,value:String){
            if(text.regionMatches(start,value,0,end-start)&&end-start==value.length){identity(start,end);return}
            val input=(start..end).filter{it==end||it==start||!Character.isLowSurrogate(text[it])||!Character.isHighSurrogate(text[it-1])}
            val outputCount=value.codePointCount(0,value.length);var at=0;var count=0
            out.append(value)
            while(at<value.length){val width=Character.charCount(value.codePointAt(at))
                if(width==2)push(input[count*(input.size-1)/outputCount])
                count++;push(input[count*(input.size-1)/outputCount]);at+=width}
        }
        var start=0
        val ends=(breaks.map{it.coerceIn(0,text.length)}.map{if(it>0&&it<text.length&&Character.isLowSurrogate(text[it])&&Character.isHighSurrogate(text[it-1]))it-1 else it}+text.length).distinct().sorted()
        for(end in ends){var at=start
            while(at<end){val m=replacement(text,at,end)
                if(m!=null){replace(at,m.end,m.value);at=m.end}else{val next=at+Character.charCount(text.codePointAt(at));identity(at,next);at=next}}
            start=end}
        check(size==out.length+1);return TextProjection(out.toString(),text.length,positions.copyOf(size))
    }
    companion object {
        fun dictionary(text:String)=text.lineSequence().filter{it.isNotBlank()&&!it.startsWith('#')}.map{line->
            val parts=line.split('\t');require(parts.size==2);parts[0] to parts[1].substringBefore(' ')
        }.toList()
    }
}

object ChineseDisplay {
    @Volatile private var instance:ChineseConverter?=null
    private class QueryConverter(phrases:List<Pair<String,String>>,characters:List<Pair<String,String>>) {
        private val words=phrases.toMap();private val chars=characters.toMap()
        private val lengths=phrases.groupBy{it.first[0]}.mapValues{(_,rows)->rows.map{it.first.length}.distinct().sortedDescending()}
        fun convert(value:String):String=buildString(value.length){var at=0
            while(at<value.length){var end=at;var result:String?=null
                for(length in lengths[value[at]].orEmpty())if(at+length<=value.length){val found=words[value.substring(at,at+length)];if(found!=null){result=found;end=at+length;break}}
                if(result==null){end=at+Character.charCount(value.codePointAt(at));result=chars[value.substring(at,end)]?:value.substring(at,end)}
                append(result);at=end
            }
        }
    }
    @Volatile private var reverse:QueryConverter?=null
    fun converter(context:Context):ChineseConverter=instance?:synchronized(this){instance?:run{
        fun rows(name:String)=context.applicationContext.assets.open("opencc/$name.txt").bufferedReader(Charsets.UTF_8).use{ChineseConverter.dictionary(it.readText())}
        ChineseConverter(rows("TSPhrases"),rows("TSCharacters")).also{instance=it}
    }}
    fun text(context:Context,value:String)=converter(context).convert(value)
    /** Bounded search fallback only after a successful truly empty first page; never edits user input. */
    fun searchVariants(context:Context,value:String):List<String> {
        val simplified=converter(context).convert(value)
        if(value.codePoints().noneMatch{it in 0x3400..0x9fff||it in 0xf900..0xfaff||it in 0x20000..0x323ff})return listOf(value,simplified).distinct()
        val traditional=reverse?:synchronized(this){reverse?:run{
            fun rows(name:String)=context.applicationContext.assets.open("opencc/$name.txt").bufferedReader(Charsets.UTF_8).use{ChineseConverter.dictionary(it.readText())}
            QueryConverter(rows("STPhrases"),rows("STCharacters")).also{reverse=it}
        }}
        return listOf(value,simplified,traditional.convert(simplified)).distinct()
    }
}
