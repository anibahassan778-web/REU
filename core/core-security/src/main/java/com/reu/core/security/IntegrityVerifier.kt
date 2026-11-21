package com.reu.core.security

import com.reu.core.common.FormatUtils
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object IntegrityVerifier {
  fun sha256(file: File, bufferSizeBytes: Int = 65_536): String {
    file.inputStream().buffered(bufferSizeBytes).use { input ->
      return sha256(input, bufferSizeBytes)
    }
  }

  fun sha256(inputStream: InputStream, bufferSizeBytes: Int = 65_536): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(bufferSizeBytes.coerceIn(4096, 1024 * 1024))
    var read = inputStream.read(buffer)
    while (read > 0) {
      digest.update(buffer, 0, read)
      read = inputStream.read(buffer)
    }
    return FormatUtils.toHexString(digest.digest())
  }

  fun sha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return FormatUtils.toHexString(digest)
  }

  fun verifyChecksum(file: File, expectedSha256: String): Boolean {
    if (!file.exists()) return false
    val actual = sha256(file)
    return actual.equals(expectedSha256.trim(), ignoreCase = true)
  }
}
