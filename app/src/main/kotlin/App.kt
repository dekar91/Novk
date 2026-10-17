package novk

import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.*
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlin.io.path.*
import kotlin.system.exitProcess

val json = Json { prettyPrint = true }
fun JsonObject.s(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.n(key: String) = s(key).toLongOrNull() ?: 0L
fun JsonObject.a(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())
fun JsonObject.o(key: String) = get(key) as? JsonObject ?: JsonObject(emptyMap())
fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
fun str(value: String) = JsonPrimitive(value)
fun html(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
fun atomic(path: Path, text: String) {
    path.parent.createDirectories()
    val temp = Files.createTempFile(path.parent, ".write-", ".tmp")
    try {
        temp.writeText(text)
        FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { temp.deleteIfExists() }
}
fun digest(path: Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    path.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) { val count = input.read(buffer); if (count < 0) break; md.update(buffer, 0, count) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
interface Api { fun call(method: String, params: Map<String, String> = emptyMap()): JsonElement }
class VkError(val code: Long, method: String) : RuntimeException("$method: VK error $code. " + when(code) {
    5L -> "Token invalid/expired: obtain a new USER API token; see docs/AUTH.md."
    7L, 15L, 27L, 28L -> "Permission/app/token type denied; see docs/AUTH.md."
    14L -> "CAPTCHA required. Stop and resolve in VK; no automatic bypass."
    17L -> "Account validation required in VK."
    6L, 9L, 29L -> "Rate limit reached; wait before retrying."
    else -> "See VK method documentation; response details suppressed to protect credentials."
})
class Vk(private val token: String, private val version: String) : Api {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    override fun call(method: String, params: Map<String, String>): JsonElement {
        val body = (params + mapOf("access_token" to token, "v" to version)).entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        repeat(4) { attempt ->
            Thread.sleep(400L * (attempt + 1))
            val req = HttpRequest.newBuilder(URI("https://api.vk.com/method/$method"))
                .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build()
            val response = http.send(req, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) { "VK HTTP ${response.statusCode()} ($method)" }
            val root = json.parseToJsonElement(response.body()).jsonObject
            if ("error" !in root) return root["response"] ?: error("Missing response: $method")
            val code = root.o("error").n("error_code")
            if (code != 6L || attempt == 3 || method == "wall.delete") throw VkError(code, method)
        }
        error("Unreachable")
    }
}
fun pageItems(value: JsonElement): JsonArray = if(value is JsonArray) value else value.jsonObject.a("items")
fun collect(api: Api, method: String, params: Map<String,String>, count: Int = 100): List<JsonObject> {
    val result = linkedMapOf<String, JsonObject>()
    var offset = 0
    var expected: Long? = null
    while(true) {
        val response = api.call(method, params + mapOf("count" to "$count", "offset" to "$offset")).jsonObject
        val total = response.n("count")
        if(expected == null) expected = total
        check(expected == total) { "$method changed during pagination; rerun without editing VK." }
        val items = response.a("items")
        if(items.isEmpty()) { check(offset >= total) { "$method ended before reported count" }; break }
        items.forEach { val item = it.jsonObject; result["${item.s("owner_id")}_${item.s("id")}"] = item }
        offset += items.size
        if(offset >= total) break
    }
    check(result.size.toLong() >= expected) { "$method returned duplicate/missing items; rerun." }
    return result.values.toList()
}
fun identity(api: Api): Long {
    val user = api.call("users.get").jsonArray.single().jsonObject
    val id = user.n("id")
    check(id > 0) { "Expected a personal USER token" }
    println("Authenticated as ${user.s("first_name")} ${user.s("last_name")} (id$id)")
    return id
}
fun diagnostic(api: Api, owner: Long) {
    for ((method, params) in listOf(
        "account.getAppPermissions" to emptyMap(),
        "wall.get" to mapOf("owner_id" to "$owner", "count" to "1", "filter" to "all"),
        "photos.getAll" to mapOf("owner_id" to "$owner", "count" to "1"),
        "video.get" to mapOf("owner_id" to "$owner", "count" to "1"),
        "audio.get" to mapOf("owner_id" to "$owner", "count" to "1")
    )) {
        try { val r = api.call(method, params); println("OK $method" + if(method == "account.getAppPermissions") " mask=$r" else "") }
        catch(e: VkError) { println("DENIED ${e.message}") }
    }
    println("Read probes do not prove wall.delete permission. No deletion was attempted.")
}
interface Downloader { fun download(url: String, target: Path, kind: String) }
class HttpDownloader : Downloader {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NORMAL).build()
    override fun download(url: String, target: Path, kind: String) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null) { "Media requires HTTPS" }
        val temp = target.resolveSibling(target.fileName.toString() + ".part")
        try {
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(30)).GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofFile(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))
            check(response.statusCode() == 200) { "Media HTTP ${response.statusCode()}" }
            val size = temp.fileSize()
            check(size > 0) { "Empty media" }
            val length = response.headers().firstValueAsLong("Content-Length")
            // Without a server length, completeness cannot be established conservatively.
            check(length.isPresent && size == length.asLong) { "Missing/mismatched Content-Length" }
            val mime = response.headers().firstValue("Content-Type").orElse("").lowercase()
            check(!mime.contains("text/html") && !mime.contains("json") && !mime.contains("mpegurl")) { "Error page/playlist instead of media" }
            val head = temp.inputStream().use { it.readNBytes(16) }
            val valid = when(kind) {
                "photo" -> (head.size >= 3 && head[0] == 0xff.toByte() && head[1] == 0xd8.toByte()) ||
                    head.take(8).toByteArray().contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10)) ||
                    (head.size >= 12 && String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WEBP") || String(head).startsWith("GIF8")
                "video" -> head.size >= 8 && String(head, 4, 4) == "ftyp"
                "audio" -> String(head).startsWith("ID3") || (head.size >= 2 && head[0] == 0xff.toByte() && (head[1].toInt() and 0xe0) == 0xe0) || (head.size >= 8 && String(head,4,4) == "ftyp")
                else -> true
            }
            check(valid) { "Unrecognized $kind file signature" }
            FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.deleteIfExists() }
    }
}
fun verify(dir: Path): Boolean = try {
    val manifest = json.parseToJsonElement(dir.resolve("manifest.json").readText()).jsonObject
    val files = manifest.a("files")
    manifest.a("blockers").isEmpty() && files.isNotEmpty() && files.any { it.jsonObject.s("path") == "post.json" } &&
        files.any { it.jsonObject.s("path") == "index.html" } && files.all {
            val entry = it.jsonObject
            val path = dir.resolve(entry.s("path")).normalize()
            path.startsWith(dir.normalize()) && !Files.isSymbolicLink(path) && path.isRegularFile() &&
                path.fileSize() == entry.n("size") && digest(path) == entry.s("sha256")
        }
} catch(_: Exception) { false }
class Archiver(private val api: Api, private val downloader: Downloader) {
    fun archive(post: JsonObject, dir: Path): Boolean {
        dir.createDirectories()
        val blockers = mutableListOf<String>()
        val files = mutableListOf<Path>()
        val body = StringBuilder()
        fun save(name: String, value: String) { val p = dir.resolve(name); atomic(p, value); files.add(p) }
        save("post.json", json.encodeToString(post))
        var index = 0
        fun media(kind: String, value: JsonObject) {
            val number = ++index
            try {
                val resolved = if(kind == "video") {
                    val key = "${value.n("owner_id")}_${value.n("id")}" + value.s("access_key").let { if(it.isBlank()) "" else "_$it" }
                    pageItems(api.call("video.get", mapOf("videos" to key))).single().jsonObject
                } else value
                save("attachment-$number.json", json.encodeToString(resolved))
                check(kind != "video" || (resolved.s("live").let { it.isBlank() || it == "0" || it == "false" } && resolved.n("upcoming") == 0L)) { "Live/upcoming video cannot be archived completely" }
                val url = when(kind) {
                    "photo" -> resolved.a("sizes").map { it.jsonObject }.maxByOrNull { it.n("width") * it.n("height") }?.s("url").orEmpty()
                    "video" -> resolved.o("files").entries.filter { it.key.matches(Regex("mp4_\\d+")) }.maxByOrNull { it.key.removePrefix("mp4_").toInt() }?.value?.jsonPrimitive?.content.orEmpty()
                    else -> resolved.s("url")
                }
                check(url.isNotBlank()) { "No downloadable $kind URL" }
                val ext = when(kind) { "photo" -> "img"; "video" -> "mp4"; "audio" -> "mp3"; else -> "bin" }
                val name = "media-$number.$ext"
                val target = dir.resolve(name)
                downloader.download(url, target, kind)
                if(kind == "doc" && resolved.n("size") > 0) check(target.fileSize() == resolved.n("size")) { "Document size mismatch" }
                files.add(target)
                body.append(when(kind) {
                    "photo" -> "<figure><img src=\"$name\" alt=\"Archived image\"></figure>"
                    "video" -> "<video controls src=\"$name\"></video>"
                    "audio" -> "<audio controls src=\"$name\"></audio>"
                    else -> "<p><a href=\"$name\">Downloaded document</a></p>"
                })
            } catch(e: Exception) { blockers.add("Attachment $number ($kind): unavailable or failed download (${e.javaClass.simpleName})") }
        }
        fun visit(p: JsonObject) {
            body.append("<article><p>${html(p.s("text")).replace("\n", "<br>")}</p>")
            if(p.o("comments").n("count") > 0) blockers.add("Post has comments; comment/reply export is not implemented")
            if(p.s("is_deleted") == "1" || p.s("is_deleted") == "true") blockers.add("Deleted post placeholder")
            if(p.s("post_type").isNotBlank() && p.s("post_type") !in setOf("post", "copy")) blockers.add("Unsupported post type")
            if("geo" in p || "copyright" in p || "post_source" in p && p.o("post_source").s("type") !in setOf("vk", "api", "")) blockers.add("Additional post content requires manual review")
            p.a("attachments").forEach { attachment ->
                val a = attachment.jsonObject
                val kind = a.s("type")
                if(kind in setOf("photo", "video", "audio", "doc")) media(kind, a.o(kind))
                else { blockers.add("Unsupported attachment: $kind (metadata saved)"); body.append("<p>Unsupported attachment: ${html(kind)}</p>") }
            }
            p.a("copy_history").forEach { visit(it.jsonObject) }
            body.append("</article>")
        }
        visit(post)
        save("index.html", "<!doctype html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src 'self'; media-src 'self'; style-src 'unsafe-inline'\"><title>VK archive</title><style>body{max-width:900px;margin:2em auto;font:18px sans-serif}img,video{max-width:100%}article{border-left:2px solid #ccc;padding:1em}pre{white-space:pre-wrap}</style></head><body><h1>Post ${post.n("id")}</h1>$body<h2>Archive issues</h2><pre>${html(blockers.joinToString("\n"))}</pre><a href=\"post.json\">Original metadata</a></body></html>")
        val entries = files.map { obj("path" to str(it.fileName.toString()), "size" to JsonPrimitive(it.fileSize()), "sha256" to str(digest(it))) }
        atomic(dir.resolve("manifest.json"), json.encodeToString(obj("files" to JsonArray(entries), "blockers" to JsonArray(blockers.map(::str)))))
        return verify(dir)
    }
}
// Only mutable engagement counters are omitted. Unknown fields remain significant.
fun content(p: JsonObject) = JsonObject(p.filterKeys { it !in setOf("likes", "views", "reposts", "can_edit", "can_delete", "can_pin", "is_pinned") })
fun deleteVerified(api: Api, owner: Long, post: JsonObject, dir: Path): Boolean {
    if(post.n("owner_id") != owner || !verify(dir)) return false
    if(post.o("comments").s("count") != "0") return false
    val stored = json.parseToJsonElement(dir.resolve("post.json").readText()).jsonObject
    if(stored != post) return false
    val fresh = pageItems(api.call("wall.getById", mapOf("posts" to "${owner}_${post.n("id")}"))).singleOrNull()?.jsonObject ?: return false
    if(content(fresh) != content(post) || fresh.o("comments").s("count") != "0") return false
    if(!verify(dir)) return false
    // Never retry an uncertain destructive request. The journal records intent first.
    atomic(dir.resolve("deletion.json"), "{\"status\":\"requested\"}")
    val response = api.call("wall.delete", mapOf("owner_id" to "$owner", "post_id" to post.s("id")))
    check(response.jsonPrimitive.int == 1) { "Deletion not acknowledged; inspect VK before rerun" }
    atomic(dir.resolve("deletion.json"), "{\"status\":\"acknowledged\"}")
    return true
}
fun main(args: Array<String>) {
    try { run(args) } catch(e: Exception) {
        // Network exceptions can contain signed media URLs; don't print their messages/stack traces.
        System.err.println(if(e is VkError) e.message else "Stopped: ${e.javaClass.simpleName}. " + if(e is IllegalStateException || e is IllegalArgumentException) e.message else "Check connectivity, disk access, and archive reports.")
        exitProcess(1)
    }
}
fun run(args: Array<String>) {
    val command = args.firstOrNull() ?: "help"
    if(command == "help" || command == "--help") {
        println("Novk: doctor | archive [--out PATH] [--delete-after-verify --confirm-owner ID] | verify --out RUN_DIRECTORY\nToken: VK_ACCESS_TOKEN environment variable. See README.md and docs/AUTH.md.")
        return
    }
    require(command in setOf("doctor", "archive", "verify")) { "Unknown command" }
    val options = mutableMapOf<String, String>()
    var i = 1
    while(i < args.size) {
        val key = args[i++]
        require(key in setOf("--out", "--delete-after-verify", "--confirm-owner")) { "Unknown option" }
        require(key !in options) { "Duplicate option" }
        options[key] = if(key == "--delete-after-verify") "true" else args.getOrNull(i++) ?: error("Missing option value")
    }
    val root = Path(options["--out"] ?: "archive").toAbsolutePath().normalize()
    if(command == "verify") {
        var failed = 0
        var count = 0
        Files.walk(root).use { paths -> paths.filter { it.fileName.toString() == "manifest.json" }.forEach {
            count++; val ok = verify(it.parent); if(!ok) failed++; println("${if(ok) "VERIFIED" else "BLOCKED/CORRUPT"} ${it.parent}")
        } }
        check(count > 0 && failed == 0) { "$failed of $count archives blocked/corrupt (or no manifests found)" }
        return
    }
    val token = System.getenv("VK_ACCESS_TOKEN")?.trim().orEmpty()
    require(token.isNotEmpty() && !token.contains(Regex("\\s")) && !token.contains("access_token=")) { "Set VK_ACCESS_TOKEN to the token value only. See docs/AUTH.md." }
    val api = Vk(token, System.getenv("VK_API_VERSION") ?: "5.199")
    val owner = identity(api)
    if(command == "doctor") { diagnostic(api, owner); return }
    val delete = "--delete-after-verify" in options
    require(!delete || options["--confirm-owner"] == "$owner") { "Deletion requires --confirm-owner $owner" }
    root.createDirectories()
    FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lockChannel ->
        val lock = lockChannel.tryLock() ?: error("Another archive process is using this output")
        lock.use {
            val run = root.resolve("${owner}-${Instant.now().toEpochMilli()}")
            run.createDirectories()
            atomic(run.resolve("profile.json"), json.encodeToString(api.call("users.get")))
            val params = mapOf("owner_id" to "$owner", "filter" to "all")
            println("Reading full wall snapshot before any deletion...")
            val posts = collect(api, "wall.get", params)
            val second = collect(api, "wall.get", params)
            check(posts.map { it.n("id") }.toSet() == second.map { it.n("id") }.toSet()) { "Wall changed during snapshot; rerun" }
            atomic(run.resolve("wall.json"), json.encodeToString(JsonArray(second)))
            val archiver = Archiver(api, HttpDownloader())
            var blocked = 0
            val ready = mutableListOf<Pair<JsonObject,Path>>()
            for(post in second) {
                val dir = run.resolve("posts/${post.n("id")}")
                val ok = archiver.archive(post, dir)
                if(ok) ready.add(post to dir) else blocked++
                println("Post ${post.n("id")}: ${if(ok) "verified" else "BLOCKED (see manifest.json)"}")
            }
            // Library failures are explicit and never described as a full account export.
            val libraryIssues = mutableListOf<String>()
            val libraryLinks = mutableListOf<String>()
            for((method, kind) in listOf("photos.getAll" to "photo", "video.get" to "video", "audio.get" to "audio")) {
                try {
                    val items = collect(api, method, mapOf("owner_id" to "$owner"))
                    atomic(run.resolve("$kind-library.json"), json.encodeToString(JsonArray(items)))
                    items.forEach { item ->
                        val synthetic = obj("id" to (item["id"] ?: JsonPrimitive(0)), "text" to str("$kind library item"), "attachments" to JsonArray(listOf(obj("type" to str(kind), kind to item))))
                        val relative = "library/$kind-${item.n("owner_id")}-${item.n("id") }"
                        if(!archiver.archive(synthetic, run.resolve(relative))) libraryIssues.add("$kind ${item.n("id")}: incomplete")
                        libraryLinks.add("<p><a href=\"$relative/index.html\">$kind ${item.n("id")}</a></p>")
                    }
                } catch(e: Exception) { libraryIssues.add("$method failed: ${if(e is VkError) e.message else e.javaClass.simpleName}") }
            }
            atomic(run.resolve("library-issues.json"), json.encodeToString(JsonArray(libraryIssues.map(::str))))
            atomic(run.resolve("index.html"), "<!doctype html><meta charset=\"utf-8\"><title>VK archive</title><h1>VK id$owner</h1><p>$blocked blocked posts. ${libraryIssues.size} library issues.</p>" + second.joinToString("\n") { "<p><a href=\"posts/${it.n("id")}/index.html\">Post ${it.n("id")}</a></p>" } + "<h2>Media libraries</h2>" + libraryLinks.joinToString("\n"))
            println("Archive: $run; $blocked blocked posts; ${libraryIssues.size} library issues")
            if(delete) {
                for((post, dir) in ready) println("Post ${post.n("id")}: " + if(deleteVerified(api, owner, post, dir)) "deletion acknowledged" else "kept: changed or verification failed")
            }
            if(blocked > 0 || libraryIssues.isNotEmpty()) error("Partial archive: see manifests and library-issues.json; incomplete posts were kept")
        }
    }
}
