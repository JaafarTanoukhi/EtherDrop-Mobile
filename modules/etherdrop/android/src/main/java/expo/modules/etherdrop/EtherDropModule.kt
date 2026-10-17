package expo.modules.etherdrop

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import expo.modules.kotlin.Promise
import expo.modules.kotlin.functions.Queues
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CompletableFuture

class EtherDropModule : Module() {
  private lateinit var engine: EtherDropEngine
  private lateinit var storage: AndroidStorage
  private lateinit var updater: AndroidUpdater
  private var picker: Promise? = null
  private var pickerKind = ""
  private var conflict: CompletableFuture<String>? = null
  private val context get() = checkNotNull(appContext.reactContext)

  override fun definition() = ModuleDefinition {
    Name("EtherDrop")
    Events("onState", "onConflict", "onUpdateProgress")

    OnCreate {
      updater = AndroidUpdater(context) { progress -> sendEvent("onUpdateProgress", progress) }
      storage = AndroidStorage(context) { path ->
        val answer = CompletableFuture<String>()
        conflict = answer
        sendEvent("onConflict", mapOf("path" to path))
        try { answer.get() } finally { conflict = null }
      }
      engine = EtherDropEngine(storage) {
        sendEvent("onState", mapOf("state" to engine.snapshot().toString()))
      }
      EtherDropService.engine = engine
    }
    AsyncFunction("getState") { engine.snapshot().toString() }
    AsyncFunction("getSeenVersion") { updater.seenVersion() }
    AsyncFunction("markVersionSeen") { version: String -> updater.markSeen(version) }
    AsyncFunction("downloadUpdate") { release: String, promise: Promise -> updater.download(release, promise) }
    AsyncFunction("installUpdate") { updater.install() }.runOnQueue(Queues.MAIN)
    AsyncFunction("start") { role: String ->
      check(role == "sender" || role == "receiver") { "Choose Send or Receive." }
      try {
        engine.start(role, wifiNetwork())
        val service = Intent(context, EtherDropService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service) else context.startService(service)
        Unit
      } catch (error: Exception) { engine.setError(error.message ?: "Cannot start Wi-Fi."); throw error }
    }
    AsyncFunction("stop") {
      conflict?.complete("skip")
      engine.stop()
      context.stopService(Intent(context, EtherDropService::class.java))
    }
    AsyncFunction("pick") { kind: String, promise: Promise ->
      check(picker == null) { "A file picker is already open." }
      val activity = checkNotNull(appContext.currentActivity) { "Open EtherDrop before choosing files." }
      val intent = if (kind == "files") Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "*/*"
        addCategory(Intent.CATEGORY_OPENABLE)
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
      } else Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
      picker = promise
      pickerKind = kind
      try { activity.startActivityForResult(intent, 45700) }
      catch (error: Exception) { picker = null; promise.reject("PICKER", error.message, error) }
    }.runOnQueue(Queues.MAIN)
    OnActivityResult { _, result ->
      if (result.requestCode == 45700) {
        val promise = picker
        picker = null
        if (result.resultCode != Activity.RESULT_OK || result.data == null) promise?.resolve(emptyList<Any>())
        else {
          val data = result.data!!
          val uris = mutableListOf<Uri>()
          data.clipData?.let { clips -> for (i in 0 until clips.itemCount) uris.add(clips.getItemAt(i).uri) }
          if (uris.isEmpty()) data.data?.let { uris.add(it) }
          try {
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            for (uri in uris) context.contentResolver.takePersistableUriPermission(uri, flags)
            if (pickerKind == "destination") promise?.resolve(uris.map { mapOf("uri" to it.toString()) })
            else promise?.resolve(uris.map { storage.selection(it.toString()) })
          } catch (error: Exception) { promise?.reject("FILES", error.message, error) }
        }
      }
    }
    AsyncFunction("send") { selected: List<String>, verify: Boolean, wrap: Boolean -> engine.send(selected, verify, wrap) }
    AsyncFunction("accept") { id: String, destination: String -> engine.accept(id, destination) }
    AsyncFunction("decline") { id: String -> engine.decline(id) }
    AsyncFunction("cancel") { id: String -> conflict?.complete("skip"); engine.cancel(id) }
    AsyncFunction("cancelAll") { conflict?.complete("skip"); engine.cancelAll() }
    AsyncFunction("dismiss") { engine.dismiss() }
    AsyncFunction("resolveConflict") { choice: String -> conflict?.complete(choice) }
    AsyncFunction("copyDiagnostics") { text: String ->
      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
      clipboard.setPrimaryClip(ClipData.newPlainText("EtherDrop diagnostics", text))
    }
    OnDestroy {
      updater.close()
      conflict?.complete("skip")
      picker?.reject("CLOSED", "EtherDrop closed.", null)
      engine.close()
      context.stopService(Intent(context, EtherDropService::class.java))
      EtherDropService.engine = null
    }
  }

  private fun wifiNetwork(): EtherDropEngine.Network {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val device = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    for (network in manager.allNetworks) {
      val capabilities = manager.getNetworkCapabilities(network) ?: continue
      if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
      val properties = manager.getLinkProperties(network) ?: continue
      val link = properties.linkAddresses.firstOrNull { it.address is Inet4Address } ?: continue
      fun currentNetwork(): android.net.Network = manager.allNetworks.firstOrNull { candidate ->
        val cap = manager.getNetworkCapabilities(candidate)
        cap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && !cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
          manager.getLinkProperties(candidate)?.linkAddresses?.any { it.address == link.address } == true
      } ?: throw java.net.SocketException("Wi-Fi disconnected.")
      return EtherDropEngine.Network(link.address.hostAddress!!, link.prefixLength, properties.interfaceName ?: "Wi-Fi", device,
        object : EtherDropEngine.Binding {
          override fun bind(socket: Socket) { currentNetwork().bindSocket(socket) }
          override fun bind(socket: DatagramSocket) { currentNetwork().bindSocket(socket) }
        })
    }
    // A phone hosting a hotspot can have a Wi-Fi interface without a ConnectivityManager Wi-Fi network.
    for (adapter in Collections.list(NetworkInterface.getNetworkInterfaces())) {
      if (!adapter.isUp || !Regex("^(wlan|ap|softap).*", RegexOption.IGNORE_CASE).matches(adapter.name)) continue
      val address = adapter.interfaceAddresses.firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress } ?: continue
      return EtherDropEngine.Network(address.address.hostAddress!!, address.networkPrefixLength.toInt(), adapter.name, device, EtherDropEngine.DEFAULT_BINDING)
    }
    error("Connect this phone to the same Wi-Fi network or hotspot as the other device, then tap Rescan.")
  }
}
