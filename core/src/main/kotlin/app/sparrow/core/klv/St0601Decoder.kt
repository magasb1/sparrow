package app.sparrow.core.klv

/** One decoded Local Set item. [value] is a Double, Long or String, or null if out of range. */
data class DecodedItem(val tagId: Int, val tag: Tag?, val raw: ByteArray, val value: Any?) {
    val display: String
        get() = when (value) {
            null -> if (tag == null) raw.joinToString("") { "%02X".format(it) } else "out of range"
            is Double -> "%.7f".format(value).trimEnd('0').trimEnd('.') + (tag?.unit?.let { " $it" } ?: "")
            else -> value.toString()
        }

    override fun equals(other: Any?) = other is DecodedItem && other.tagId == tagId && other.raw.contentEquals(raw)
    override fun hashCode() = tagId * 31 + raw.contentHashCode()
}

data class DecodedPacket(val items: List<DecodedItem>, val checksumValid: Boolean) {
    operator fun get(tag: Tag): Any? = items.firstOrNull { it.tagId == tag.id }?.value
    fun has(tag: Tag) = items.any { it.tagId == tag.id }
}

/** Parses ST 0601 packets; used by the KLV inspector (UI-4) and round-trip tests. */
object St0601Decoder {
    class FormatException(message: String) : Exception(message)

    fun decode(packet: ByteArray, offset: Int = 0): DecodedPacket {
        val key = St0601.UNIVERSAL_KEY
        if (packet.size - offset < key.size + 1) throw FormatException("packet too short")
        for (i in key.indices) {
            if (packet[offset + i] != key[i]) throw FormatException("not a UAS Datalink Local Set")
        }
        val (length, lenBytes) = Ber.readLength(packet, offset + key.size)
        val start = offset + key.size + lenBytes
        val end = start + length
        if (end > packet.size) throw FormatException("truncated packet")

        val items = ArrayList<DecodedItem>()
        var pos = start
        var checksumValid = false
        while (pos < end) {
            val (tagId, tagBytes) = Ber.readOid(packet, pos)
            val (len, lb) = Ber.readLength(packet, pos + tagBytes)
            val valueStart = pos + tagBytes + lb
            if (valueStart + len > end) throw FormatException("item $tagId overruns packet")
            val raw = packet.copyOfRange(valueStart, valueStart + len)
            val tag = Tag.of(tagId)
            if (tag == Tag.CHECKSUM) {
                val expected = St0601Encoder.checksum(packet, offset, valueStart - offset)
                checksumValid = len == 2 && expected == readUnsigned(raw).toInt()
            }
            items.add(DecodedItem(tagId, tag, raw, tag?.let { decodeValue(it, raw) }))
            pos = valueStart + len
        }
        return DecodedPacket(items, checksumValid)
    }

    private fun readUnsigned(raw: ByteArray): Long {
        var v = 0L
        for (b in raw) v = (v shl 8) or (b.toLong() and 0xFF)
        return v
    }

    private fun decodeValue(tag: Tag, raw: ByteArray): Any? = when (val f = tag.format) {
        is TagFormat.UInt -> readUnsigned(raw)
        is TagFormat.Text -> raw.toString(Charsets.UTF_8)
        is TagFormat.UMapped -> TagMapping.decodeUnsigned(f, readUnsigned(raw))
        is TagFormat.SMapped -> TagMapping.decodeSigned(f, readUnsigned(raw))
    }
}
