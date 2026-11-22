package com.reu.engine.texture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlinx.serialization.Serializable

enum class TextureExportFormat {
  PNG,
  JPG,
  TGA,
}

@Serializable
data class TextureExtractionResult(
  val success: Boolean,
  val name: String,
  val width: Int,
  val height: Int,
  val unityFormat: String,
  val exportedPath: String?,
  val format: String,
  val note: String,
)

class TextureEngine {
  companion object {
    fun unityTextureFormatName(formatId: Int): String =
      when (formatId) {
        1 -> "Alpha8"
        2 -> "ARGB4444"
        3 -> "RGB24"
        4 -> "RGBA32"
        5 -> "ARGB32"
        7 -> "RGB565"
        10 -> "DXT1"
        12 -> "DXT5"
        13 -> "RGBA4444"
        14 -> "BGRA32"
        34 -> "ETC_RGB4"
        45 -> "ETC2_RGB"
        46 -> "ETC2_RGBA1"
        47 -> "ETC2_RGBA8"
        48 -> "ASTC_4x4"
        49 -> "ASTC_5x5"
        50 -> "ASTC_6x6"
        51 -> "ASTC_8x8"
        else -> "UnityFmt_$formatId"
      }

    private val ETC_MODIFIER_TABLE =
      arrayOf(
        intArrayOf(2, 8, -2, -8),
        intArrayOf(5, 17, -5, -17),
        intArrayOf(9, 29, -9, -29),
        intArrayOf(13, 42, -13, -42),
        intArrayOf(18, 60, -18, -60),
        intArrayOf(24, 80, -24, -80),
        intArrayOf(33, 106, -33, -106),
        intArrayOf(47, 183, -47, -183),
      )

    private val ETC2_DISTANCE_TABLE = intArrayOf(3, 6, 11, 16, 23, 32, 41, 64)
  }

  /**
   * Decodes Unity Texture2D raw pixel/block buffers (Alpha8, RGB24, RGBA32, ARGB32, BGRA32,
   * RGB565, ARGB4444, RGBA4444, DXT1, DXT5, ETC_RGB4, ETC2_RGB, ETC2_RGBA8) into top-down RGBA32
   * bytes and exports to PNG/JPG/TGA.
   */
  fun decodeAndExportUnityTexture(
    name: String,
    width: Int,
    height: Int,
    unityFormatId: Int,
    rawBytes: ByteArray,
    outputDir: File,
    exportFormat: TextureExportFormat = TextureExportFormat.PNG,
    flipVertical: Boolean = true,
  ): TextureExtractionResult {
    val fmtName = unityTextureFormatName(unityFormatId)
    if (width <= 0 || height <= 0 || rawBytes.isEmpty()) {
      return TextureExtractionResult(
        success = false,
        name = name,
        width = width,
        height = height,
        unityFormat = fmtName,
        exportedPath = null,
        format = exportFormat.name,
        note = "Dynamic or empty texture payload (${rawBytes.size} bytes).",
      )
    }

    val decoded = decodeUnityFormatToRgba(width, height, unityFormatId, rawBytes)
      ?: return TextureExtractionResult(
        success = false,
        name = name,
        width = width,
        height = height,
        unityFormat = fmtName,
        exportedPath = null,
        format = exportFormat.name,
        note = "Unsupported GPU compression format $fmtName ($unityFormatId) or insufficient data (${rawBytes.size} B).",
      )

    val oriented = if (flipVertical) flipRgbaVertical(width, height, decoded) else decoded
    return exportRgbaTexture(
      name = name,
      width = width,
      height = height,
      rgbaPixels = oriented,
      outputDir = outputDir,
      format = exportFormat,
      unityFormatName = fmtName,
    )
  }

