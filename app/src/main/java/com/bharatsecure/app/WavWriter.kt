package com.bharatsecure.app

import java.io.File
import java.io.RandomAccessFile

/** Streams 16-bit mono PCM into a .wav file, fixing up the header on close. */
class WavWriter(file: File, private val sampleRate: Int) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0) }
    private var dataBytes = 0L
    private val scratch = ByteArray(8192 * 2)

    init {
        writeHeader()
    }

    fun write(samples: ShortArray, count: Int) {
        val bytes = if (count * 2 <= scratch.size) scratch else ByteArray(count * 2)
        for (i in 0 until count) {
            val s = samples[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = (s shr 8 and 0xFF).toByte()
        }
        raf.write(bytes, 0, count * 2)
        dataBytes += count * 2
    }

    override fun close() {
        writeHeader()
        raf.close()
    }

    private fun writeHeader() {
        val byteRate = sampleRate * 2
        raf.seek(0)
        raf.writeBytes("RIFF")
        raf.writeIntLe((36 + dataBytes).toInt())
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        raf.writeIntLe(16)
        raf.writeShortLe(1) // PCM
        raf.writeShortLe(1) // mono
        raf.writeIntLe(sampleRate)
        raf.writeIntLe(byteRate)
        raf.writeShortLe(2) // block align
        raf.writeShortLe(16) // bits per sample
        raf.writeBytes("data")
        raf.writeIntLe(dataBytes.toInt())
        raf.seek(44 + dataBytes)
    }

    private fun RandomAccessFile.writeIntLe(v: Int) {
        write(v and 0xFF); write(v shr 8 and 0xFF); write(v shr 16 and 0xFF); write(v shr 24 and 0xFF)
    }

    private fun RandomAccessFile.writeShortLe(v: Int) {
        write(v and 0xFF); write(v shr 8 and 0xFF)
    }
}
