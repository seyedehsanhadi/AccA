package mattecarra.accapp.utils

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.Exception
import java.net.URL

data class ReleaseInfo(val version: String, val notes: String, val pageUrl: String, val apkUrl: String?)

data class AccUpdateInfo(val versionCode: Int, val notes: String, val pageUrl: String)

object GithubUtils {
    suspend fun getLatestAccCommit(branch: String = "main"): String? = withContext(Dispatchers.IO) {
        (try {
            JsonParser
                .parseString(URL("https://api.github.com/repos/seyedehsanhadi/acc/commits/$branch").readText())
                .asJsonObject.get("sha").asString
        } catch (e: Exception) {
            // Log so a failed update check (no network, API error, rate-limit) is
            // distinguishable from "already up to date".
            LogExt().e("GithubUtils", "getLatestAccCommit failed: $e")
            null
        })
    }

    private fun fetchNewestRelease(repo: String, includePreReleases: Boolean): JsonObject? {
        val arr = JsonParser
            .parseString(URL("https://api.github.com/repos/seyedehsanhadi/$repo/releases?per_page=30").readText())
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
                .parseString(URL("https://api.github.com/repos/seyedehsanhadi/AccA/releases/latest").readText())
                .asJsonObject
            releaseInfoFrom("AccA", o)
        } catch (e: Exception) {
            LogExt().e("GithubUtils", "getLatestAccaReleaseInfo failed: $e")
            null
        }
    }

    suspend fun getLatestAccUpdateInfo(includePreReleases: Boolean = false): AccUpdateInfo? = withContext(Dispatchers.IO) {
        try {
            if (includePreReleases) {
                val rel = runCatching { fetchNewestRelease("acc", true) }.getOrNull()
                if (rel != null) {
                    val tag = rel.get("tag_name").asString
                    val notes = runCatching { rel.get("body").asString }.getOrNull().orEmpty()
                    val vc = runCatching {
                        JsonParser
                            .parseString(URL("https://raw.githubusercontent.com/seyedehsanhadi/acc/$tag/module.json").readText())
                            .asJsonObject.get("versionCode").asInt
                    }.getOrNull()
                    if (vc != null) return@withContext AccUpdateInfo(vc, notes, htmlUrl(rel) ?: releasePage("acc", tag))
                }
            }
            val vc = JsonParser
                .parseString(URL("https://raw.githubusercontent.com/seyedehsanhadi/acc/main/module.json").readText())
                .asJsonObject.get("versionCode").asInt
            var page = "https://github.com/seyedehsanhadi/acc/releases/latest"
            val notes = runCatching {
                val o = JsonParser
                    .parseString(URL("https://api.github.com/repos/seyedehsanhadi/acc/releases/latest").readText())
                    .asJsonObject
                htmlUrl(o)?.let { page = it }
                o.get("body").asString
            }.getOrNull().orEmpty()
            AccUpdateInfo(vc, notes, page)
        } catch (e: Exception) {
            LogExt().e("GithubUtils", "getLatestAccUpdateInfo failed: $e")
            null
        }
    }

    suspend fun listAccReleaseTags(includePreReleases: Boolean): List<String> = withContext(Dispatchers.IO) {
        try {
            JsonParser
                .parseString(URL("https://api.github.com/repos/seyedehsanhadi/acc/releases?per_page=30").readText())
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
}
