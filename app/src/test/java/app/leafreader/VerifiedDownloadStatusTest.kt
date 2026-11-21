package app.leafreader

import app.leafreader.offline.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class VerifiedDownloadStatusTest {
    @Test fun queuedOrAllCachedResourcesWithoutCompletionMarkerNeverCountAsDownloaded() {
        val w=OfflineFixtures();val store=OfflineStore(Files.createTempDirectory("verify-status").toFile(),"fixture-owner")
        val m=w.manifest();val epoch=store.generation("public:1");store.saveManifest("public:1",m,epoch)
        assertEquals(0,store.status("public:1",true).saved)
        w.bytes().forEach{(id,bytes)->store.put("public:1",w.version,id,bytes,epoch)}
        val all=store.status("public:1",true);assertEquals(all.total,all.saved);assertFalse(all.complete)
        assertTrue(store.complete("public:1",m,epoch));assertTrue(store.status("public:1",true).complete)
    }
    @Test fun sameLengthCorruptionAndMissingFilesInvalidateCompletionWithoutDeletingCopies() {
        val w=OfflineFixtures();val root=Files.createTempDirectory("verify-corrupt").toFile();val store=OfflineStore(root,"fixture-owner")
        val m=w.manifest();val epoch=store.generation("public:1");store.saveManifest("public:1",m,epoch)
        w.bytes().forEach{(id,bytes)->store.put("public:1",w.version,id,bytes,epoch)};store.complete("public:1",m,epoch)
        val file=File(root,"${sha("public:1".toByteArray())}/${sha(w.version.toByteArray())}/${sha("a".toByteArray())}.bin")
        val raw=file.readBytes();file.writeBytes(raw.copyOf().apply{this[0]=(this[0].toInt() xor 1).toByte()})
        assertTrue(store.status("public:1").complete);val checked=store.status("public:1",true)
        assertFalse(checked.complete);assertEquals(4,checked.saved);assertTrue(file.exists());assertFalse(store.status("public:1").complete)
        store.put("public:1",w.version,"a",raw,epoch);assertFalse(store.status("public:1",true).complete)
        store.complete("public:1",m,epoch);assertTrue(store.status("public:1",true).complete)
        file.delete();assertFalse(store.status("public:1",true).complete);assertEquals(4,store.status("public:1",true).saved)
    }
    @Test fun cancelKeepsCommittedCopiesAndRejectsOldWritersAndRecoversAnInterruptedMetadataRollForward() {
        val w=OfflineFixtures();val root=Files.createTempDirectory("cancel-preserve").toFile();val store=OfflineStore(root,"fixture-owner")
        val m=w.manifest();val epoch=store.generation("public:1");store.saveManifest("public:1",m,epoch)
        w.bytes().forEach{(id,bytes)->store.put("public:1",w.version,id,bytes,epoch)};store.complete("public:1",m,epoch)
        store.cancelWrites("public:1");assertTrue(store.status("public:1",true).complete)
        assertFalse(store.put("public:1",w.version,"a","late".toByteArray(),epoch));assertEquals(epoch+1,store.generation("public:1"))
        val dir=File(root,sha("public:1".toByteArray()));val version=File(dir,sha(w.version.toByteArray()))
        File(dir,"cancel-journal.json").writeText(JSONObject().put("from",epoch+1).put("to",epoch+2).toString())
        val manifestFile=File(version,"manifest.json");manifestFile.writeText(JSONObject(manifestFile.readText()).put("_cacheEpoch",epoch+2).toString())
        try{OfflineStore(root,"wrong-owner").generation("public:1");fail("foreign owner cannot recover/write metadata")}
        catch(e:app.leafreader.data.SourceProblem){assertEquals(app.leafreader.data.FailureKind.AUTH,e.kind)}
        assertEquals(epoch+1,JSONObject(File(version,"index.json").readText()).getLong("_epoch"))
        val cold=OfflineStore(root,"fixture-owner");assertEquals(epoch+2,cold.generation("public:1"));assertTrue(cold.status("public:1",true).complete)
        assertFalse(File(dir,"cancel-journal.json").exists());assertFalse(cold.put("public:1",w.version,"a","late".toByteArray(),epoch+1))
        cold.clear("public:1");assertEquals(OfflineStatus(),cold.status("public:1",true))
    }
}
