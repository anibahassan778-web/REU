package com.reu.engine.texture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
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
  /**
   * Decodes raw RGBA32 / RGB24 / Alpha8 / encoded PNG-JPG image bytes or DXT1 blocks into an
   * Android Bitmap and exports to PNG, JPG, or TGA.
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

    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val outFile =
      when (format) {
        TextureExportFormat.PNG -> {
          File(outputDir, "$safeName.png").also { f ->
            FileOutputStream(f).use { fos -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos) }
          }
        }
        TextureExportFormat.JPG -> {
          File(outputDir, "$safeName.jpg").also { f ->
            FileOutputStream(f).use { fos -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos) }
          }
        }
        TextureExportFormat.TGA -> {
          File(outputDir, "$safeName.tga").also { f -> writeTga32(f, width, height, rgbaPixels) }
        }
      }
    bitmap.recycle()

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
        val c0 = (dxt1Bytes[offset].toInt() and 0xFF) or ((dxt1Bytes[offset + 1].toInt() and 0xFF) shl 8)
        val c1 = (dxt1Bytes[offset + 2].toInt() and 0xFF) or ((dxt1Bytes[offset + 3].toInt() and 0xFF) shl 8)
        val bits =
          (dxt1Bytes[offset + 4].toInt() and 0xFF) or
            ((dxt1Bytes[offset + 5].toInt() and 0xFF) shl 8) or
            ((dxt1Bytes[offset + 6].toInt() and 0xFF) shl 16) or
            ((dxt1Bytes[offset + 7].toInt() and 0xFF) shl 24)
        offset += 8

        val palette = Array(4) { IntArray(4) }
        palette[0] = rgb565ToRgba(c0)
        palette[1] = rgb565ToRgba(c1)
        if (c0 > c1) {
          for (ch in 0..2) {
            palette[2][ch] = (2 * palette[0][ch] + palette[1][ch]) / 3
            palette[3][ch] = (palette[0][ch] + 2 * palette[1][ch]) / 3
          }
          palette[2][3] = 255; palette[3][3] = 255
        } else {
          for (ch in 0..2) {
            palette[2][ch] = (palette[0][ch] + palette[1][ch]) / 2
            palette[3][ch] = 0
          }
          palette[2][3] = 255; palette[3][3] = 0
        }

        for (py in 0 until 4) {
          for (px in 0 until 4) {
            val ix = bx * 4 + px
            val iy = by * 4 + py
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
    }
    return out
  }

  private fun rgb565ToRgba(c: Int): IntArray {
    val r = ((c ushr 11) and 0x1F) * 255 / 31
    val g = ((c ushr 5) and 0x3F) * 255 / 63
    val b = (c and 0x1F) * 255 / 31
    return intArrayOf(r, g, b, 255)
  }

  private fun writeTga32(file: File, width: Int, height: Int, rgba: ByteArray) {
    val header = ByteArray(18)
    header[2] = 2 // Uncompressed true-color image
    header[12] = (width and 0xFF).toByte()
    header[13] = ((width ushr 8) and 0xFF).toByte()
    header[14] = (height and 0xFF).toByte()
    header[15] = ((height ushr 8) and 0xFF).toByte()
    header[16] = 32 // 32 bpp
    header[17] = 0x28 // top-left origin + 8 alpha bits

    FileOutputStream(file).use { fos ->
      fos.write(header)
      val bgra = ByteArray(width * height * 4)
      for (i in 0 until width * height) {
        val off = i * 4
        bgra[off] = rgba[off + 2]     // B
        bgra[off + 1] = rgba[off + 1] // G
        bgra[off + 2] = rgba[off]     // R
        bgra[off + 3] = rgba[off + 3] // A
      }
      fos.write(bgra)
    }
  }
}
