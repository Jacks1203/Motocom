package com.offnavigator.core.bike

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The two ways of framing a message that the bike's screen speaks. Both are little-endian.
 *
 * "PXC" carries the conversation (who are you, are you still there, what time is it):
 *
 * ```
 *   int command | int length, these 16 bytes included | int command xor length | int unused
 *   body, usually JSON
 * ```
 *
 * A reply carries the command plus one. "RV" carries the picture and is leaner:
 *
 * ```
 *   short command | short body length | int token, always 0
 *   body
 * ```
 */
class PxcMessage(val command: Int, val body: ByteArray = NO_BYTES) {
    val text: String get() = String(body, Charsets.UTF_8)

    override fun toString() = "0x%08X (%d bytes)".format(command, body.size)
}

class RvMessage(val command: Int, val body: ByteArray = NO_BYTES) {
    override fun toString() = "0x%04X (%d bytes)".format(command, body.size)
}

internal val NO_BYTES = ByteArray(0)

object Pxc {
    const val HEADER = 16

    /** Nothing the bike says comes near this; a length beyond it means the stream is out of step. */
    private const val LARGEST_BODY = 4 * 1024 * 1024

    // What the bike opens each of its two connections with.
    const val OPEN_BIKE_ASKS = 0x00010000
    const val OPEN_PHONE_ASKS = 0x00020000

    // Asked by the bike.
    const val BIKE_INFO = 0x00010010
    const val HUD_ICONS = 0x00010040
    const val CHECK_SERIAL = 0x000103E0
    const val WHAT_TIME = 0x00010450
    const val WEATHER = 0x00010630
    const val ENCRYPT = 0x000107C0

    // Asked by the phone.
    const val APP_IN_FRONT = 0x00020020
    const val SERIAL_CHECKED = 0x000201C0

    // Either way round.
    const val HEARTBEAT = 0x70000000
    const val KNOCK = 0x70000010

    // Instead of a reply.
    const val NOT_SUPPORTED = 0x80000010.toInt()
    const val FAILED = 0x80000020.toInt()

    @Throws(IOException::class)
    fun read(input: InputStream): PxcMessage {
        val head = ByteBuffer.wrap(readFully(input, HEADER)).order(ByteOrder.LITTLE_ENDIAN)
        val command = head.getInt(0)
        val length = head.getInt(4)
        if (command xor length != head.getInt(8)) throw IOException("PXC: cabecera que no cuadra")
        val bodyLength = length - HEADER
        if (bodyLength < 0 || bodyLength > LARGEST_BODY) throw IOException("PXC: longitud imposible $length")
        return PxcMessage(command, readFully(input, bodyLength))
    }

    @Throws(IOException::class)
    fun write(output: OutputStream, command: Int, body: ByteArray = NO_BYTES) {
        val length = HEADER + body.size
        val out = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(command).putInt(length).putInt(command xor length).putInt(0).put(body)
        // One write, so header and body leave in the same packet.
        output.write(out.array())
        output.flush()
    }
}

object Rv {
    const val HEADER = 8

    const val CONFIGURE = 0x0010
    const val KEY_OR_TOUCH = 0x0020
    const val VERSION = 0x0030
    const val HEARTBEAT = 0x0040
    const val CONFIGURE_MORE = 0x0060
    const val START = 0x0070
    const val NEXT_FRAME = 0x0072
    const val SHELL = 0x0110

    const val NOT_SUPPORTED = 0x7F02
    const val START_FAILED = 0x7F05

    @Throws(IOException::class)
    fun read(input: InputStream): RvMessage {
        val head = ByteBuffer.wrap(readFully(input, HEADER)).order(ByteOrder.LITTLE_ENDIAN)
        val command = head.getShort(0).toInt() and 0xFFFF
        val bodyLength = head.getShort(2).toInt() and 0xFFFF
        return RvMessage(command, readFully(input, bodyLength))
    }

    @Throws(IOException::class)
    fun write(output: OutputStream, command: Int, body: ByteArray = NO_BYTES) {
        val out = ByteBuffer.allocate(HEADER + body.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(command.toShort()).putShort(body.size.toShort()).putInt(0).put(body)
        output.write(out.array())
        output.flush()
    }
}

/** What the bike wants the picture to be like: the body of [Rv.CONFIGURE]. */
data class PictureRequest(
    val width: Int,
    val height: Int,
    /** 0 means "I will ask for each frame"; anything else, "send them at this pace". */
    val fps: Int,
    /** 1 JPEG, 2 H.264, 4 MP4. */
    val encoder: Int,
    val bitRate: Int,
    val orientation: Int,
    val displayId: Int,
    val extended: Int,
    /** The bike's own identifier, as the phone signed it in its greeting. */
    val signedId: String
) {
    /** Encoders work in blocks of 16, and the reply says what size they will really get. */
    val encodedWidth get() = width and 15.inv()
    val encodedHeight get() = height and 15.inv()

    companion object {
        fun parse(body: ByteArray): PictureRequest {
            if (body.size < 24) throw IOException("RV: petición de imagen corta (${body.size})")
            val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
            val width = b.getShort().toInt()
            val height = b.getShort().toInt()
            val fps = b.getInt()
            val encoder = b.getInt().let { if (it == 0) 1 else it }
            b.getInt()   // codecs it could take
            b.getShort() // lowest JPEG quality
            b.getShort() // highest
            val bitRate = b.getInt().let { if (it == 0) 3_000_000 else it }
            var orientation = 0
            var displayId = 0
            var extended = 0
            if (b.remaining() >= 8) {
                b.get() // capture mode
                b.get() // touch mode
                orientation = b.get().toInt()
                displayId = b.get().toInt()
                b.get() // video type
                extended = b.get().toInt()
                b.getShort()
            }
            val signedId = if (body.size > 32) String(body, 32, body.size - 32, Charsets.UTF_8) else ""
            return PictureRequest(width, height, fps, encoder, bitRate, orientation, displayId, extended, signedId)
        }
    }

    fun reply(): ByteArray {
        val out = ByteBuffer.allocate(if (extended != 0) 9 else 8).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(encoder).putShort(encodedWidth.toShort()).putShort(encodedHeight.toShort())
        if (extended != 0) out.put(extended.toByte())
        return out.array()
    }
}

/** A handlebar button or a touch, as the bike reports it: the body of [Rv.KEY_OR_TOUCH]. */
data class BikeInput(val type: Int, val x: Int, val y: Int, val slot: Int, val key: Int) {
    companion object {
        const val UP = 1
        const val DOWN = 2
        const val MOVE = 3
        const val KEY = 4

        fun parse(body: ByteArray): BikeInput? {
            if (body.size < 10) return null
            val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
            return BikeInput(
                b.getShort().toInt(), b.getShort().toInt(), b.getShort().toInt(),
                b.getShort().toInt(), b.getShort().toInt() and 0xFFFF
            )
        }
    }
}

private fun readFully(input: InputStream, length: Int): ByteArray {
    val bytes = ByteArray(length)
    var read = 0
    while (read < length) {
        val n = input.read(bytes, read, length - read)
        if (n < 0) throw EOFException("la moto ha cerrado")
        read += n
    }
    return bytes
}
