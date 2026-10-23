package home.veed.tv.data

import kotlinx.serialization.Serializable

/**
 * The newest version of this app, as announced by the home server's `veed-tv.json` (written by
 * tools/publish-release.sh next to `veed-tv.apk`).
 */
@Serializable
data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    /** Of the APK file, lowercase hex: the download is thrown away unless it matches. */
    val sha256: String,
    val size: Long,
    /** One plain sentence for the family about what changed. */
    val notes: String = "",
) {
    val isPlausible: Boolean
        get() = versionCode > 0 && size in 1..MAX_APK_BYTES && sha256.matches(Regex("[0-9a-fA-F]{64}"))

    companion object {
        const val MAX_APK_BYTES = 100L * 1024 * 1024
    }
}
