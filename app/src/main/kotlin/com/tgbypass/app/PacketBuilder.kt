package com.tgbypass.app

object PacketBuilder {

    fun buildPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        seq: Long, ack: Long,
        flags: Int,
        data: ByteArray = ByteArray(0),
        window: Int = 65535
    ): ByteArray {
        val total = 40 + data.size
        val buf = ByteArray(total)

        // IP header (20 байт)
        buf[0] = 0x45.toByte()          // Version=4, IHL=5
        buf[2] = ((total shr 8) and 0xFF).toByte()
        buf[3] = (total and 0xFF).toByte()
        buf[6] = 0x40.toByte()           // Don't fragment
        buf[8] = 64.toByte()             // TTL
        buf[9] = 6.toByte()              // Protocol: TCP
        System.arraycopy(srcIp, 0, buf, 12, 4)
        System.arraycopy(dstIp, 0, buf, 16, 4)

        // TCP header (20 байт, offset 20)
        buf[20] = ((srcPort shr 8) and 0xFF).toByte()
        buf[21] = (srcPort and 0xFF).toByte()
        buf[22] = ((dstPort shr 8) and 0xFF).toByte()
        buf[23] = (dstPort and 0xFF).toByte()
        buf[24] = ((seq shr 24) and 0xFF).toByte()
        buf[25] = ((seq shr 16) and 0xFF).toByte()
        buf[26] = ((seq shr  8) and 0xFF).toByte()
        buf[27] = (seq          and 0xFF).toByte()
        buf[28] = ((ack shr 24) and 0xFF).toByte()
        buf[29] = ((ack shr 16) and 0xFF).toByte()
        buf[30] = ((ack shr  8) and 0xFF).toByte()
        buf[31] = (ack          and 0xFF).toByte()
        buf[32] = 0x50.toByte()          // Data offset = 5 (20 байт)
        buf[33] = flags.toByte()
        buf[34] = ((window shr 8) and 0xFF).toByte()
        buf[35] = (window and 0xFF).toByte()

        if (data.isNotEmpty()) System.arraycopy(data, 0, buf, 40, data.size)

        ipChecksum(buf)
        tcpChecksum(buf)
        return buf
    }

    private fun ipChecksum(buf: ByteArray) {
        buf[10] = 0; buf[11] = 0
        var sum = 0L
        for (i in 0 until 20 step 2)
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i+1].toInt() and 0xFF)
        while (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum shr 16)
        sum = sum.inv() and 0xFFFF
        buf[10] = ((sum shr 8) and 0xFF).toByte()
        buf[11] = (sum and 0xFF).toByte()
    }

    private fun tcpChecksum(buf: ByteArray) {
        val tcpLen = buf.size - 20
        buf[36] = 0; buf[37] = 0
        var sum = 0L
        // Pseudo-header: src IP, dst IP
        for (i in 12 until 20 step 2)
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i+1].toInt() and 0xFF)
        sum += 6L          // Protocol TCP
        sum += tcpLen.toLong()
        // TCP segment
        var i = 20
        while (i < buf.size - 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i+1].toInt() and 0xFF)
            i += 2
        }
        if (i < buf.size) sum += (buf[i].toInt() and 0xFF) shl 8
        while (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum shr 16)
        sum = sum.inv() and 0xFFFF
        buf[36] = ((sum shr 8) and 0xFF).toByte()
        buf[37] = (sum and 0xFF).toByte()
    }
}
