package com.lunacode.photonnews.data

import java.io.File
import java.security.MessageDigest

/**
 * Small, platform-independent SHA-256 helper for downloaded model assets.
 *
 * Empty files have a well-defined SHA-256 digest, so [sha256] returns it;
 * [matches] deliberately rejects empty assets because they cannot be valid models.
 */
internal object ModelFileIntegrity {
    private const val BUFFER_SIZE = 64 * 1024
    private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")

    fun sha256(file: File): String {
        require(file.isFile) { "Model asset is not a regular file: ${file.name}" }

        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun matches(file: File, expectedSha256: String): Boolean {
        if (!file.isFile || file.length() <= 0L || !SHA256_PATTERN.matches(expectedSha256)) {
            return false
        }

        val expected = decodeHex(expectedSha256)
        val actual = decodeHex(sha256(file))
        return MessageDigest.isEqual(actual, expected)
    }

    private fun decodeHex(value: String): ByteArray =
        ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
}
