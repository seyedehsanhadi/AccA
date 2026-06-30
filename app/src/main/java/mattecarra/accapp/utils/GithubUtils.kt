package mattecarra.accapp.utils

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.Exception
import java.net.URL

data class ReleaseInfo(val version: String, val notes: String)

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

    // Latest ACC module versionCode from the fork's module.json (for the ACC update notification).
    suspend fun getLatestAccVersionCode(): Int? = withContext(Dispatchers.IO) {
        try {
            JsonParser
                .parseString(URL("https://raw.githubusercontent.com/seyedehsanhadi/acc/main/module.json").readText())
                .asJsonObject.get("versionCode").asInt
        } catch (e: Exception) {
            LogExt().e("GithubUtils", "getLatestAccVersionCode failed: $e")
            null
        }
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

    suspend fun getLatestAccaReleaseInfo(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
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

    suspend fun getLatestAccReleaseNotes(): String? = withContext(Dispatchers.IO) {
        try {
            JsonParser
                .parseString(URL("https://api.github.com/repos/seyedehsanhadi/acc/releases/latest").readText())
                .asJsonObject
                .let { runCatching { it.get("body").asString }.getOrNull() }
        } catch (e: Exception) {
            LogExt().e("GithubUtils", "getLatestAccReleaseNotes failed: $e")
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