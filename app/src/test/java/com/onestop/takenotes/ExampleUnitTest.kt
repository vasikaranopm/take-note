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

  @Test
  fun testAiActionItemExtraction() {
    val note = com.onestop.takenotes.data.NoteEntity(
      id = 1,
      contentType = "Text",
      contentData = "Sprint Planning",
      title = "Sprint Planning",
      description = "TODO: Finish code review by Friday\n- [ ] Buy groceries tomorrow\nCall the client at 2pm",
      category = "Work"
    )

    val actionItems = com.onestop.takenotes.ai.AiFeaturesEngine.extractActionItems(note)
    assertTrue("Should extract action items", actionItems.isNotEmpty())
    assertTrue("Should contain review task", actionItems.any { it.text.contains("code review", ignoreCase = true) })
    assertTrue("Should contain buy task", actionItems.any { it.text.contains("groceries", ignoreCase = true) })
  }

  @Test
  fun testAiSummarization() {
    val note = com.onestop.takenotes.data.NoteEntity(
      id = 2,
      contentType = "Text",
      contentData = "Deep Learning Guide",
      title = "Deep Learning Guide",
      description = "Neural networks are computing systems inspired by the biological brain. They consist of input, hidden, and output layers. Backpropagation is used for training the model weights efficiently.",
      category = "Education"
    )

    val summary = com.onestop.takenotes.ai.AiFeaturesEngine.summarizeNote(note)
    assertNotNull(summary)
    assertTrue(summary.oneLiner.isNotBlank())
    assertTrue(summary.keyTakeaways.isNotEmpty())
  }

  @Test
  fun testAiAskNotes() {
    val notes = listOf(
      com.onestop.takenotes.data.NoteEntity(
        id = 1,
        contentType = "Text",
        contentData = "WiFi Secret",
        title = "Office WiFi Password",
        description = "The guest WiFi password is SecureGuest2026",
        category = "Personal"
      ),
      com.onestop.takenotes.data.NoteEntity(
        id = 2,
        contentType = "Link",
        contentData = "https://example.com/recipe",
        title = "Pancake Recipe",
        description = "Flour, eggs, milk, sugar, butter",
        category = "Personal"
      )
    )

    val answer = com.onestop.takenotes.ai.AiFeaturesEngine.askNotes("What is the wifi password?", notes)
    assertNotNull(answer)
    assertTrue("Answer should contain wifi password", answer!!.answer.contains("SecureGuest2026"))
  }
}