  fun decodeUnityFormatToRgba(
    width: Int,
    height: Int,
    unityFormatId: Int,
    rawBytes: ByteArray,
  ): ByteArray? {
    val pxCount = width.toLong() * height.toLong()
    if (pxCount <= 0 || pxCount > 32_000_000L) return null
    val n = pxCount.toInt()
    return when (unityFormatId) {
      1 -> { // Alpha8
        if (rawBytes.size < n) return null
        ByteArray(n * 4).also { out ->
          for (i in 0 until n) {
            val off = i * 4
            out[off] = 0xFF.toByte()
            out[off + 1] = 0xFF.toByte()
            out[off + 2] = 0xFF.toByte()
            out[off + 3] = rawBytes[i]
          }
        }
      }
      2 -> { // ARGB4444
        if (rawBytes.size < n * 2) return null
        ByteArray(n * 4).also { out ->
          for (i in 0 until n) {
            val v = (rawBytes[i * 2].toInt() and 0xFF) or ((rawBytes[i * 2 + 1].toInt() and 0xFF) shl 8)
            val a = ((v ushr 12) and 0xF) * 17
            val r = ((v ushr 8) and 0xF) * 17
            val g = ((v ushr 4) and 0xF) * 17
            val b = (v and 0xF) * 17
            val off = i * 4
            out[off] = r.toByte()
            out[off + 1] = g.toByte()
            out[off + 2] = b.toByte()
            out[off + 3] = a.toByte()
          }
        }
      }
      3 -> { // RGB24
        if (rawBytes.size < n * 3) return null
        ByteArray(n * 4).also { out ->
          for (i in 0 until n) {
            val s = i * 3
            val d = i * 4
            out[d] = rawBytes[s]
            out[d + 1] = rawBytes[s + 1]
            out[d + 2] = rawBytes[s + 2]
            out[d + 3] = 0xFF.toByte()
          }
        }
      }
      4 -> { // RGBA32
        if (rawBytes.size < n * 4) return null
        rawBytes.copyOfRange(0, n * 4)
      }
      5 -> { // ARGB32
        if (rawBytes.size < n * 4) return null
        ByteArray(n * 4).also { out ->
          for (i in 0 until n) {
            val o = i * 4
            out[o] = rawBytes[o + 1]
            out[o + 1] = rawBytes[o + 2]
            out[o + 2] = rawBytes[o + 3]
            out[o + 3] = rawBytes[o]
          }
        }
      }
      7 -> { // RGB565
        if (rawBytes.size < n * 2) return null
        ByteArray(n * 4).also { out ->
          for (i in 0 until n) {
            val v = (rawBytes[i * 2].toInt() and 0xFF) or ((rawBytes[i * 2 + 1].toInt() and 0xFF) shl 8)
            val rgba = rgb565ToRgba(v)
            val o = i * 4
            out[o] = rgba[0].toByte()
            out[o + 1] = rgba[1].toByte()
            out[o + 2] = rgba[2].toByte()
            out[o + 3] = 0xFF.toByte()
          }
        }
      }
      10 -> { // DXT1
        val expected = ((width + 3) / 4) * ((height + 3) / 4) * 8
        if (rawBytes.size < expected) return null
        decodeDxt1ToRgba(width, height, rawBytes)
      }
      12 -> { // DXT5
        val expected = ((width + 3) / 4) * ((height + 3) / 4) * 16
        if (rawBytes.size < expected) return null
        decodeDxt5ToRgba(width, height, rawBytes)
      }
      13 -> { // RGBA4444
        if (rawBytes.size < n * 2) return null
        ByteArray(n * 4).also { out ->
          for (i in 0 until n) {
            val v = (rawBytes[i * 2].toInt() and 0xFF) or ((rawBytes[i * 2 + 1].toInt() and 0xFF) shl 8)
            val r = ((v ushr 12) and 0xF) * 17
            val g = ((v ushr 8) and 0xF) * 17
            val b = ((v ushr 4) and 0xF) * 17
            val a = (v and 0xF) * 17
            val off = i * 4
            out[off] = r.toByte()
            out[off + 1] = g.toByte()
            out[off + 2] = b.toByte()
            out[off + 3] = a.toByte()
          }
        }
      }
      14 -> { // BGRA32
        if (rawBytes.size < n * 4) return null
        ByteArray(n * 4).also { out ->
          for (i in 0 until n) {
            val o = i * 4
            out[o] = rawBytes[o + 2]
            out[o + 1] = rawBytes[o + 1]
            out[o + 2] = rawBytes[o]
            out[o + 3] = rawBytes[o + 3]
          }
        }
      }
      34, 45 -> { // ETC_RGB4 / ETC2_RGB
        val expected = ((width + 3) / 4) * ((height + 3) / 4) * 8
        if (rawBytes.size < expected) return null
        decodeEtc2ToRgba(width, height, rawBytes, hasAlpha = false)
      }
      47 -> { // ETC2_RGBA8 (EAC alpha + ETC2 RGB)
        val expected = ((width + 3) / 4) * ((height + 3) / 4) * 16
        if (rawBytes.size < expected) return null
        decodeEtc2ToRgba(width, height, rawBytes, hasAlpha = true)
      }
      else -> null
    }
  }

