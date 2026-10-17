package expo.modules.etherdrop

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import expo.modules.kotlin.Promise
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class EtherDropUpdateProvider : FileProvider()

class AndroidUpdater(private val context: Context, private val progress: (Map<String, Any>) -> Unit) {
  private val executor = Executors.newSingleThreadExecutor()
  private val preferences = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
  private val folder = File(context.cacheDir, "updates")
  private val apk get() = File(folder, "EtherDrop.apk")

  fun seenVersion(): String? = preferences.getString("seen_version", null)

  fun markSeen(version: String) { preferences.edit().putString("seen_version", version).apply() }

  fun download(releaseJson: String, promise: Promise) {
    executor.execute {
      try {
        val release = JSONObject(releaseJson)
        check(release.getString("url").startsWith("https://")) { "Update downloads must use HTTPS." }
        check(folder.isDirectory || folder.mkdirs()) { "Cannot create the update download folder." }
        val downloaded = UpdateDownloader.download(release.getString("url"), release.getLong("size"), release.optString("digest"), apk) { done, total, speed ->
          progress(mapOf("done" to done.toDouble(), "total" to total.toDouble(), "speed" to speed))
        }
        try { validate(downloaded) } catch (error: Exception) { downloaded.delete(); throw error }
        promise.resolve()
      } catch (error: Exception) { promise.reject("UPDATE_DOWNLOAD", error.message, error) }
    }
  }

  private fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

  private fun validate(file: File) {
    val manager = context.packageManager
    val downloaded = checkNotNull(manager.getPackageArchiveInfo(file.path, 0)) { "The downloaded update is not a valid Android APK." }
    check(downloaded.packageName == context.packageName) { "The downloaded APK is not an EtherDrop update." }
    val installed = manager.getPackageInfo(context.packageName, 0)
    check(code(downloaded) > code(installed)) { "This APK does not have a newer Android version code." }
  }

  fun install(): Boolean {
    validate(apk)
    if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
      context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      return false
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    return true
  }

  fun close() { executor.shutdownNow() }
}
