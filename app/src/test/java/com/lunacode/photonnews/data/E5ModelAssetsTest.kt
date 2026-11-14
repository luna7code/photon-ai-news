package com.lunacode.photonnews.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class E5ModelAssetsTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("e5-model-integrity").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun acceptsExpectedSha256() {
        val file = writeFile("model.bin", "hello")
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            ModelFileIntegrity.sha256(file)
        )
        assertTrue(
            ModelFileIntegrity.matches(
                file,
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
            )
        )
    }

    @Test
    fun rejectsModifiedFile() {
        val file = writeFile("modified.bin", "hello!")
        assertFalse(
            ModelFileIntegrity.matches(
                file,
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
            )
        )
    }

    @Test
    fun rejectsEmptyFile() {
        val file = writeFile("empty.bin", "")
        assertFalse(
            ModelFileIntegrity.matches(
                file,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            )
        )
    }

    @Test
    fun rejectsTruncatedFile() {
        val file = writeFile("truncated.bin", "hell")
        assertFalse(
            ModelFileIntegrity.matches(
                file,
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
            )
        )
    }

    private fun writeFile(name: String, value: String): File =
        File(directory, name).apply { writeText(value) }
}