  fun flipRgbaVertical(width: Int, height: Int, rgba: ByteArray): ByteArray {
    val rowBytes = width * 4
    val out = ByteArray(rgba.size)
    for (y in 0 until height) {
      val srcOff = (height - 1 - y) * rowBytes
      val dstOff = y * rowBytes
      System.arraycopy(rgba, srcOff, out, dstOff, rowBytes)
    }
    return out
  }

  fun cropRgba(
    rgbaTopDown: ByteArray,
    srcWidth: Int,
    srcHeight: Int,
    rectX: Int,
    rectYFromBottom: Int,
    cropW: Int,
    cropH: Int,
  ): ByteArray? {
    if (cropW <= 0 || cropH <= 0 || srcWidth <= 0 || srcHeight <= 0) return null
    val out = ByteArray(cropW * cropH * 4)
    val startYTop = srcHeight - (rectYFromBottom + cropH)
    for (y in 0 until cropH) {
      val sy = startYTop + y
      if (sy !in 0 until srcHeight) continue
      for (x in 0 until cropW) {
        val sx = rectX + x
        if (sx !in 0 until srcWidth) continue
        val srcIdx = (sy * srcWidth + sx) * 4
        val dstIdx = (y * cropW + x) * 4
        System.arraycopy(rgbaTopDown, srcIdx, out, dstIdx, 4)
      }
    }
    return out
  }

  /**
   * Exports raw RGBA32 pixels into PNG, JPG, or TGA. Uses a deterministic pure-Kotlin PNG writer
   * so both Android runtime and JVM/Robolectric produce valid RGBA PNG files without shadow gaps.
   */
  fun exportRgbaTexture(
    name: String,
    width: Int,
    height: Int,
    rgbaPixels: ByteArray,
    outputDir: File,
    format: TextureExportFormat,
    unityFormatName: String = "RGBA32",
  ): TextureExtractionResult {
    if (width <= 0 || height <= 0 || rgbaPixels.size < width * height * 4) {
      return TextureExtractionResult(
        success = false,
        name = name,
        width = width,
        height = height,
        unityFormat = unityFormatName,
        exportedPath = null,
        format = format.name,
        note = "Invalid texture dimensions or insufficient pixel buffer (${rgbaPixels.size} bytes).",
      )
    }
    if (!outputDir.exists()) outputDir.mkdirs()

    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "texture" }
    val outFile =
      when (format) {
        TextureExportFormat.PNG -> {
          File(outputDir, "$safeName.png").also { f -> writePngRgba(f, width, height, rgbaPixels) }
        }
        TextureExportFormat.JPG -> {
          File(outputDir, "$safeName.jpg").also { f ->
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val argbInts = IntArray(width * height)
            for (i in argbInts.indices) {
              val off = i * 4
              val r = rgbaPixels[off].toInt() and 0xFF
              val g = rgbaPixels[off + 1].toInt() and 0xFF
              val b = rgbaPixels[off + 2].toInt() and 0xFF
              val a = rgbaPixels[off + 3].toInt() and 0xFF
              argbInts[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            bitmap.setPixels(argbInts, 0, width, 0, 0, width, height)
            FileOutputStream(f).use { fos -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos) }
            bitmap.recycle()
          }
        }
        TextureExportFormat.TGA -> {
          File(outputDir, "$safeName.tga").also { f -> writeTga32(f, width, height, rgbaPixels) }
        }
      }

    return TextureExtractionResult(
      success = true,
      name = name,
      width = width,
      height = height,
      unityFormat = unityFormatName,
      exportedPath = outFile.absolutePath,
      format = format.name,
      note = "Exported ${width}x${height} $unityFormatName -> ${format.name}",
    )
  }

  fun transcodeExistingImage(
    sourceFile: File,
    outputDir: File,
    targetFormat: TextureExportFormat,
  ): TextureExtractionResult {
    val bmp = BitmapFactory.decodeFile(sourceFile.absolutePath)
      ?: return TextureExtractionResult(
        success = false,
        name = sourceFile.nameWithoutExtension,
        width = 0,
        height = 0,
        unityFormat = "Unknown",
        exportedPath = null,
        format = targetFormat.name,
        note = "Unable to decode source image file ${sourceFile.name}",
      )
    val w = bmp.width
    val h = bmp.height
    val pixels = IntArray(w * h)
    bmp.getPixels(pixels, 0, w, 0, 0, w, h)
    bmp.recycle()

    val rgba = ByteArray(w * h * 4)
    for (i in pixels.indices) {
      val c = pixels[i]
      rgba[i * 4] = ((c ushr 16) and 0xFF).toByte()
      rgba[i * 4 + 1] = ((c ushr 8) and 0xFF).toByte()
      rgba[i * 4 + 2] = (c and 0xFF).toByte()
      rgba[i * 4 + 3] = ((c ushr 24) and 0xFF).toByte()
    }
    return exportRgbaTexture(sourceFile.nameWithoutExtension, w, h, rgba, outputDir, targetFormat, "Encoded")
  }

