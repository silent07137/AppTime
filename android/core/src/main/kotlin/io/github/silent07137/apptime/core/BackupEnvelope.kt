// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.core

import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable .atbackup v1 envelope; the entire fixed header is authenticated as AAD. */
object BackupEnvelope {
    const val HEADER_SIZE = 54
    const val ITERATIONS = 600_000
    const val MAX_CIPHERTEXT = 16 * 1024 * 1024L
    private val magic = byteArrayOf(65, 84, 66, 75, 13, 10, 26, 10)

    fun encrypt(zip: File, output: OutputStream, password: CharArray, random: SecureRandom = SecureRandom()) {
        val size = zip.length() + 16
        require(size in 16..MAX_CIPHERTEXT) { "档案超过备份大小限制" }
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_SIZE).put(magic).putShort(1)
            .put(1).put(1).put(1).put(0).putInt(ITERATIONS).put(salt).put(nonce).putLong(size).array()
        val cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, nonce, header)
        output.write(header)
        zip.inputStream().use { input -> transform(input, output, cipher, zip.length()) }
        output.flush()
    }

    /** Authenticated plaintext is staged privately; failure always removes it. */
    fun decrypt(input: InputStream, zip: File, password: CharArray) {
        try {
            val bytes = ByteArray(HEADER_SIZE)
            DataInputStream(input).readFully(bytes)
            val header = ByteBuffer.wrap(bytes)
            val actualMagic = ByteArray(8).also(header::get)
            require(actualMagic.contentEquals(magic)) { "不是 AppTime 备份文件" }
            require(header.short.toInt() == 1) { "暂不支持此备份版本" }
            require(header.get().toInt() == 1 && header.get().toInt() == 1 && header.get().toInt() == 1 && header.get().toInt() == 0) { "不支持的备份算法" }
            require(header.int == ITERATIONS) { "不支持的备份密钥参数" }
            val salt = ByteArray(16).also(header::get)
            val nonce = ByteArray(12).also(header::get)
            val length = header.long
            require(length in 16..MAX_CIPHERTEXT) { "备份文件大小无效" }
            val cipher = cipher(Cipher.DECRYPT_MODE, password, salt, nonce, bytes)
            zip.outputStream().use { transform(input, it, cipher, length) }
            require(input.read() == -1) { "备份文件含有额外数据" }
        } catch (problem: Exception) {
            zip.delete()
            throw problem
        }
    }

    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher {
        require(password.size in 8..256) { "口令需要 8–256 个字符" }
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad)
            }
        } finally { key.fill(0) }
    }

    private fun transform(input: InputStream, output: OutputStream, cipher: Cipher, length: Long) {
        val buffer = ByteArray(32 * 1024)
        var remaining = length
        while (remaining > 0) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("备份文件不完整")
            if (read == 0) continue
            cipher.update(buffer, 0, read)?.let(output::write)
            remaining -= read
        }
        output.write(cipher.doFinal())
    }
}
