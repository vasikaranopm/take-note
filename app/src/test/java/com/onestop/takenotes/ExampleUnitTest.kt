package com.onestop.takenotes

import com.onestop.takenotes.extraction.MetadataExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testExtractFromPlainText() = runBlocking {
    val text = "Meeting Notes\nDiscuss project roadmap and deadline with team"
    val result = MetadataExtractor.extractFromText(text)
    assertEquals("Text", result.contentType)
    assertEquals("Meeting Notes", result.title)
    assertTrue(result.description.contains("Discuss project roadmap"))
  }

  @Test
  fun testYouTubeUrlExtraction() = runBlocking {
    val ytUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    val result = MetadataExtractor.extractFromText(ytUrl)
    assertEquals("Link", result.contentType)
    assertEquals("YouTube", result.extraMetadata["platform"])
    assertEquals("dQw4w9WgXcQ", result.extraMetadata["video_id"])
    assertNotNull(result.imageUrl)
    assertTrue(result.imageUrl!!.contains("dQw4w9WgXcQ"))
    assertTrue(result.description.isNotBlank())
  }

  @Test
  fun testShortsYouTubeUrlExtraction() = runBlocking {
    val shortsUrl = "https://youtube.com/shorts/abc123XYZ89"
    val result = MetadataExtractor.extractFromText(shortsUrl)
    assertEquals("Link", result.contentType)
    assertEquals("YouTube", result.extraMetadata["platform"])
    assertEquals("abc123XYZ89", result.extraMetadata["video_id"])
  }

  @Test
  fun testBackupJsonCreation() {
    val notes = listOf(
      com.onestop.takenotes.data.NoteEntity(
        id = 1,
        contentType = "Link",
        contentData = "https://kotlinlang.org",
        title = "Kotlin Language",
        description = "Modern programming language for Android",
        category = "Work",
        timestamp = 1700000000000L
      )
    )
    val categories = listOf(
      com.onestop.takenotes.data.CategoryEntity(
        id = 1,
        name = "Work",
        colorHex = "#005AC1",
        isDefault = true,
        orderIndex = 0
      )
    )

    val jsonString = com.onestop.takenotes.backup.BackupRestoreManager.createBackupJson(notes, categories)
    assertNotNull(jsonString)
    assertTrue(jsonString.contains("TakeNotes"))
    assertTrue(jsonString.contains("Kotlin Language"))
    assertTrue(jsonString.contains("Work"))

    val json = org.json.JSONObject(jsonString)
    assertEquals(1, json.getInt("totalNotes"))
    assertEquals(1, json.getInt("totalCategories"))
    assertEquals("TakeNotes", json.getString("appName"))
  }
}

