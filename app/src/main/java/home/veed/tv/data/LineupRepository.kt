package home.veed.tv.data

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

/** The outcome of asking the server for a file. */
sealed interface Fetched<out T> {
    /**
     * A new copy, already saved. Call [applied] once it is in use: only then does the next
     * request say "unless modified since", so a download dropped half-way (the app left the
     * screen) is fetched again rather than answered with a 304 for a copy never shown.
     */
    class Fresh<T>(val value: T, val applied: () -> Unit = {}) : Fetched<T>

    /** The server's copy has not changed since the one already saved here. */
    data object Unchanged : Fetched<Nothing>

    /** No server answered with a usable file. */
    data object Failed : Fetched<Nothing>
}

/**
 * Where the lineup and the programme guide come from. The home server `veed` rebuilds both
 * every night and is reachable at several addresses ([home.veed.tv.BuildConfig.SERVER_URLS]:
 * the home LAN, then Tailscale); the one that answered last time is tried first. Requests
 * are conditional (If-Modified-Since), so frequent polling costs a 304 when nothing changed.
 * The last good download of each file is kept in app storage — written atomically, so a power
 * cut cannot leave half a file — and the APK carries a lineup for a first start without the server.
 */
class LineupRepository(private val context: Context, private val servers: List<String>) {
    private val json = Json { ignoreUnknownKeys = true }
    private val savedLineup = AtomicFile(File(context.filesDir, "lineup.json"))
    /** The fallback list made from iptv-org while veed is out of reach; see [fallbackFromIptvOrg]. */
    private val savedFallback = AtomicFile(File(context.filesDir, "lineup-iptv.json"))
    private val savedEpg = AtomicFile(File(context.filesDir, "epg.json"))
    private val sp = context.getSharedPreferences("veedtv", Context.MODE_PRIVATE)

    /**
     * One download at a time. A download cannot be interrupted, so one started before the app
     * left the screen may still be writing when the next starts; AtomicFile takes one writer.
     */
    private val downloads = Mutex()

    /** The newest lineup available without the network. */
    fun loadLocal(): Lineup {
        val base = loadBase()
        val fallback = runCatching { readSaved(savedFallback)?.let(::parseLineup) }.getOrNull()
        return if (fallback != null && fallback.generated > base.generated) fallback else base
    }

    /** The newest list veed made, saved or bundled in the APK: what a fallback starts from. */
    private fun loadBase(): Lineup {
        val bundled = context.assets.open("lineup.json").bufferedReader().use { parseLineup(it.readText()) }
        val saved = runCatching { readSaved(savedLineup)?.let(::parseLineup) }
            .onFailure { Log.w(TAG, "ignoring unreadable saved lineup", it) }
            .getOrNull()
        return if (saved != null && saved.generated > bundled.generated) saved else bundled
    }

    fun loadLocalEpg(): Epg = runCatching { readSaved(savedEpg)?.let(Epg::parse) }.getOrNull() ?: Epg()

    suspend fun fetchLineup(): Fetched<Lineup> {
        val result = download("lineup.json", savedLineup) { parseLineup(it).takeIf { l -> l.channels.isNotEmpty() } }
        // veed's tested list always beats a fallback made while it was out of reach.
        if (result is Fetched.Fresh) savedFallback.delete()
        return result
    }

    /**
     * For a TV that has not heard from veed for a day (one that can't run Tailscale, such as a
     * Fire OS 6 stick, or one whose Tailscale is off): the same channels with links refreshed
     * from iptv-org, untested. Tried at most every [FALLBACK_RETRY_MS]; Failed when not due.
     */
    suspend fun fallbackFromIptvOrg(current: Lineup): Fetched<Lineup> {
        val now = System.currentTimeMillis()
        val age = now - (generatedAt(current) ?: 0L)
        if (age < FALLBACK_AFTER_MS || now - sp.getLong(KEY_FALLBACK_AT, 0L) < FALLBACK_RETRY_MS) return Fetched.Failed
        sp.edit().putLong(KEY_FALLBACK_AT, now).apply()
        return downloads.withLock {
            withContext(Dispatchers.IO) {
                runCatching { IptvOrg.derive(loadBase()) }
                    .onFailure { Log.w(TAG, "iptv-org fallback failed", it) }
                    .getOrNull()
                    ?.let { list ->
                        Log.i(TAG, "iptv-org fallback: ${list.channels.sumOf { it.streams.size }} links for ${list.channels.size} channels")
                        runCatching { save(savedFallback, json.encodeToString(Lineup.serializer(), list)) }
                            .onFailure { Log.w(TAG, "could not save the fallback list", it) }
                        Fetched.Fresh(list)
                    }
                    ?: Fetched.Failed
            }
        }
    }

