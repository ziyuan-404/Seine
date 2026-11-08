package hk.uwu.reareye.version

import android.net.Uri
import hk.uwu.reareye.generated.AppProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

internal object SeineProject {
    const val REPOSITORY = "https://github.com/ziyuan-404/Seine"
    const val RELEASES = "$REPOSITORY/releases"
    const val UPSTREAM = "https://github.com/killerprojecte/REAREye"
    const val CURRENT_RELEASE = "v" + AppProperties.PROJECT_APP_VERSION_NAME

    private const val LATEST_RELEASE_API = "https://api.github.com/repos/ziyuan-404/Seine/releases/latest"
    private val client = OkHttpClient()

    suspend fun latestRelease(): SeineRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val tag = JSONObject(response.body.string()).optString("tag_name").trim()
                tag.takeIf(String::isNotEmpty)?.let(::SeineRelease)
            }
        } catch (_: IOException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
    }
}

internal data class SeineRelease(val tag: String) {
    val url: String get() = SeineProject.RELEASES + "/tag/" + Uri.encode(tag)

    fun isNewerThan(currentTag: String): Boolean {
        val candidate = ReleaseNumber.parse(tag) ?: return false
        val installed = ReleaseNumber.parse(currentTag) ?: return false
        return compareValuesBy(
            candidate, installed,
            { it.major }, { it.minor }, { it.patch }, { it.build }, { it.revision },
        ) > 0
    }
}

private data class ReleaseNumber(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val build: Int,
    val revision: Int,
) {
    companion object {
        private val versionPattern = Regex("""^[vV]?(\d+)\.(\d+)\.(\d+)(?:-r(\d+)(?:-(\d+))?)?(?:[-+].*)?$""")

        fun parse(tag: String): ReleaseNumber? {
            val (major, minor, patch, build, revision) =
                versionPattern.matchEntire(tag)?.destructured ?: return null
            return ReleaseNumber(
                major.toIntOrNull() ?: return null,
                minor.toIntOrNull() ?: return null,
                patch.toIntOrNull() ?: return null,
                if (build.isEmpty()) 0 else build.toIntOrNull() ?: return null,
                if (revision.isEmpty()) 0 else revision.toIntOrNull() ?: return null,
            )
        }
    }
}
