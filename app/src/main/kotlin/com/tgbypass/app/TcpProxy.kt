package com.tgbypass.app

import android.util.Log
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TcpProxy(
    private val srcIp: ByteArray,
    private val srcPort: Int,
    private val dstIp: ByteArray,
    private val dstPort: Int,
    private val clientIsn: Long,
    private val service: BypassVpnService,
    private val mode: BypassMode,
    private val writeToTun: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit
) {
    private val socket = Socket()
    // AtomicLong для потокобезопасности между readFromServer и handleData
    private val serverSeq = AtomicLong(System.nanoTime() and 0xFFFFFFFFL)
    private val clientAck = AtomicLong((clientIsn + 1) and 0xFFFFFFFFL)
    private val running = AtomicBoolean(false)
    private var firstData = true
    // Очередь для пакетов пришедших до готовности соединения
    private val pendingData = LinkedBlockingQueue<Pair<ByteArray, Long>>(64)

    fun handleSyn() {
        try {
            service.protect(socket)
            // КРИТИЧНО: отключаем алгоритм Нэйгла чтобы каждый write() = отдельный TCP сегмент
            socket.tcpNoDelay = true
            socket.soTimeout = 15000
            socket.connect(InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort), 10000)

            // Отправляем SYN-ACK клиенту
            send(0x12)  // SYN+ACK
            serverSeq.set((serverSeq.get() + 1) and 0xFFFFFFFFL)

            running.set(true)

            // Обрабатываем данные что пришли пока устанавливалось соединение
            while (pendingData.isNotEmpty()) {
                val (data, seq) = pendingData.poll() ?: break
                forwardData(data, seq)
            }

            Thread { readFromServer() }.start()

        } catch (e: Exception) {
            onLog("Ошибка подключения к ${dstIp.ipStr()}:$dstPort — ${e.message}")
            try { send(0x04) } catch (_: Exception) {}  // RST
        }
    }

    fun handleData(data: ByteArray, seq: Long) {
        if (data.isEmpty()) return
        clientAck.set((seq + data.size) and 0xFFFFFFFFL)
        // Подтверждаем получение данных от клиента
        send(0x10)  // ACK

        if (!running.get()) {
            // Соединение ещё устанавливается — ставим в очередь
            pendingData.offer(data to seq)
            return
        }
        forwardData(data, seq)
    }

    private fun forwardData(data: ByteArray, seq: Long) {
        if (!running.get()) return
        try {
            val out: OutputStream = socket.getOutputStream()
            if (firstData && isTlsClientHello(data) && data.size > mode.fragmentSize) {
                firstData = false
                val split = mode.fragmentSize
                // Отправляем ПЕРВЫЙ фрагмент
                out.write(data, 0, split)
                out.flush()
                Thread.sleep(3)  // Небольшая пауза — ядро успевает отправить первый сегмент
                // Отправляем ВТОРОЙ фрагмент
                out.write(data, split, data.size - split)
                out.flush()
                onLog("Фрагментирован TLS Hello: ${split}б + ${data.size - split}б")
            } else {
                firstData = false
                out.write(data)
                out.flush()
            }
        } catch (e: Exception) {
            if (running.get()) onLog("Ошибка отправки: ${e.message}")
            close()
        }
    }

    fun handleFin() = close()

    private fun readFromServer() {
        val buf = ByteArray(16384)
        try {
            val inp = socket.getInputStream()
            while (running.get()) {
                val len = inp.read(buf)
                if (len <= 0) break
                val data = buf.copyOf(len)
                // PSH+ACK → клиенту
                send(0x18, data)
                serverSeq.set((serverSeq.get() + len) and 0xFFFFFFFFL)
            }
        } catch (_: Exception) {}

        // FIN → клиенту
        if (running.compareAndSet(true, false)) {
            try { send(0x11) } catch (_: Exception) {}  // FIN+ACK
        }
        close()
    }

    private fun send(flags: Int, data: ByteArray = ByteArray(0)) {
        val pkt = PacketBuilder.buildPacket(
            srcIp = dstIp, dstIp = srcIp,
            srcPort = dstPort, dstPort = srcPort,
            seq = serverSeq.get(),
            ack = clientAck.get(),
            flags = flags,
            data = data
        )
        writeToTun(pkt)
    }

    private fun isTlsClientHello(d: ByteArray) =
        d.size > 5 && d[0] == 0x16.toByte() && d[1] == 0x03.toByte()

    fun close() {
        running.set(false)
        try { socket.close() } catch (_: Exception) {}
    }
}

fun ByteArray.ipStr() = joinToString(".") { (it.toInt() and 0xFF).toString() }