    private fun generatedAt(l: Lineup): Long? =
        runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(l.generated)?.time }.getOrNull()

    suspend fun fetchEpg(): Fetched<Epg> =
        download("epg.json", savedEpg) { Epg.parse(it).takeIf { e -> !e.isEmpty } }

    /** What the server says the newest version of the app is. Not kept: it is a few bytes. */
    suspend fun fetchRelease(): Fetched<AppRelease> =
        download(RELEASE_JSON, keep = null) {
            json.decodeFromString(AppRelease.serializer(), it).takeIf { r -> r.isPlausible }
        }

    /**
     * Downloads the APK [release] describes to [dest]. True only when its size and SHA-256 are
     * the announced ones; Android then refuses it anyway unless it is signed with this app's key.
     */
    suspend fun downloadApk(release: AppRelease, dest: File): Boolean = downloads.withLock { apk(release, dest) }

    private suspend fun apk(release: AppRelease, dest: File): Boolean = withContext(Dispatchers.IO) {
        val last = sp.getString(KEY_SERVER, null)
        for (base in servers.sortedBy { it != last }) {
            val ok = runCatching {
                val conn = (URL("$base/$APK").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3_000
                    readTimeout = 20_000
                }
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    conn.inputStream.use { input ->
                        dest.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                check(total <= release.size) { "larger than announced" }
                                digest.update(buf, 0, n)
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    val hex = digest.digest().joinToString("") { "%02x".format(it) }
                    total == release.size && hex.equals(release.sha256, ignoreCase = true)
                } finally {
                    conn.disconnect()
                }
            }.onFailure { Log.w(TAG, "$base/$APK: $it") }.getOrDefault(false)
            if (ok) return@withContext true
            Log.w(TAG, "$base/$APK: not the announced file")
        }
        dest.delete()
        false
    }

    private suspend fun <T : Any> download(name: String, keep: AtomicFile?, parse: (String) -> T?): Fetched<T> =
        downloads.withLock { fetch(name, keep, parse) }

    private suspend fun <T : Any> fetch(name: String, keep: AtomicFile?, parse: (String) -> T?): Fetched<T> =
        withContext(Dispatchers.IO) {
            val last = sp.getString(KEY_SERVER, null)
            val knownStamp = sp.getString("$KEY_STAMP$name", null)?.takeIf { keep?.baseFile?.exists() == true }
            for (base in servers.sortedBy { it != last }) {
                val result: Fetched<T>? = runCatching {
                    val conn = (URL("$base/$name").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3_000 // a wrong address should cost seconds, not a minute
                        readTimeout = 20_000
                        if (knownStamp != null) setRequestProperty("If-Modified-Since", knownStamp)
                    }
                    try {
                        if (conn.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                            Fetched.Unchanged
                        } else {
                            val text = conn.inputStream.bufferedReader().use { it.readText() }
                            parse(text)?.let { value ->
                                // A failed save must not throw away a good download.
                                val saved = keep != null &&
                                    runCatching { save(keep, text) }.onFailure { Log.w(TAG, "could not save $name", it) }.isSuccess
                                val stamp = conn.getHeaderField("Last-Modified")
                                Fetched.Fresh(value) {
                                    if (saved && stamp != null) sp.edit().putString("$KEY_STAMP$name", stamp).apply()
                                }
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                }.onFailure { Log.w(TAG, "$base/$name: $it") }.getOrNull()
                if (result != null) {
                    if (base != last) sp.edit().putString(KEY_SERVER, base).apply()
                    return@withContext result
                }
            }
            Fetched.Failed
        }

    private fun readSaved(file: AtomicFile): String? =
        if (file.baseFile.exists()) String(file.readFully(), Charsets.UTF_8) else null

    private fun save(file: AtomicFile, text: String) {
        val out = file.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private fun parseLineup(text: String): Lineup = json.decodeFromString(Lineup.serializer(), text)

    private companion object {
        const val TAG = "VeedTV"
        const val KEY_SERVER = "server_base"
        const val KEY_STAMP = "last_modified_"
        const val RELEASE_JSON = "veed-tv.json"
        const val KEY_FALLBACK_AT = "iptv_fallback_at"
        const val FALLBACK_AFTER_MS = 24 * 60 * 60 * 1000L
        const val FALLBACK_RETRY_MS = 6 * 60 * 60 * 1000L
        const val APK = "veed-tv.apk"
    }
}
