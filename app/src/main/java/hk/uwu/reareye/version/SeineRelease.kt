package hk.uwu.reareye.version

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import hk.uwu.reareye.generated.AppProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

internal object UpdateInfoCache {
    val lock = Mutex()
    var latestRelease: SeineRelease? = null
    var islandShownInSession: Boolean = false
    var promptedReleaseTag: String? = null
}

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
                val json = JSONObject(response.body.string())
                val tag = json.optString("tag_name").trim()
                val body = json.optString("body").trim()
                tag.takeIf(String::isNotEmpty)?.let {
                    SeineRelease(tag = it, body = body, apk = readReleaseApk(json, it))
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
    }

    private fun readReleaseApk(json: JSONObject, tag: String): SeineReleaseApk? {
        if (ReleaseNumber.parse(tag) == null) return null
        val expectedName = "Seine-v${tag.removePrefix("v").removePrefix("V")}.apk"
        val assets = json.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name") != expectedName || asset.optString("state") != "uploaded") continue
            val size = asset.optLong("size")
            val sha256 = asset.optString("digest").removePrefix("sha256:")
            if (size > 0 && sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
                return SeineReleaseApk(expectedName, size, sha256)
            }
        }
        return null
    }

    suspend fun downloadUpdate(
        context: Context,
        release: SeineRelease,
        onProgress: suspend (Float) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        val apk = release.apk ?: throw IOException("No Seine APK in ${release.tag}")
        val request = Request.Builder()
            .url("$RELEASES/download/${Uri.encode(release.tag)}/${Uri.encode(apk.name)}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Update download returned HTTP ${response.code}")
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, apk.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Unable to create update in Downloads")
            var saved = false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(32 * 1024)
                var bytesRead = 0L
                var reportedPercent = 0
                val output = resolver.openOutputStream(destination)
                    ?: throw IOException("Unable to open update in Downloads")
                output.use { target ->
                    response.body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytesRead += count
                            if (bytesRead > apk.size) throw IOException("Update exceeded its published size")
                            digest.update(buffer, 0, count)
                            target.write(buffer, 0, count)
                            val percent = (100.0 * bytesRead / apk.size).toInt().coerceAtMost(99)
                            if (percent != reportedPercent) {
                                reportedPercent = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                }
                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (bytesRead != apk.size || !actualSha256.equals(apk.sha256, ignoreCase = true)) {
                    throw IOException("Update did not match the published APK")
                }
                currentCoroutineContext().ensureActive()
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                if (resolver.update(destination, values, null, null) != 1) {
                    throw IOException("Unable to publish update in Downloads")
                }
                saved = true
                destination
            } finally {
                if (!saved) resolver.delete(destination, null, null)
            }
        }
    }
}

internal data class SeineReleaseApk(val name: String, val size: Long, val sha256: String)

internal data class SeineRelease(
    val tag: String,
    val body: String = "",
    val apk: SeineReleaseApk? = null,
) {
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
