package com.example.util

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

object HashUtils {

    /**
     * Calculates SHA-256 checksum for a given file in 8KB chunks.
     */
    fun calculateSha256(file: File): String {
        return try {
            if (!file.exists() || !file.canRead()) {
                return "Файл недоступен"
            }
            FileInputStream(file).use { calculateSha256(it) }
        } catch (e: Exception) {
            "Ошибка: ${e.message}"
        }
    }

    /**
     * Calculates SHA-256 checksum from an InputStream.
     */
    fun calculateSha256(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        val hashBytes = digest.digest()
        return bytesToHex(hashBytes)
    }

    /**
     * Calculates SHA-256 checksum from a ByteArray.
     */
    fun calculateSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return bytesToHex(hashBytes)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val result = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val i = byte.toInt()
            result.append(hexChars[(i shr 4) and 0x0F])
            result.append(hexChars[i and 0x0F])
        }
        return result.toString()
    }
}
