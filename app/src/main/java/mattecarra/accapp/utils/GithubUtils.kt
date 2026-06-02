package mattecarra.accapp.utils

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.Exception
import java.net.URL

object GithubUtils {
    suspend fun getLatestAccCommit(branch: String = "master"): String? = withContext(Dispatchers.IO) {
        (try {
            JsonParser
                .parseString(URL("https://api.github.com/repos/VR-25/commits/$branch").readText())
                .asJsonObject.get("sha").asString
        } catch (e: Exception) {
            // Log so a failed update check (no network, API error, rate-limit) is
            // distinguishable from "already up to date".
            LogExt().e("GithubUtils", "getLatestAccCommit failed: $e")
            null
        })
    }

    suspend fun listAccVersions(): List<String> = withContext(Dispatchers.IO) {
        try {
            JsonParser
                .parseString(URL("https://api.github.com/repos/VR-25/acc/tags").readText())
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