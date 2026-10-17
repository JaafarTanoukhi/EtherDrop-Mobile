package expo.modules.etherdrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

class EtherDropService : Service() {
  companion object { @Volatile var engine: EtherDropEngine? = null }
  private val handler = Handler(Looper.getMainLooper())
  private lateinit var wake: PowerManager.WakeLock
  private lateinit var wifi: WifiManager.WifiLock
  private lateinit var multicast: WifiManager.MulticastLock
  private val tick = object : Runnable {
    override fun run() {
      val current = engine ?: run { stopSelf(); return }
      if (current.busy()) {
        if (!wake.isHeld) wake.acquire()
        if (!wifi.isHeld) wifi.acquire()
      } else {
        if (wake.isHeld) wake.release()
        if (wifi.isHeld) wifi.release()
      }
      val manager = getSystemService(NotificationManager::class.java)
      manager.notify(45671, notification())
      handler.postDelayed(this, 1000)
    }
  }
  override fun onCreate() {
    super.onCreate()
    val power = getSystemService(Context.POWER_SERVICE) as PowerManager
    wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EtherDrop:transfer")
    val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    wifi = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "EtherDrop:transfer")
    multicast = manager.createMulticastLock("EtherDrop:discovery")
    multicast.acquire()
    if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
      NotificationChannel("etherdrop", "Nearby transfers", NotificationManager.IMPORTANCE_LOW))
  }
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (Build.VERSION.SDK_INT >= 29) startForeground(45671, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    else startForeground(45671, notification())
    handler.removeCallbacks(tick)
    handler.post(tick)
    return START_NOT_STICKY
  }
  private fun notification(): Notification {
    val launch = packageManager.getLaunchIntentForPackage(packageName)!!
    val pending = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val state = engine?.snapshot()
    val transfers = state?.optJSONArray("transfers")
    var text = if (state?.optString("role") == "receiver") "Ready to receive over Wi-Fi" else "Ready to send over Wi-Fi"
    var done = 0L
    var total = 0L
    var active = false
    if (transfers != null) for (i in 0 until transfers.length()) {
      val transfer = transfers.getJSONObject(i)
      if (!transfer.optBoolean("finished")) {
        active = true
        done += transfer.optLong("done")
        total += transfer.optLong("total")
        text = "${transfer.optString("phase")} · ${transfer.optString("peer")}"
      }
    }
    val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "etherdrop") else Notification.Builder(this)
    return builder.setContentTitle("EtherDrop")
      .setContentText(text).setContentIntent(pending).setSmallIcon(android.R.drawable.stat_sys_upload)
      .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
      .setProgress(if (active && total > 0) 100 else 0, if (total > 0) (done.toDouble() / total * 100).toInt() else 0, active && total == 0L)
      .build()
  }
  override fun onTaskRemoved(rootIntent: Intent?) { engine?.stop(); stopSelf() }
  override fun onDestroy() {
    handler.removeCallbacks(tick)
    if (wake.isHeld) wake.release()
    if (wifi.isHeld) wifi.release()
    if (multicast.isHeld) multicast.release()
    super.onDestroy()
  }
  override fun onBind(intent: Intent?): IBinder? = null
}
