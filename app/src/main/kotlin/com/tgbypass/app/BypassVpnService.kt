package com.tgbypass.app

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class BypassVpnService : VpnService() {

    companion object {
        const val ACTION_START         = "com.tgbypass.START"
        const val ACTION_STOP          = "com.tgbypass.STOP"
        const val ACTION_STATUS_UPDATE = "com.tgbypass.STATUS"
        const val ACTION_LOG           = "com.tgbypass.LOG"
        const val EXTRA_STATUS         = "status"
        const val EXTRA_IS_ACTIVE      = "is_active"
        const val EXTRA_MODE           = "mode"
        const val EXTRA_LOG            = "log"
        private const val CHANNEL_ID   = "tgbypass"
        private const val NOTIF_ID     = 1
        private const val TAG          = "TGBypass"

        @Volatile var isRunning   = false
        @Volatile var currentMode = BypassMode.ENHANCED

        private val TELEGRAM_ROUTES = listOf(
            "91.108.4.0"    to 22,
            "91.108.8.0"    to 22,
            "91.108.12.0"   to 22,
            "91.108.16.0"   to 22,
            "91.108.56.0"   to 22,
            "149.154.160.0" to 20,
            "149.154.164.0" to 22,
            "149.154.168.0" to 22,
            "149.154.175.0" to 24,
            "185.76.151.0"  to 24
        )
    }

    private var vpnFd: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private val connections = ConcurrentHashMap<String, TcpProxy>()
    private lateinit var tunOut: FileOutputStream

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentMode = runCatching {
                    BypassMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: "ENHANCED")
                }.getOrDefault(BypassMode.ENHANCED)
                startForeground(NOTIF_ID, buildNotif("Запуск..."))
                Thread { runVpn() }.start()
            }
            ACTION_STOP -> { stopVpn(); stopSelf() }
        }
        return START_STICKY
    }

    override fun onDestroy() { stopVpn(); super.onDestroy() }

    private fun runVpn() {
        try {
            log("Режим: ${currentMode.label} | фрагмент: ${currentMode.fragmentSize} байт")

            val builder = Builder()
                .setSession("TG Bypass")
                .addAddress("10.99.99.2", 32)
                .addDnsServer("1.1.1.1")
                .setMtu(1500)
                .setBlocking(true)

            TELEGRAM_ROUTES.forEach { (ip, prefix) ->
                try { builder.addRoute(ip, prefix) } catch (_: Exception) {}
            }
            builder.addDisallowedApplication(packageName)

            vpnFd = builder.establish() ?: run {
                log("ОШИБКА: не удалось создать VPN")
                broadcast("Ошибка", false); return
            }

            running.set(true)
            isRunning = true
            log("VPN запущен, маршрутов: ${TELEGRAM_ROUTES.size}")
            broadcast("Активен · ${currentMode.label}", true)
            notify("Активен · ${currentMode.label}")

            tunOut = FileOutputStream(vpnFd!!.fileDescriptor)
            val tunIn  = FileInputStream(vpnFd!!.fileDescriptor)
            val buf = ByteArray(32767)

            while (running.get()) {
                val len = tunIn.read(buf)
                if (len > 0) handlePacket(buf.copyOf(len))
            }
        } catch (e: Exception) {
            if (running.get()) log("Сбой VPN: ${e.message}")
        }
        isRunning = false
        broadcast("Остановлен", false)
    }

    private fun handlePacket(pkt: ByteArray) {
        if (pkt.size < 40) return
        if ((pkt[0].toInt() and 0xF0) shr 4 != 4) return  // только IPv4
        if (pkt[9].toInt() and 0xFF != 6) return           // только TCP

        val ihl     = (pkt[0].toInt() and 0x0F) * 4
        val srcIp   = pkt.copyOfRange(12, 16)
        val dstIp   = pkt.copyOfRange(16, 20)
        val srcPort = ((pkt[ihl  ].toInt() and 0xFF) shl 8) or (pkt[ihl+1].toInt() and 0xFF)
        val dstPort = ((pkt[ihl+2].toInt() and 0xFF) shl 8) or (pkt[ihl+3].toInt() and 0xFF)
        val flags   = pkt[ihl+13].toInt() and 0xFF
        val seq     = ((pkt[ihl+4].toLong() and 0xFF) shl 24) or
                      ((pkt[ihl+5].toLong() and 0xFF) shl 16) or
                      ((pkt[ihl+6].toLong() and 0xFF) shl  8) or
                       (pkt[ihl+7].toLong() and 0xFF)
        val tcpHl   = ((pkt[ihl+12].toInt() and 0xF0) shr 4) * 4
        val payOff  = ihl + tcpHl
        val data    = if (pkt.size > payOff) pkt.copyOfRange(payOff, pkt.size) else ByteArray(0)

        val isSyn = flags and 0x02 != 0
        val isAck = flags and 0x10 != 0
        val isPsh = flags and 0x08 != 0
        val isFin = flags and 0x01 != 0
        val isRst = flags and 0x04 != 0

        val key = "${srcIp.ipStr()}:$srcPort"

        val writeFn: (ByteArray) -> Unit = { p ->
            try { synchronized(tunOut) { tunOut.write(p) } }
            catch (_: Exception) {}
        }

        when {
            isSyn && !isAck -> {
                log("Новое соединение → ${dstIp.ipStr()}:$dstPort")
                connections.remove(key)?.close()
                val proxy = TcpProxy(
                    srcIp, srcPort, dstIp, dstPort, seq,
                    this, currentMode, writeFn
                ) { msg -> log(msg) }
                connections[key] = proxy
                Thread { proxy.handleSyn() }.start()
            }
            isRst -> connections.remove(key)?.close()
            isFin -> { connections.remove(key)?.handleFin() }
            data.isNotEmpty() -> connections[key]?.handleData(data, seq)
        }
    }

    private fun stopVpn() {
        running.set(false)
        isRunning = false
        connections.values.forEach { it.close() }
        connections.clear()
        try { vpnFd?.close() } catch (_: Exception) {}
        vpnFd = null
        log("VPN остановлен")
    }

    fun log(msg: String) {
        Log.d(TAG, msg)
        sendBroadcast(Intent(ACTION_LOG).apply { putExtra(EXTRA_LOG, msg) })
    }

    private fun broadcast(status: String, active: Boolean) =
        sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_IS_ACTIVE, active)
        })

    private fun buildNotif(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "TG Bypass", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, BypassVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TG Bypass").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "Стоп", stop)
            .setOngoing(true).build()
    }

    private fun notify(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text))
}

fun ByteArray.ipStr() = joinToString(".") { (it.toInt() and 0xFF).toString() }
