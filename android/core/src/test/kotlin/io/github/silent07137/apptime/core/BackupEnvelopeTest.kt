// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.core

import java.io.*
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class BackupEnvelopeTest {
    private val password = "测试备份口令🔐abcdefgh".toCharArray()
    private fun encrypted(): ByteArray {
        val file = File.createTempFile("backup-test", ".zip")
        return try {
            file.writeText("portable authenticated payload")
            ByteArrayOutputStream().also { BackupEnvelope.encrypt(file, it, password) }.toByteArray()
        } finally { file.delete() }
    }
    private fun rejects(bytes: ByteArray, pass: CharArray = password) {
        val file = File.createTempFile("backup-plain", ".zip")
        try {
            try { BackupEnvelope.decrypt(bytes.inputStream(), file, pass); fail("Corruption accepted") }
            catch (expected: Exception) { assertFalse(file.exists()) }
        } finally { file.delete() }
    }
    @Test fun roundTripUsesFreshSaltAndNonce() {
        val bytes = encrypted()
        assertFalse(bytes.contentEquals(encrypted()))
        val file = File.createTempFile("backup-plain", ".zip")
        try {
            BackupEnvelope.decrypt(bytes.inputStream(), file, password)
            assertEquals("portable authenticated payload", file.readText())
        } finally { file.delete() }
    }
    @Test fun wrongPasswordAndModifiedCiphertextNeverLeavePlaintext() {
        val bytes = encrypted()
        rejects(bytes, "wrong-password".toCharArray())
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        rejects(bytes)
    }
    @Test fun authenticatedHeaderAndBoundedKdfAreEnforced() {
        val bytes = encrypted()
        rejects(bytes.copyOf().also { it[18] = (it[18].toInt() xor 1).toByte() })
        rejects(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(14, Int.MAX_VALUE) })
        rejects(bytes.copyOf().also { it[9] = 2 })
        rejects(bytes.copyOf().also { ByteBuffer.wrap(it).putLong(46, Long.MAX_VALUE) })
    }
    @Test fun truncatedAndAppendedFilesAreRejected() {
        val bytes = encrypted()
        rejects(bytes.copyOf(bytes.size - 1))
        rejects(bytes + byteArrayOf(0))
    }
    @Test fun portableGoldenFixtureDecryptsByteForByte() {
        val dir = File(System.getProperty("apptime.fixtures"), "backup-v1")
        val plain = File.createTempFile("backup-golden", ".zip")
        try {
            File(dir, "golden.atbackup").inputStream().use { BackupEnvelope.decrypt(it, plain, "AppTime-备份-golden-123".toCharArray()) }
            assertArrayEquals(File(dir, "golden.zip").readBytes(), plain.readBytes())
        } finally { plain.delete() }
    }
}
