package com.onestop.takenotes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.onestop.takenotes.R
import com.onestop.takenotes.ai.SmolLM2Classifier
import com.onestop.takenotes.extraction.MetadataExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("TakeNotes", appName)
  }

  @Test
  fun `test heuristic categorization`() {
    val workCategory = SmolLM2Classifier.heuristicCategorize(
      "Sprint planning and Jira backlog items",
      "Meeting with engineering team at 2pm"
    )
    assertEquals("Work", workCategory)

    val shoppingCategory = SmolLM2Classifier.heuristicCategorize(
      "Amazon deal on headphones",
      "Special discount and free shipping on electronics order"
    )
    assertEquals("Shopping", shoppingCategory)
  }

  @Test
  fun `test plain text extraction`() = runBlocking {
    val result = MetadataExtractor.extractFromText("Quick shopping reminder for milk and eggs")
    assertNotNull(result)
    assertEquals("Text", result.contentType)
  }
}
