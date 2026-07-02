package mattecarra.accapp.utils

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.Exception
import java.net.URL

data class ReleaseInfo(val version: String, val notes: String)

data class AccUpdateInfo(val versionCode: Int, val notes: String)

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

    // Newest published release for a repo. GitHub returns releases newest-first, so the first
    // non-draft entry (honouring the pre-release opt-in) is the latest candidate. Whether it is
    // actually an upgrade is decided by the caller's version guard, so this never has to reason
    // about tag schemes that differ between the app (semver) and the module (date + channel).
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

    // Latest published AccA app release tag (for the in-app update notification).
    suspend fun getLatestAccaRelease(): String? = withContext(Dispatchers.IO) {
        try {
            JsonParser
                .parseString(URL("https://api.github.com/repos/seyedehsanhadi/AccA/releases/latest").readText())
                .asJsonObject.get("tag_name").asString
        } catch (e: Exception) {
            LogExt().e("GithubUtils", "getLatestAccaRelease failed: $e")
            null
        }
    }

    suspend fun getLatestAccaReleaseInfo(includePreReleases: Boolean = false): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            if (includePreReleases) {
                val rel = runCatching { fetchNewestRelease("AccA", true) }.getOrNull()
                if (rel != null) {
                    val tag = rel.get("tag_name").asString
                    val body = runCatching { rel.get("body").asString }.getOrNull().orEmpty()
                    return@withContext ReleaseInfo(tag, body)
                }
            }
            val o = JsonParser
                .parseString(URL("https://api.github.com/repos/seyedehsanhadi/AccA/releases/latest").readText())
                .asJsonObject
            val tag = o.get("tag_name").asString
            val body = runCatching { o.get("body").asString }.getOrNull().orEmpty()
            ReleaseInfo(tag, body)
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
                    if (vc != null) return@withContext AccUpdateInfo(vc, notes)
                }
            }
            val vc = JsonParser
                .parseString(URL("https://raw.githubusercontent.com/seyedehsanhadi/acc/main/module.json").readText())
                .asJsonObject.get("versionCode").asInt
            val notes = runCatching {
                JsonParser
                    .parseString(URL("https://api.github.com/repos/seyedehsanhadi/acc/releases/latest").readText())
                    .asJsonObject.get("body").asString
            }.getOrNull().orEmpty()
            AccUpdateInfo(vc, notes)
        } catch (e: Exception) {
            LogExt().e("GithubUtils", "getLatestAccUpdateInfo failed: $e")
            null
        }
    }

    suspend fun listAccVersions(): List<String> = withContext(Dispatchers.IO) {
        try {
            JsonParser
                .parseString(URL("https://api.github.com/repos/seyedehsanhadi/acc/tags").readText())
                .asJsonArray
                // Each element may lack "name" or be malformed; map null-safely and
                // drop bad entries instead of throwing on .asString.
                .mapNotNull { runCatching { it.asJsonObject["name"].asString }.getOrNull() }
        } catch (e: Exception) {
            LogExt().e("GithubUtils", "listAccVersions failed: $e")
            emptyList()
        }
    }
}