  fun decodeDxt1ToRgba(width: Int, height: Int, dxt1Bytes: ByteArray): ByteArray {
    val out = ByteArray(width * height * 4)
    val blocksX = (width + 3) / 4
    val blocksY = (height + 3) / 4
    var offset = 0
    for (by in 0 until blocksY) {
      for (bx in 0 until blocksX) {
        if (offset + 8 > dxt1Bytes.size) return out
        decodeDxt1Block(dxt1Bytes, offset, bx * 4, by * 4, width, height, out, forceOpaque = false)
        offset += 8
      }
    }
    return out
  }

  fun decodeDxt5ToRgba(width: Int, height: Int, dxt5Bytes: ByteArray): ByteArray {
    val out = ByteArray(width * height * 4)
    val blocksX = (width + 3) / 4
    val blocksY = (height + 3) / 4
    var offset = 0
    val alphas = IntArray(8)
    for (by in 0 until blocksY) {
      for (bx in 0 until blocksX) {
        if (offset + 16 > dxt5Bytes.size) return out
        val a0 = dxt5Bytes[offset].toInt() and 0xFF
        val a1 = dxt5Bytes[offset + 1].toInt() and 0xFF
        alphas[0] = a0
        alphas[1] = a1
        if (a0 > a1) {
          for (k in 1..6) alphas[k + 1] = ((7 - k) * a0 + k * a1) / 7
        } else {
          for (k in 1..4) alphas[k + 1] = ((5 - k) * a0 + k * a1) / 5
          alphas[6] = 0
          alphas[7] = 255
        }
        var alphaBits = 0L
        for (k in 0 until 6) {
          alphaBits = alphaBits or ((dxt5Bytes[offset + 2 + k].toLong() and 0xFFL) shl (8 * k))
        }
        decodeDxt1Block(dxt5Bytes, offset + 8, bx * 4, by * 4, width, height, out, forceOpaque = true)
        for (py in 0 until 4) {
          for (px in 0 until 4) {
            val ix = bx * 4 + px
            val iy = by * 4 + py
            if (ix < width && iy < height) {
              val aIdx = ((alphaBits ushr (3 * (py * 4 + px))) and 0x7L).toInt()
              out[(iy * width + ix) * 4 + 3] = alphas[aIdx].toByte()
            }
          }
        }
        offset += 16
      }
    }
    return out
  }

  private fun decodeDxt1Block(
    src: ByteArray,
    offset: Int,
    startX: Int,
    startY: Int,
    width: Int,
    height: Int,
    out: ByteArray,
    forceOpaque: Boolean,
  ) {
    val c0 = (src[offset].toInt() and 0xFF) or ((src[offset + 1].toInt() and 0xFF) shl 8)
    val c1 = (src[offset + 2].toInt() and 0xFF) or ((src[offset + 3].toInt() and 0xFF) shl 8)
    val bits =
      (src[offset + 4].toInt() and 0xFF) or
        ((src[offset + 5].toInt() and 0xFF) shl 8) or
        ((src[offset + 6].toInt() and 0xFF) shl 16) or
        ((src[offset + 7].toInt() and 0xFF) shl 24)

    val palette = Array(4) { IntArray(4) }
    palette[0] = rgb565ToRgba(c0)
    palette[1] = rgb565ToRgba(c1)
    if (forceOpaque || c0 > c1) {
      for (ch in 0..2) {
        palette[2][ch] = (2 * palette[0][ch] + palette[1][ch]) / 3
        palette[3][ch] = (palette[0][ch] + 2 * palette[1][ch]) / 3
      }
      palette[2][3] = 255
      palette[3][3] = 255
    } else {
      for (ch in 0..2) {
        palette[2][ch] = (palette[0][ch] + palette[1][ch]) / 2
        palette[3][ch] = 0
      }
      palette[2][3] = 255
      palette[3][3] = 0
    }

    for (py in 0 until 4) {
      for (px in 0 until 4) {
        val ix = startX + px
        val iy = startY + py
        if (ix < width && iy < height) {
          val code = (bits ushr (2 * (py * 4 + px))) and 0x03
          val dst = (iy * width + ix) * 4
          out[dst] = palette[code][0].toByte()
          out[dst + 1] = palette[code][1].toByte()
          out[dst + 2] = palette[code][2].toByte()
          out[dst + 3] = palette[code][3].toByte()
        }
      }
    }
  }

