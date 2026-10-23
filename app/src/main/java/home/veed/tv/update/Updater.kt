package home.veed.tv.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import home.veed.tv.BuildConfig
import home.veed.tv.data.AppRelease
import home.veed.tv.data.LineupRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs new versions of the app from the home server, so a TV that was set up once never
 * needs the sideloading steps again. Nothing happens without the viewer: the channel list
 * offers the update, OK downloads it, and Android's own installer asks before installing.
 * Android only accepts it if it is signed with the same key as the copy already installed.
 */
class Updater(private val context: Context, private val repo: LineupRepository, private val scope: CoroutineScope) {
    /** A newer version the server offers, or null. */
    var available by mutableStateOf<AppRelease?>(null)
        private set

    /** What the update is doing, in words for the screen; null before OK is pressed. */
    var status by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    private val results = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            Log.i(TAG, "install status $code $message")
            status = when (code) {
                // Android's "Do you want to install this update?" screen.
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    if (confirm == null) {
                        "The update could not start. Try again later."
                    } else {
                        ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        "Choose Install on the next screen."
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> "Installed. Open Veed TV again."
                PackageInstaller.STATUS_FAILURE_ABORTED -> "Not installed. Press OK to try again."
                else -> "The update did not install. Try again later."
            }
        }
    }

    init {
        ContextCompat.registerReceiver(context, results, IntentFilter(ACTION_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun offer(release: AppRelease) {
        available = release.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    }

    fun install() {
        val release = available ?: return
        if (job?.isActive == true) return
        // Android 8 made "install unknown apps" a per-app switch. Before that (Fire OS 6) it is one
        // switch for the whole TV, already on if this app was sideloaded, and the installer asks.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            askForPermission()
            return
        }
        job = scope.launch {
            status = "Downloading…"
            val apk = File(context.cacheDir, "veed-tv-update.apk")
            if (!repo.downloadApk(release, apk)) {
                status = "Could not download the update. Try again later."
                return@launch
            }
            status = "Starting the update…"
            runCatching { withContext(Dispatchers.IO) { commit(apk) } }
                .onFailure {
                    Log.w(TAG, "install session", it)
                    status = "The update could not start. Try again later."
                }
            apk.delete()
        }
    }

    fun release() {
        job?.cancel()
        runCatching { context.unregisterReceiver(results) }
    }

    /**
     * Android lets an app install updates only once someone has switched it on for that app.
     * Open that switch; TVs whose settings app has no direct page get the path in words.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun askForPermission() {
        val page = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        status = try {
            context.startActivity(page)
            "Turn on Veed TV on that screen, come back, and press OK again."
        } catch (e: ActivityNotFoundException) {
            val path = if (context.packageManager.hasSystemFeature(FIRE_TV)) {
                "Settings → My Fire TV → Developer options → Install unknown apps → Veed TV on"
            } else {
                "Settings → Apps → Security & restrictions → Unknown sources → Veed TV on"
            }
            "First allow it: $path. Then press OK here again."
        }
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            try {
                session.openWrite("veed-tv.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // Mutable: the installer adds the status and the confirmation screen to it.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val result = Intent(ACTION_RESULT).setPackage(context.packageName)
                session.commit(PendingIntent.getBroadcast(context, id, result, flags).intentSender)
            } catch (e: Exception) {
                session.abandon()
                throw e
            }
        }
    }

    private companion object {
        const val TAG = "VeedTV"
        const val ACTION_RESULT = "home.veed.tv.INSTALL_RESULT"

        /** Amazon's documented way to tell a Fire TV, whose settings menus differ. */
        const val FIRE_TV = "amazon.hardware.fire_tv"
    }
}
