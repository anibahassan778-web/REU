package com.reu.core.common

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Bounded binary reader supporting both in-memory ByteArray slices and streaming RandomAccessFile
 * seeks without loading large game binaries into RAM.
 */
interface BinarySource : Closeable {
  val length: Long
  var position: Long

  fun readBytes(count: Int): ByteArray

  fun readU8(): Int

  fun readU16LE(): Int

  fun readU16BE(): Int

  fun readU32LE(): Long

  fun readU32BE(): Long

  fun readI32LE(): Int

  fun readI32BE(): Int

  fun readU64LE(): Long

  fun readU64BE(): Long

  fun readNullTerminatedAscii(maxLen: Int = 512): String

  fun align(alignment: Int) {
    val rem = position % alignment
    if (rem != 0L) {
      position += (alignment - rem)
    }
  }
}

class ByteArrayBinarySource(private val data: ByteArray) : BinarySource {
  override val length: Long = data.size.toLong()
  override var position: Long = 0L

  override fun readBytes(count: Int): ByteArray {
    require(count >= 0) { "Negative read count: $count" }
    val pos = position.toInt()
    require(pos + count <= data.size) { "EOF reading $count bytes at offset $pos (size=${data.size})" }
    val slice = data.copyOfRange(pos, pos + count)
    position += count
    return slice
  }

  override fun readU8(): Int {
    val pos = position.toInt()
    require(pos < data.size) { "EOF at $pos" }
    position++
    return data[pos].toInt() and 0xFF
  }

  override fun readU16LE(): Int {
    val b = readBytes(2)
    return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
  }

  override fun readU16BE(): Int {
    val b = readBytes(2)
    return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
  }

  override fun readU32LE(): Long {
    val b = readBytes(4)
    return ((b[0].toLong() and 0xFF)) or
      ((b[1].toLong() and 0xFF) shl 8) or
      ((b[2].toLong() and 0xFF) shl 16) or
      ((b[3].toLong() and 0xFF) shl 24)
  }

  override fun readU32BE(): Long {
    val b = readBytes(4)
    return ((b[0].toLong() and 0xFF) shl 24) or
      ((b[1].toLong() and 0xFF) shl 16) or
      ((b[2].toLong() and 0xFF) shl 8) or
      (b[3].toLong() and 0xFF)
  }

  override fun readI32LE(): Int = readU32LE().toInt()

  override fun readI32BE(): Int = readU32BE().toInt()

  override fun readU64LE(): Long {
    val b = readBytes(8)
    var v = 0L
    for (i in 7 downTo 0) {
      v = (v shl 8) or (b[i].toLong() and 0xFF)
    }
    return v
  }

  override fun readU64BE(): Long {
    val b = readBytes(8)
    var v = 0L
    for (i in 0 until 8) {
      v = (v shl 8) or (b[i].toLong() and 0xFF)
    }
    return v
  }

  override fun readNullTerminatedAscii(maxLen: Int): String {
    val start = position.toInt()
    var end = start
    while (end < data.size && data[end] != 0.toByte() && (end - start) < maxLen) {
      end++
    }
    val text = String(data, start, end - start, StandardCharsets.UTF_8)
    position = if (end < data.size && data[end] == 0.toByte()) (end + 1).toLong() else end.toLong()
    return text
  }

  override fun close() = Unit
}

class FileBinarySource(file: File) : BinarySource {
  private val raf = RandomAccessFile(file, "r")
  override val length: Long = raf.length()
  override var position: Long
    get() = raf.filePointer
    set(value) {
      require(value in 0..length) { "Seek out of bounds: $value (length=$length)" }
      raf.seek(value)
    }

  override fun readBytes(count: Int): ByteArray {
    require(count in 0..(16 * 1024 * 1024)) { "Unbounded read request rejected: $count bytes" }
    val pos = raf.filePointer
    require(pos + count <= length) { "EOF reading $count bytes at offset $pos (length=$length)" }
    val buf = ByteArray(count)
    raf.readFully(buf)
    return buf
  }

  override fun readU8(): Int {
    val v = raf.read()
    require(v >= 0) { "Unexpected EOF at ${raf.filePointer}" }
    return v
  }

  override fun readU16LE(): Int {
    val b = readBytes(2)
    return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
  }

  override fun readU16BE(): Int {
    val b = readBytes(2)
    return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
  }

  override fun readU32LE(): Long {
    val b = readBytes(4)
    return ((b[0].toLong() and 0xFF)) or
      ((b[1].toLong() and 0xFF) shl 8) or
      ((b[2].toLong() and 0xFF) shl 16) or
      ((b[3].toLong() and 0xFF) shl 24)
  }

  override fun readU32BE(): Long {
    val b = readBytes(4)
    return ((b[0].toLong() and 0xFF) shl 24) or
      ((b[1].toLong() and 0xFF) shl 16) or
      ((b[2].toLong() and 0xFF) shl 8) or
      (b[3].toLong() and 0xFF)
  }

  override fun readI32LE(): Int = readU32LE().toInt()

  override fun readI32BE(): Int = readU32BE().toInt()

  override fun readU64LE(): Long {
    val b = readBytes(8)
    var v = 0L
    for (i in 7 downTo 0) {
      v = (v shl 8) or (b[i].toLong() and 0xFF)
    }
    return v
  }

  override fun readU64BE(): Long {
    val b = readBytes(8)
    var v = 0L
    for (i in 0 until 8) {
      v = (v shl 8) or (b[i].toLong() and 0xFF)
    }
    return v
  }

  override fun readNullTerminatedAscii(maxLen: Int): String {
    val bytes = mutableListOf<Byte>()
    var read = 0
    while (raf.filePointer < length && read < maxLen) {
      val b = raf.read()
      if (b <= 0) break
      bytes.add(b.toByte())
      read++
    }
    return String(bytes.toByteArray(), StandardCharsets.UTF_8)
  }

  override fun close() {
    raf.close()
  }
}
