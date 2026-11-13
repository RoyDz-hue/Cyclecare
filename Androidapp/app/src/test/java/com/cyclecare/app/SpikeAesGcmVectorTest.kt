package com.cyclecare.app

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Spike S3(c): AES/GCM/NoPadding (12-byte IV prepended, 128-bit tag) parity
 * vectors. Phase A always runs: Kotlin encrypts synthetic vectors, written
 * line-based as keyB64:ivB64:ptB64:combinedB64(iv||ct||tag). Phase B: if the
 * Rust side has written its own vectors, decrypt each and assert equality.
 */
class SpikeAesGcmVectorTest {

    private val dir = File("build/spike").apply { mkdirs() }

    private fun decrypt(key: ByteArray, combined: ByteArray): ByteArray {
        val iv = combined.copyOfRange(0, 12)
        val ct = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    @Test
    fun writeKotlinVectorsAndVerifyRustVectorsIfPresent() {
        val random = SecureRandom()
        val b64 = Base64.getEncoder()
        val out = StringBuilder()
        val plaintexts = listOf(
            ByteArray(0),
            "cyclecare".toByteArray(Charsets.UTF_8),
            ByteArray(64) { 'x'.code.toByte() },
            byteArrayOf(0, 159.toByte(), 146.toByte(), 150.toByte()),
            ByteArray(1000) { 'q'.code.toByte() }
        )
        for (pt in plaintexts) {
            val key = ByteArray(32).also { random.nextBytes(it) }
            val iv = ByteArray(12).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val ct = cipher.doFinal(pt) // JCE appends the 128-bit tag
            val combined = iv + ct
            out.append(b64.encodeToString(key)).append(':')
                .append(b64.encodeToString(iv)).append(':')
                .append(b64.encodeToString(pt)).append(':')
                .append(b64.encodeToString(combined)).append('\n')
        }
        File(dir, "aesgcm-vectors-kotlin.txt").writeText(out.toString())
        println("AES_KOTLIN_VECTORS_WRITTEN ${plaintexts.size}")

        val rustFile = File(dir, "aesgcm-vectors-rust.txt")
        if (rustFile.exists()) {
            val decoder = Base64.getDecoder()
            var count = 0
            rustFile.readLines().filter { it.isNotBlank() }.forEach { line ->
                val f = line.split(":")
                check(f.size == 4) { "rust vector line needs 4 fields" }
                val pt = decrypt(decoder.decode(f[0]), decoder.decode(f[3]))
                assertArrayEquals("rust->kotlin plaintext mismatch", decoder.decode(f[2]), pt)
                count++
            }
            println("AES_RUST_TO_KOTLIN $count vectors OK")
        } else {
            println("AES_RUST_TO_KOTLIN skipped (no rust vectors yet)")
        }
    }
}