  private fun decodeEtc2ToRgba(
    width: Int,
    height: Int,
    src: ByteArray,
    hasAlpha: Boolean,
  ): ByteArray {
    val out = ByteArray(width * height * 4)
    val blocksX = (width + 3) / 4
    val blocksY = (height + 3) / 4
    val stride = if (hasAlpha) 16 else 8
    var offset = 0
    for (by in 0 until blocksY) {
      for (bx in 0 until blocksX) {
        if (offset + stride > src.size) return out
        val colorOffset = if (hasAlpha) offset + 8 else offset
        decodeEtc2ColorBlock(src, colorOffset, bx * 4, by * 4, width, height, out)
        if (hasAlpha) {
          decodeEacAlphaBlock(src, offset, bx * 4, by * 4, width, height, out)
        }
        offset += stride
      }
    }
    return out
  }

  private fun decodeEtc2ColorBlock(
    src: ByteArray,
    off: Int,
    startX: Int,
    startY: Int,
    width: Int,
    height: Int,
    out: ByteArray,
  ) {
    val hi =
      ((src[off].toLong() and 0xFF) shl 24) or
        ((src[off + 1].toLong() and 0xFF) shl 16) or
        ((src[off + 2].toLong() and 0xFF) shl 8) or
        (src[off + 3].toLong() and 0xFF)
    val lo =
      ((src[off + 4].toLong() and 0xFF) shl 24) or
        ((src[off + 5].toLong() and 0xFF) shl 16) or
        ((src[off + 6].toLong() and 0xFF) shl 8) or
        (src[off + 7].toLong() and 0xFF)

    val diff = ((hi ushr 1) and 1L) != 0L
    if (diff) {
      val rBase = ((hi ushr 27) and 0x1FL).toInt()
      val dr = signExtend3(((hi ushr 24) and 0x7L).toInt())
      val gBase = ((hi ushr 19) and 0x1FL).toInt()
      val dg = signExtend3(((hi ushr 16) and 0x7L).toInt())
      val bBase = ((hi ushr 11) and 0x1FL).toInt()
      val db = signExtend3(((hi ushr 8) and 0x7L).toInt())
      val rSum = rBase + dr
      val gSum = gBase + dg
      val bSum = bBase + db

      if (rSum !in 0..31) {
        // ETC2 T-mode
        val r1 = (((hi ushr 27) and 0x3L) or (((hi ushr 24) and 0x3L) shl 2)).toInt()
        val g1 = ((hi ushr 20) and 0xFL).toInt()
        val b1 = ((hi ushr 16) and 0xFL).toInt()
        val r2 = ((hi ushr 12) and 0xFL).toInt()
        val g2 = ((hi ushr 8) and 0xFL).toInt()
        val b2 = ((hi ushr 4) and 0xFL).toInt()
        val dist = ETC2_DISTANCE_TABLE[((((hi ushr 2) and 0x3L) shl 1) or (hi and 0x1L)).toInt()]
        val c1 = intArrayOf((r1 shl 4) or r1, (g1 shl 4) or g1, (b1 shl 4) or b1)
        val c2 = intArrayOf((r2 shl 4) or r2, (g2 shl 4) or g2, (b2 shl 4) or b2)
        val pal =
          arrayOf(
            c1,
            intArrayOf(clampByte(c2[0] + dist), clampByte(c2[1] + dist), clampByte(c2[2] + dist)),
            c2,
            intArrayOf(clampByte(c2[0] - dist), clampByte(c2[1] - dist), clampByte(c2[2] - dist)),
          )
        writeEtc2IndexedPixels(lo, pal, startX, startY, width, height, out)
        return
      }
      if (gSum !in 0..31) {
        // ETC2 H-mode
        val r1 = ((hi ushr 27) and 0xFL).toInt()
        val g1 = ((((hi ushr 24) and 0x7L) shl 1) or ((hi ushr 20) and 0x1L)).toInt()
        val b1 = (((hi ushr 19) and 0x1L).shl(3) or ((hi ushr 15) and 0x7L)).toInt()
        val r2 = ((hi ushr 11) and 0xFL).toInt()
        val g2 = ((hi ushr 7) and 0xFL).toInt()
        val b2 = ((hi ushr 3) and 0xFL).toInt()
        val c1 = intArrayOf((r1 shl 4) or r1, (g1 shl 4) or g1, (b1 shl 4) or b1)
        val c2 = intArrayOf((r2 shl 4) or r2, (g2 shl 4) or g2, (b2 shl 4) or b2)
        val dBit = if (((c1[0] shl 16) or (c1[1] shl 8) or c1[2]) >= ((c2[0] shl 16) or (c2[1] shl 8) or c2[2])) 1 else 0
        val distIdx = ((((hi ushr 2) and 0x1L) shl 2) or ((hi and 0x1L) shl 1) or dBit.toLong()).toInt()
        val dist = ETC2_DISTANCE_TABLE[distIdx]
        val pal =
          arrayOf(
            intArrayOf(clampByte(c1[0] + dist), clampByte(c1[1] + dist), clampByte(c1[2] + dist)),
            intArrayOf(clampByte(c1[0] - dist), clampByte(c1[1] - dist), clampByte(c1[2] - dist)),
            intArrayOf(clampByte(c2[0] + dist), clampByte(c2[1] + dist), clampByte(c2[2] + dist)),
            intArrayOf(clampByte(c2[0] - dist), clampByte(c2[1] - dist), clampByte(c2[2] - dist)),
          )
        writeEtc2IndexedPixels(lo, pal, startX, startY, width, height, out)
        return
      }
      if (bSum !in 0..31) {
        // ETC2 Planar mode
        val ro = ((hi ushr 25) and 0x3FL).toInt()
        val go = ((((hi ushr 24) and 0x1L) shl 6) or ((hi ushr 17) and 0x3FL)).toInt()
        val bo = ((((hi ushr 16) and 0x1L) shl 5) or (((hi ushr 13) and 0x3L) shl 3) or ((hi ushr 9) and 0x7L)).toInt()
        val rh = ((((hi ushr 4) and 0x1FL) shl 1) or ((hi ushr 2) and 0x1L)).toInt()
        val gh = ((((hi and 0x3L) shl 5) or ((lo ushr 27) and 0x1FL))).toInt() // approx packed
        // Standard 6-7-6 unpack from 64-bit block:
        val b64 = (hi shl 32) or (lo and 0xFFFFFFFFL)
        val pRo = ((b64 ushr 57) and 0x3FL).toInt()
        val pGo = ((b64 ushr 49) and 0x7FL).toInt()
        val pBo = ((b64 ushr 42) and 0x3FL).toInt()
        val pRh = ((b64 ushr 36) and 0x3FL).toInt()
        val pGh = ((b64 ushr 29) and 0x7FL).toInt()
        val pBh = ((b64 ushr 23) and 0x3FL).toInt()
        val pRv = ((b64 ushr 17) and 0x3FL).toInt()
        val pGv = ((b64 ushr 10) and 0x7FL).toInt()
        val pBv = ((b64 ushr 4) and 0x3FL).toInt()
        val cRo = (pRo shl 2) or (pRo ushr 4)
        val cGo = (pGo shl 1) or (pGo ushr 6)
        val cBo = (pBo shl 2) or (pBo ushr 4)
        val cRh = (pRh shl 2) or (pRh ushr 4)
        val cGh = (pGh shl 1) or (pGh ushr 6)
        val cBh = (pBh shl 2) or (pBh ushr 4)
        val cRv = (pRv shl 2) or (pRv ushr 4)
        val cGv = (pGv shl 1) or (pGv ushr 6)
        val cBv = (pBv shl 2) or (pBv ushr 4)
        for (py in 0 until 4) {
          for (px in 0 until 4) {
            val ix = startX + px
            val iy = startY + py
            if (ix < width && iy < height) {
              val r = clampByte((px * (cRh - cRo) + py * (cRv - cRo) + 4 * cRo + 2) shr 2)
              val g = clampByte((px * (cGh - cGo) + py * (cGv - cGo) + 4 * cGo + 2) shr 2)
              val b = clampByte((px * (cBh - cBo) + py * (cBv - cBo) + 4 * cBo + 2) shr 2)
              val dst = (iy * width + ix) * 4
              out[dst] = r.toByte()
              out[dst + 1] = g.toByte()
              out[dst + 2] = b.toByte()
              out[dst + 3] = 0xFF.toByte()
            }
          }
        }
        return
      }

      val r1 = (rBase shl 3) or (rBase ushr 2)
      val g1 = (gBase shl 3) or (gBase ushr 2)
      val b1 = (bBase shl 3) or (bBase ushr 2)
      val r2 = (rSum shl 3) or (rSum ushr 2)
      val g2 = (gSum shl 3) or (gSum ushr 2)
      val b2 = (bSum shl 3) or (bSum ushr 2)
      decodeEtc1Subblocks(hi, lo, r1, g1, b1, r2, g2, b2, startX, startY, width, height, out)
    } else {
      val r1 = ((hi ushr 28) and 0xFL).toInt().let { (it shl 4) or it }
      val r2 = ((hi ushr 24) and 0xFL).toInt().let { (it shl 4) or it }
      val g1 = ((hi ushr 20) and 0xFL).toInt().let { (it shl 4) or it }
      val g2 = ((hi ushr 16) and 0xFL).toInt().let { (it shl 4) or it }
      val b1 = ((hi ushr 12) and 0xFL).toInt().let { (it shl 4) or it }
      val b2 = ((hi ushr 8) and 0xFL).toInt().let { (it shl 4) or it }
      decodeEtc1Subblocks(hi, lo, r1, g1, b1, r2, g2, b2, startX, startY, width, height, out)
    }
  }

