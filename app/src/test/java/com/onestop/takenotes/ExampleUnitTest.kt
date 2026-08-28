package com.onestop.takenotes

import com.onestop.takenotes.extraction.MetadataExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

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
  }

  @Test
  fun testShortsYouTubeUrlExtraction() = runBlocking {
    val shortsUrl = "https://youtube.com/shorts/abc123XYZ89"
    val result = MetadataExtractor.extractFromText(shortsUrl)
    assertEquals("Link", result.contentType)
    assertEquals("YouTube", result.extraMetadata["platform"])
    assertEquals("abc123XYZ89", result.extraMetadata["video_id"])
  }
}

