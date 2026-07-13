package mattecarra.accapp.utils

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.Exception
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(val version: String, val notes: String, val pageUrl: String, val apkUrl: String?)
data class AccModuleInfo(val version: String, val versionCode: Int, val releasePage: String)

object GithubUtils {
    // Plain URL(x).readText() sets no timeout, so a dead/slow connection can hang a "check for
    // updates" tap forever with no error shown - bound every fetch so it always resolves.
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    private fun fetchText(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("User-Agent", "AccA")
        return try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
    }

    suspend fun getLatestAccCommit(branch: String = "main"): String? = withContext(Dispatchers.IO) {
        (try {
            JsonParser
                .parseString(fetchText("https://api.github.com/repos/seyedehsanhadi/acc/commits/$branch"))
                .asJsonObject.get("sha").asString
        } catch (e: Exception) {
            // Log so a failed update check (no network, API error, rate-limit) is
            // distinguishable from "already up to date".
            LogExt().e("GithubUtils", "getLatestAccCommit failed: $e")
            null
        })
    }

    /** Latest ACC module info from the fork's module.json on main - the SAME file Magisk's
     * updateJson reads, so its versionCode/version are authoritative for "is a newer ACC out".
     * AccA never installs ACC itself (no bundle); the user flashes the module. */
    suspend fun getLatestAccModuleInfo(): AccModuleInfo? = withContext(Dispatchers.IO) {
        try {
            val o = JsonParser
                .parseString(fetchText("https://raw.githubusercontent.com/seyedehsanhadi/acc/main/module.json"))
                .asJsonObject
            val ver = o.get("version").asString
            AccModuleInfo(ver, o.get("versionCode").asInt,
                "https://github.com/seyedehsanhadi/acc/releases/tag/$ver")
        } catch (e: Exception) {
            LogExt().e("GithubUtils", "getLatestAccModuleInfo failed: $e")
            null
        }
    }

    private fun fetchNewestRelease(repo: String, includePreReleases: Boolean): JsonObject? {
        val arr = JsonParser
            .parseString(fetchText("https://api.github.com/repos/seyedehsanhadi/$repo/releases?per_page=30"))
            .asJsonArray
        for (el in arr) {
            val o = runCatching { el.asJsonObject }.getOrNull() ?: continue
            if (runCatching { o.get("draft").asBoolean }.getOrDefault(false)) continue
            val pre = runCatching { o.get("prerelease").asBoolean }.getOrDefault(false)
            if (pre && !includePreReleases) continue
            return o
        }
        return null
    }

    private fun releasePage(repo: String, tag: String) =
        "https://github.com/seyedehsanhadi/$repo/releases/tag/$tag"

    private fun htmlUrl(o: JsonObject): String? =
        runCatching { o.get("html_url").asString }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun apkAsset(o: JsonObject): String? =
        runCatching { o.getAsJsonArray("assets") }.getOrNull()?.firstNotNullOfOrNull { el ->
            runCatching {
                val a = el.asJsonObject
                if (a.get("name").asString.endsWith(".apk", true)) a.get("browser_download_url").asString else null
            }.getOrNull()
        }

    private fun releaseInfoFrom(repo: String, o: JsonObject): ReleaseInfo {
        val tag = o.get("tag_name").asString
        val body = runCatching { o.get("body").asString }.getOrNull().orEmpty()
        return ReleaseInfo(tag, body, htmlUrl(o) ?: releasePage(repo, tag), apkAsset(o))
    }

    suspend fun getLatestAccaReleaseInfo(includePreReleases: Boolean = false): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            if (includePreReleases) {
                val rel = runCatching { fetchNewestRelease("AccA", true) }.getOrNull()
                if (rel != null) return@withContext releaseInfoFrom("AccA", rel)
            }
            val o = JsonParser
                .parseString(fetchText("https://api.github.com/repos/seyedehsanhadi/AccA/releases/latest"))
                .asJsonObject
            releaseInfoFrom("AccA", o)
        } catch (e: Exception) {
            LogExt().e("GithubUtils", "getLatestAccaReleaseInfo failed: $e")
            null
        }
    }

    suspend fun listAccReleaseTags(includePreReleases: Boolean): List<String> = withContext(Dispatchers.IO) {
        try {
            JsonParser
                .parseString(fetchText("https://api.github.com/repos/seyedehsanhadi/acc/releases?per_page=30"))
                .asJsonArray
                .mapNotNull { runCatching { it.asJsonObject }.getOrNull() }
                .filterNot { runCatching { it.get("draft").asBoolean }.getOrDefault(false) }
                .filter { includePreReleases || !runCatching { it.get("prerelease").asBoolean }.getOrDefault(false) }
                .mapNotNull { runCatching { it.get("tag_name").asString }.getOrNull() }
        } catch (e: Exception) {
            LogExt().e("GithubUtils", "listAccReleaseTags failed: $e")
            emptyList()
        }
    }

    data class ReleaseEntry(val tag: String, val prerelease: Boolean, val pageUrl: String, val downloadUrl: String?)

    /** Every AccA release on GitHub, in GitHub's own (newest-first) order - each one downloadable.
     * ACC is deliberately NOT covered here: it already surfaces its own update via module.prop's
     * updateJson, which Magisk/KernelSU show natively in their Modules list. */
    suspend fun listAccaReleases(includePreReleases: Boolean): List<ReleaseEntry> = withContext(Dispatchers.IO) {
        try {
            JsonParser
                .parseString(fetchText("https://api.github.com/repos/seyedehsanhadi/AccA/releases?per_page=30"))
                .asJsonArray
                .mapNotNull { runCatching { it.asJsonObject }.getOrNull() }
                .filterNot { runCatching { it.get("draft").asBoolean }.getOrDefault(false) }
                .filter { includePreReleases || !runCatching { it.get("prerelease").asBoolean }.getOrDefault(false) }
                .mapNotNull { o ->
                    val tag = runCatching { o.get("tag_name").asString }.getOrNull() ?: return@mapNotNull null
                    ReleaseEntry(
                        tag = tag,
                        prerelease = runCatching { o.get("prerelease").asBoolean }.getOrDefault(false),
                        pageUrl = htmlUrl(o) ?: releasePage("AccA", tag),
                        downloadUrl = apkAsset(o)
                    )
                }
        } catch (e: Exception) {
            LogExt().e("GithubUtils", "listAccaReleases failed: $e")
            emptyList()
        }
    }
}