  private fun decodeEtc1Subblocks(
    hi: Long,
    lo: Long,
    r1: Int,
    g1: Int,
    b1: Int,
    r2: Int,
    g2: Int,
    b2: Int,
    startX: Int,
    startY: Int,
    width: Int,
    height: Int,
    out: ByteArray,
  ) {
    val t1 = ETC_MODIFIER_TABLE[((hi ushr 5) and 0x7L).toInt()]
    val t2 = ETC_MODIFIER_TABLE[((hi ushr 2) and 0x7L).toInt()]
    val flip = (hi and 1L) != 0L
    for (py in 0 until 4) {
      for (px in 0 until 4) {
        val ix = startX + px
        val iy = startY + py
        if (ix >= width || iy >= height) continue
        val bitIndex = px * 4 + py
        val msb = ((lo ushr (16 + bitIndex)) and 1L).toInt()
        val lsb = ((lo ushr bitIndex) and 1L).toInt()
        val code = (msb shl 1) or lsb
        val modIdx = when (code) {
          0 -> 0
          1 -> 1
          2 -> 2
          else -> 3
        }
        val useSub1 = if (flip) py < 2 else px < 2
        val delta = if (useSub1) t1[modIdx] else t2[modIdx]
        val br = if (useSub1) r1 else r2
        val bg = if (useSub1) g1 else g2
        val bb = if (useSub1) b1 else b2
        val dst = (iy * width + ix) * 4
        out[dst] = clampByte(br + delta).toByte()
        out[dst + 1] = clampByte(bg + delta).toByte()
        out[dst + 2] = clampByte(bb + delta).toByte()
        out[dst + 3] = 0xFF.toByte()
      }
    }
  }

