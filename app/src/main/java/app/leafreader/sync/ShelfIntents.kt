package app.leafreader.sync

import android.content.Context
import app.leafreader.data.*
import org.json.JSONObject
import java.util.UUID

/** Version 3 is opt-in by a new explicit authenticated action; legacy hidden/v2 records are never imported. */
data class ShelfIntent(val key:String,val nonce:String,val owner:String,val origin:String,val account:String,val book:Book,
    val present:Boolean,val clearOnRemove:Boolean,val keepLocal:Boolean,val state:String="PENDING",val attempts:Int=0,
    val due:Long=0,val baseline:Boolean?=null,val sent:Boolean=false,val watchUntil:Long=0,val checks:Int=0) {
    val pending:Boolean get()=state !in setOf("DONE","CANCELLED")
}
@android.annotation.SuppressLint("ApplySharedPref")
class ShelfIntents(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences("bookhub-shelf-intents-v3",Context.MODE_PRIVATE)
    val guard=Any()
    val epoch:Long get()=prefs.getLong("membership-epoch",0)
    fun key(owner:String,id:String)=app.leafreader.offline.sha("$owner|$id".toByteArray())
    fun get(owner:String,id:String)=getKey(key(owner,id))
    private fun getKey(key:String):ShelfIntent?=runCatching{decode(JSONObject(prefs.getString("r:$key",null)?:return null))}.getOrNull()
    fun all():List<ShelfIntent> = prefs.all.filterKeys{it.startsWith("r:")}.values.mapNotNull{v->runCatching{decode(JSONObject(v as String))}.getOrNull()}
    fun current(r:ShelfIntent)=getKey(r.key)?.let{it.nonce==r.nonce && it.pending}==true
    fun save(r:ShelfIntent,membershipChange:Boolean=false):Boolean=synchronized(guard) {
        val e=prefs.edit().putString("r:${r.key}",encode(r).toString())
        if(membershipChange)e.putLong("membership-epoch",epoch+1)
        e.commit()
    }
    fun enqueue(session:SavedSession,book:Book,present:Boolean,clear:Boolean,now:Long):ShelfIntent?=synchronized(guard) {
        val owner="bookhub-v1-"+app.leafreader.offline.sha((session.storageOrigin+"|"+session.accountId).toByteArray()).take(32)
        if(book.sourceId!=owner || app.leafreader.data.ReadingStore(contextRef,owner).cleanupPending(book.id))return null
        val old=get(owner,book.id)
        if(old?.pending==true && old.present==present && old.state!="APPLYING") {
            val next=old.copy(due=now,state="PENDING");return if(save(next))next else null
        }
        val keep=app.leafreader.data.ReadingStore(contextRef,owner).peekShelf().any{it.id==book.id}
        val watch=if(old?.pending==true && (old.sent || old.watchUntil>now) && old.present!=present)now+DAY else 0L
        val r=ShelfIntent(key(owner,book.id),UUID.randomUUID().toString(),owner,ServiceOrigin(session.origin).value,session.accountId,book,present,clear,keep,due=now,watchUntil=watch)
        if(save(r,true))r else null
    }
    private val contextRef=context.applicationContext
    /** Cancel before send, or compensate an already sent operation toward its observed pre-operation state. */
    fun cancel(owner:String,id:String,now:Long):Boolean=synchronized(guard) {
        val old=get(owner,id)?:return false
        if(!old.pending || old.state=="APPLYING")return false
        val next=if(old.sent && old.baseline!=null)old.copy(nonce=UUID.randomUUID().toString(),present=old.baseline,
            clearOnRemove=false,state="PENDING",attempts=0,due=now,sent=false,baseline=null,watchUntil=now+DAY,checks=0)
        else old.copy(nonce=UUID.randomUUID().toString(),state="CANCELLED")
        save(next,true)
    }
    /** A local-only edit cancels any future cloud replay; it never creates a compensating request. */
    fun cancelLocally(owner:String,id:String):Boolean=synchronized(guard){val r=get(owner,id)?:return true;if(r.state=="APPLYING")return false;save(r.copy(nonce=UUID.randomUUID().toString(),state="CANCELLED"),true)}
    fun resume(owner:String,now:Long) {all().filter{it.owner==owner && it.pending && it.state!="APPLYING"}.forEach{save(it.copy(state="PENDING",attempts=0,due=now))}}
    fun pause(owner:String) {all().filter{it.owner==owner && it.pending && it.state!="APPLYING"}.forEach{save(it.copy(state="WAIT_AUTH"))}}
    private fun encode(r:ShelfIntent)=JSONObject().put("v",3).put("key",r.key).put("nonce",r.nonce).put("owner",r.owner)
        .put("origin",r.origin).put("account",r.account).put("id",r.book.id).put("title",r.book.title).put("author",r.book.author).put("format",r.book.format)
        .put("present",r.present).put("clear",r.clearOnRemove).put("keep",r.keepLocal).put("state",r.state).put("attempts",r.attempts).put("due",r.due)
        .put("baseline",r.baseline?:JSONObject.NULL).put("sent",r.sent).put("watch",r.watchUntil).put("checks",r.checks)
    private fun decode(j:JSONObject):ShelfIntent {
        require(j.getInt("v")==3);val owner=j.getString("owner");val id=j.getString("id");val recordedKey=j.getString("key");require(recordedKey==key(owner,id))
        val origin=ServiceOrigin(j.getString("origin"));require(origin.uri.scheme=="https")
        val book=Book(id,j.getString("title"),j.getString("author"),"","",0,0xFF516D66,j.getString("format"),owner)
        return ShelfIntent(recordedKey,j.getString("nonce"),owner,origin.value,j.getString("account"),book,j.getBoolean("present"),j.getBoolean("clear"),j.getBoolean("keep"),j.getString("state"),j.getInt("attempts"),j.getLong("due"),j.opt("baseline") as? Boolean,j.optBoolean("sent"),j.optLong("watch"),j.optInt("checks"))
    }
    companion object {const val DAY=24*60*60*1000L}
}
