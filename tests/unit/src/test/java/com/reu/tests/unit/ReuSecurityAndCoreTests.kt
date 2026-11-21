package com.reu.tests.unit

import com.reu.core.security.IntegrityVerifier
import com.reu.core.security.PathTraversalGuard
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReuSecurityAndCoreTests {
  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun `path traversal guard blocks Zip-Slip and allows safe paths`() {
    val root = tempFolder.newFolder("sandbox")
    assertFalse(PathTraversalGuard.isPathSafe("../../etc/passwd"))
    assertFalse(PathTraversalGuard.isPathSafe("assets/../../evil.so"))
    assertFalse(PathTraversalGuard.isPathSafe("/absolute/path"))
    assertFalse(PathTraversalGuard.isPathSafe("bad\u0000name.txt"))
    assertTrue(PathTraversalGuard.isPathSafe("assets/bin/Data/globalgamemanagers"))

    val safe = PathTraversalGuard.resolveSafeTarget(root, "lib/arm64-v8a/libil2cpp.so")
    assertTrue(safe.canonicalPath.startsWith(root.canonicalPath))

    var threw = false
    try {
      PathTraversalGuard.resolveSafeTarget(root, "a/../../escape.txt")
    } catch (_: IllegalArgumentException) {
      threw = true
    } catch (_: SecurityException) {
      threw = true
    }
    assertTrue("Expected SecurityException/IllegalArgumentException on Zip-Slip", threw)
  }

  @Test
  fun `integrity verifier produces standard SHA-256 digest`() {
    val f = File(tempFolder.root, "sample.txt").apply { writeText("abc") }
    val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    assertEquals(expected, IntegrityVerifier.sha256(f))
    assertTrue(IntegrityVerifier.verifyChecksum(f, expected))
  }
}