  private fun writeEtc2IndexedPixels(
    lo: Long,
    pal: Array<IntArray>,
    startX: Int,
    startY: Int,
    width: Int,
    height: Int,
    out: ByteArray,
  ) {
    for (py in 0 until 4) {
      for (px in 0 until 4) {
        val ix = startX + px
        val iy = startY + py
        if (ix >= width || iy >= height) continue
        val bitIndex = px * 4 + py
        val msb = ((lo ushr (16 + bitIndex)) and 1L).toInt()
        val lsb = ((lo ushr bitIndex) and 1L).toInt()
        val c = pal[(msb shl 1) or lsb]
        val dst = (iy * width + ix) * 4
        out[dst] = c[0].toByte()
        out[dst + 1] = c[1].toByte()
        out[dst + 2] = c[2].toByte()
        out[dst + 3] = 0xFF.toByte()
      }
    }
  }

  private fun decodeEacAlphaBlock(
    src: ByteArray,
    off: Int,
    startX: Int,
    startY: Int,
    width: Int,
    height: Int,
    out: ByteArray,
  ) {
    val base = src[off].toInt() and 0xFF
    val mult = (src[off + 1].toInt() ushr 4) and 0xF
    val tableIdx = src[off + 1].toInt() and 0xF
    val eacTable =
      arrayOf(
        intArrayOf(-3, -6, -9, -15, 2, 5, 8, 14),
        intArrayOf(-3, -7, -10, -13, 2, 6, 9, 12),
        intArrayOf(-2, -5, -8, -13, 1, 4, 7, 12),
        intArrayOf(-2, -4, -6, -13, 1, 3, 5, 12),
        intArrayOf(-3, -6, -8, -12, 2, 5, 7, 11),
        intArrayOf(-3, -7, -9, -11, 2, 6, 8, 10),
        intArrayOf(-4, -7, -8, -11, 3, 6, 7, 10),
        intArrayOf(-3, -5, -8, -11, 2, 4, 7, 10),
        intArrayOf(-2, -6, -8, -10, 1, 5, 7, 9),
        intArrayOf(-2, -5, -8, -10, 1, 4, 7, 9),
        intArrayOf(-2, -4, -8, -10, 1, 3, 7, 9),
        intArrayOf(-2, -5, -7, -10, 1, 4, 6, 9),
        intArrayOf(-3, -4, -7, -10, 2, 3, 6, 9),
        intArrayOf(-1, -2, -3, -10, 0, 1, 2, 9),
        intArrayOf(-4, -6, -8, -9, 3, 5, 7, 8),
        intArrayOf(-3, -5, -7, -9, 2, 4, 6, 8),
      )
    var bits = 0L
    for (k in 0 until 6) {
      bits = (bits shl 8) or (src[off + 2 + k].toLong() and 0xFFL)
    }
    val row = eacTable[tableIdx]
    for (px in 0 until 4) {
      for (py in 0 until 4) {
        val ix = startX + px
        val iy = startY + py
        if (ix >= width || iy >= height) continue
        val pIdx = px * 4 + py
        val code = ((bits ushr (45 - pIdx * 3)) and 0x7L).toInt()
        val alpha = clampByte(base + row[code] * mult)
        out[(iy * width + ix) * 4 + 3] = alpha.toByte()
      }
    }
  }

