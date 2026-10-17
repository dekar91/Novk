package novk

import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.*

class ArchiveTest {
    private fun post(extra: String = "") = json.parseToJsonElement("""{"id":12,"owner_id":42,"text":"<script>alert('x')</script>","comments":{"count":0}$extra}""").jsonObject
    private class FakeApi(var fresh: JsonObject) : Api {
        var deletes = 0
        override fun call(method: String, params: Map<String,String>): JsonElement = when(method) {
            "wall.getById" -> JsonArray(listOf(fresh))
            "wall.delete" -> { deletes++; JsonPrimitive(1) }
            else -> error("Unexpected call $method")
        }
    }
    private val fakeDownloader = object : Downloader {
        override fun download(url: String, target: java.nio.file.Path, kind: String) { target.writeText("downloaded bytes") }
    }
    @Test fun verifiedTextPostCanBeDeleted() {
        val p = post(); val api = FakeApi(p); val dir = Files.createTempDirectory("novk-test")
        assertTrue(Archiver(api, fakeDownloader).archive(p, dir))
        assertTrue(deleteVerified(api,42,p,dir)); assertEquals(1,api.deletes)
        assertFalse(dir.resolve("index.html").readText().contains("<script>"))
    }
    @Test fun corruptedHtmlBlocksDeletion() {
        val p = post(); val api = FakeApi(p); val dir = Files.createTempDirectory("novk-test")
        Archiver(api,fakeDownloader).archive(p,dir); dir.resolve("index.html").writeText("broken")
        assertFalse(deleteVerified(api,42,p,dir)); assertEquals(0,api.deletes)
    }
    @Test fun changedRemotePostBlocksDeletion() {
        val p = post(); val api = FakeApi(p); val dir = Files.createTempDirectory("novk-test")
        Archiver(api,fakeDownloader).archive(p,dir)
        api.fresh = JsonObject(p + ("text" to str("edited")))
        assertFalse(deleteVerified(api,42,p,dir)); assertEquals(0,api.deletes)
    }
    @Test fun missingMediaBlocksDeletion() {
        val p = post(",\"attachments\":[{\"type\":\"audio\",\"audio\":{\"id\":1}}]")
        val api = FakeApi(p); val dir = Files.createTempDirectory("novk-test")
        assertFalse(Archiver(api,fakeDownloader).archive(p,dir))
        assertFalse(deleteVerified(api,42,p,dir)); assertEquals(0,api.deletes)
    }
    @Test fun unsupportedRepostAttachmentBlocksDeletion() {
        val p = post(",\"copy_history\":[{\"attachments\":[{\"type\":\"poll\",\"poll\":{}}]}]")
        val api = FakeApi(p); val dir = Files.createTempDirectory("novk-test")
        assertFalse(Archiver(api,fakeDownloader).archive(p,dir))
        assertFalse(deleteVerified(api,42,p,dir))
    }
    @Test fun commentsAndWrongOwnerBlockDeletion() {
        val p = JsonObject(post() + ("comments" to obj("count" to JsonPrimitive(1))))
        val api = FakeApi(p); val dir = Files.createTempDirectory("novk-test")
        assertFalse(Archiver(api,fakeDownloader).archive(p,dir))
        assertFalse(deleteVerified(api,43,p,dir)); assertEquals(0,api.deletes)
    }
    @Test fun newCommentBlocksDeletion() {
        val p = post(); val api = FakeApi(p); val dir = Files.createTempDirectory("novk-test")
        Archiver(api,fakeDownloader).archive(p,dir)
        api.fresh = JsonObject(p + ("comments" to obj("count" to JsonPrimitive(1))))
        assertFalse(deleteVerified(api,42,p,dir))
    }
    @Test fun downloadedMediaIsHashedAndMissingFileBlocksDeletion() {
        val p = post(",\"attachments\":[{\"type\":\"photo\",\"photo\":{\"sizes\":[{\"width\":10,\"height\":10,\"url\":\"https://example.com/p.jpg\"}]}}]")
        val api = FakeApi(p); val dir = Files.createTempDirectory("novk-test")
        assertTrue(Archiver(api,fakeDownloader).archive(p,dir))
        dir.resolve("media-1.img").deleteExisting()
        assertFalse(deleteVerified(api,42,p,dir))
    }
    @Test fun failedDownloadBlocksDeletion() {
        val p = post(",\"attachments\":[{\"type\":\"audio\",\"audio\":{\"url\":\"https://example.com/a.mp3\"}}]")
        val api = FakeApi(p); val dir = Files.createTempDirectory("novk-test")
        val failure = object : Downloader { override fun download(url: String, target: java.nio.file.Path, kind: String) { error("truncated") } }
        assertFalse(Archiver(api,failure).archive(p,dir)); assertFalse(deleteVerified(api,42,p,dir))
    }
    @Test fun changingPaginationCountIsRejected() {
        var calls = 0
        val api = object : Api {
            override fun call(method: String, params: Map<String,String>): JsonElement {
                calls++
                return obj("count" to JsonPrimitive(if(calls == 1) 2 else 3), "items" to JsonArray(listOf(post())))
            }
        }
        assertFailsWith<IllegalStateException> { collect(api,"wall.get",emptyMap(),1) }
    }
    @Test fun duplicatePaginationCannotSilentlySkipPosts() {
        val api = object : Api {
            override fun call(method: String, params: Map<String,String>) = obj("count" to JsonPrimitive(2),"items" to JsonArray(listOf(post())))
        }
        assertFailsWith<IllegalStateException> { collect(api,"wall.get",emptyMap(),1) }
    }
}
