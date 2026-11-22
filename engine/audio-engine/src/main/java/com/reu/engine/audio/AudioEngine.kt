package com.reu.engine.audio

import com.reu.core.common.ByteArrayBinarySource
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.Serializable

@Serializable
data class AudioExtractionResult(
  val success: Boolean,
  val name: String,
  val detectedCodec: String,
  val sampleRate: Int,
  val channels: Int,
  val exportedPath: String?,
  val note: String,
)

class AudioEngine {
  fun inspectAndExportAudioPayload(
    name: String,
    payload: ByteArray,
    outputDir: File,
    fallbackChannels: Int = 2,
    fallbackSampleRate: Int = 44100,
    fallbackBitsPerSample: Int = 0,
  ): AudioExtractionResult {
    if (!outputDir.exists()) outputDir.mkdirs()
    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "audio" }

    // 1. Check Ogg Vorbis magic ("OggS")
    val oggIdx = indexOfMagic(payload, byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte()))
    if (oggIdx >= 0) {
      val outFile = File(outputDir, "$safeName.ogg")
      FileOutputStream(outFile).use { it.write(payload, oggIdx, payload.size - oggIdx) }
      return AudioExtractionResult(
        success = true,
        name = name,
        detectedCodec = "Ogg Vorbis",
        sampleRate = fallbackSampleRate,
        channels = fallbackChannels,
        exportedPath = outFile.absolutePath,
        note = "Extracted native Ogg Vorbis stream.",
      )
    }

    // 2. Check RIFF WAV magic ("RIFF")
    val riffIdx = indexOfMagic(payload, byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
    if (riffIdx >= 0) {
      val outFile = File(outputDir, "$safeName.wav")
      FileOutputStream(outFile).use { it.write(payload, riffIdx, payload.size - riffIdx) }
      return AudioExtractionResult(
        success = true,
        name = name,
        detectedCodec = "RIFF PCM WAV",
        sampleRate = fallbackSampleRate,
        channels = fallbackChannels,
        exportedPath = outFile.absolutePath,
        note = "Extracted RIFF WAV stream.",
      )
    }

    // 3. Check FMOD FSB5 ("FSB5")
    val fsbIdx = indexOfMagic(payload, byteArrayOf('F'.code.toByte(), 'S'.code.toByte(), 'B'.code.toByte(), '5'.code.toByte()))
    if (fsbIdx >= 0 && fsbIdx + 60 <= payload.size) {
      ByteArrayBinarySource(payload).use { src ->
        src.position = (fsbIdx + 4).toLong()
        val version = src.readI32LE()
        val numSamples = src.readI32LE()
        val sampleHeadersSize = src.readI32LE()
        val nameTableSize = src.readI32LE()
        val dataSize = src.readI32LE()
        val mode = src.readI32LE()
        val headerBase = if (version == 0) 64 else 60
        val dataStart = fsbIdx + headerBase + sampleHeadersSize + nameTableSize

        // Attempt direct WAV decoding when FSB5 contains uncompressed PCM (modes 1..5)
        if (mode in 1..4 && numSamples >= 1 && dataStart < payload.size && dataSize > 0) {
          val safeLen = minOf(dataSize, payload.size - dataStart)
          if (mode == 2 && safeLen >= 2) {
            val sampleCount = safeLen / 2
            val pcm = ShortArray(sampleCount)
            for (i in 0 until sampleCount) {
              val b0 = payload[dataStart + i * 2].toInt() and 0xFF
              val b1 = payload[dataStart + i * 2 + 1].toInt()
              pcm[i] = ((b1 shl 8) or b0).toShort()
            }
            return writePcm16Wav(name, pcm, fallbackSampleRate.coerceAtLeast(8000), fallbackChannels.coerceIn(1, 8), outputDir)
          }
          if (mode == 1 && safeLen >= 1) {
            val pcm = ShortArray(safeLen)
            for (i in 0 until safeLen) {
              val u = (payload[dataStart + i].toInt() and 0xFF) - 128
              pcm[i] = (u shl 8).toShort()
            }
            return writePcm16Wav(name, pcm, fallbackSampleRate.coerceAtLeast(8000), fallbackChannels.coerceIn(1, 8), outputDir)
          }
        }

        val outFile = File(outputDir, "$safeName.fsb5")
        FileOutputStream(outFile).use { it.write(payload, fsbIdx, payload.size - fsbIdx) }
        return AudioExtractionResult(
          success = true,
          name = name,
          detectedCodec = "FMOD FSB5 (mode=$mode, v=$version)",
          sampleRate = fallbackSampleRate,
          channels = fallbackChannels,
          exportedPath = outFile.absolutePath,
          note = "Preserved original FSB5 container ($numSamples sub-samples, dataSize=$dataSize bytes).",
        )
      }
    }

    // 4. Raw PCM fallback ONLY when explicit AudioClip metadata confirms uncompressed PCM (compressionFormat == 0)
    if (fallbackBitsPerSample == 16 && payload.size >= 4 && fallbackSampleRate in 8000..192000 && fallbackChannels in 1..8) {
      val sampleCount = payload.size / 2
      val pcm = ShortArray(sampleCount)
      for (i in 0 until sampleCount) {
        val b0 = payload[i * 2].toInt() and 0xFF
        val b1 = payload[i * 2 + 1].toInt()
        pcm[i] = ((b1 shl 8) or b0).toShort()
      }
      return writePcm16Wav(name, pcm, fallbackSampleRate, fallbackChannels, outputDir)
    }

    return AudioExtractionResult(
      success = false,
      name = name,
      detectedCodec = "Unknown",
      sampleRate = 0,
      channels = 0,
      exportedPath = null,
      note = "No recognized OggS, RIFF WAV, or FSB5 audio signature found.",
    )
  }

  fun writePcm16Wav(
    name: String,
    pcmSamples: ShortArray,
    sampleRate: Int,
    channels: Int,
    outputDir: File,
  ): AudioExtractionResult {
    if (!outputDir.exists()) outputDir.mkdirs()
    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "audio" }
    val outFile = File(outputDir, "$safeName.wav")
    val dataSize = pcmSamples.size * 2
    val byteRate = sampleRate * channels * 2
    val blockAlign = channels * 2

    FileOutputStream(outFile).use { fos ->
      fun writeIntLE(v: Int) {
        fos.write(v and 0xFF)
        fos.write((v ushr 8) and 0xFF)
        fos.write((v ushr 16) and 0xFF)
        fos.write((v ushr 24) and 0xFF)
      }
      fun writeShortLE(v: Int) {
        fos.write(v and 0xFF)
        fos.write((v ushr 8) and 0xFF)
      }
      fos.write("RIFF".toByteArray(Charsets.US_ASCII))
      writeIntLE(36 + dataSize)
      fos.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
      writeIntLE(16)
      writeShortLE(1)
      writeShortLE(channels)
      writeIntLE(sampleRate)
      writeIntLE(byteRate)
      writeShortLE(blockAlign)
      writeShortLE(16)
      fos.write("data".toByteArray(Charsets.US_ASCII))
      writeIntLE(dataSize)
      val buf = ByteArray(dataSize)
      for (i in pcmSamples.indices) {
        val s = pcmSamples[i].toInt()
        buf[i * 2] = (s and 0xFF).toByte()
        buf[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
      }
      fos.write(buf)
    }

    return AudioExtractionResult(
      success = true,
      name = name,
      detectedCodec = "PCM16 WAV",
      sampleRate = sampleRate,
      channels = channels,
      exportedPath = outFile.absolutePath,
      note = "Exported ${pcmSamples.size} PCM16 samples to WAV.",
    )
  }

  private fun indexOfMagic(haystack: ByteArray, needle: ByteArray): Int {
    if (haystack.size < needle.size) return -1
    for (i in 0..(haystack.size - needle.size)) {
      var match = true
      for (j in needle.indices) {
        if (haystack[i + j] != needle[j]) {
          match = false
          break
        }
      }
      if (match) return i
    }
    return -1
  }
}