  private fun signExtend3(v: Int): Int = if ((v and 4) != 0) v - 8 else v

  private fun clampByte(v: Int): Int = v.coerceIn(0, 255)

  private fun rgb565ToRgba(c: Int): IntArray {
    val r = ((c ushr 11) and 0x1F) * 255 / 31
    val g = ((c ushr 5) and 0x3F) * 255 / 63
    val b = (c and 0x1F) * 255 / 31
    return intArrayOf(r, g, b, 255)
  }

  private fun writePngRgba(file: File, width: Int, height: Int, rgba: ByteArray) {
    val scanlines = ByteArray(height * (1 + width * 4))
    val rowBytes = width * 4
    for (y in 0 until height) {
      val dstRow = y * (1 + rowBytes)
      scanlines[dstRow] = 0 // Filter type 0 (None)
      System.arraycopy(rgba, y * rowBytes, scanlines, dstRow + 1, rowBytes)
    }

    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
    deflater.setInput(scanlines)
    deflater.finish()
    val idatStream = ByteArrayOutputStream(scanlines.size / 2 + 128)
    val temp = ByteArray(16_384)
    while (!deflater.finished()) {
      val count = deflater.deflate(temp)
      if (count > 0) idatStream.write(temp, 0, count)
    }
    deflater.end()

    val ihdr =
      ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN).apply {
        putInt(width)
        putInt(height)
        put(8.toByte()) // bit depth
        put(6.toByte()) // RGBA
        put(0.toByte()) // compression
        put(0.toByte()) // filter
        put(0.toByte()) // interlace
      }.array()

    FileOutputStream(file).use { fos ->
      fos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
      writePngChunk(fos, "IHDR", ihdr)
      writePngChunk(fos, "IDAT", idatStream.toByteArray())
      writePngChunk(fos, "IEND", ByteArray(0))
    }
  }

  private fun writePngChunk(fos: FileOutputStream, type: String, data: ByteArray) {
    val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array()
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    val crc = CRC32().apply {
      update(typeBytes)
      update(data)
    }.value
    val crcBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc.toInt()).array()
    fos.write(lenBuf)
    fos.write(typeBytes)
    fos.write(data)
    fos.write(crcBuf)
  }

  private fun writeTga32(file: File, width: Int, height: Int, rgba: ByteArray) {
    val header = ByteArray(18)
    header[2] = 2
    header[12] = (width and 0xFF).toByte()
    header[13] = ((width ushr 8) and 0xFF).toByte()
    header[14] = (height and 0xFF).toByte()
    header[15] = ((height ushr 8) and 0xFF).toByte()
    header[16] = 32
    header[17] = 0x28

    FileOutputStream(file).use { fos ->
      fos.write(header)
      val bgra = ByteArray(width * height * 4)
      for (i in 0 until width * height) {
        val off = i * 4
        bgra[off] = rgba[off + 2]
        bgra[off + 1] = rgba[off + 1]
        bgra[off + 2] = rgba[off]
        bgra[off + 3] = rgba[off + 3]
      }
      fos.write(bgra)
    }
  }
}
