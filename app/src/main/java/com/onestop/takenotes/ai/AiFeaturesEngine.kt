package com.onestop.takenotes.ai

import com.onestop.takenotes.data.NoteEntity
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

data class ActionItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val dueDate: String? = null,
    val priority: String = "Normal",
    val isCompleted: Boolean = false
)

data class NoteSummary(
    val oneLiner: String,
    val keyTakeaways: List<String>,
    val detectedTopic: String
)

data class RelatedNoteMatch(
    val note: NoteEntity,
    val similarityScore: Float,
    val connectionReason: String
)

data class AiDeepAnswer(
    val query: String,
    val answer: String,
    val keyPoints: List<String> = emptyList(),
    val sourceNotes: List<NoteEntity> = emptyList(),
    val confidence: Float = 0.9f
)

object AiFeaturesEngine {

    private val STOP_WORDS = setOf(
        "a", "about", "above", "after", "again", "against", "all", "am", "an", "and",
        "any", "are", "aren't", "as", "at", "be", "because", "been", "before", "being",
        "below", "between", "both", "but", "by", "can't", "cannot", "could", "did",
        "do", "does", "doing", "don't", "down", "during", "each", "few", "for", "from",
        "further", "had", "has", "have", "having", "he", "her", "here", "hers", "herself",
        "him", "himself", "his", "how", "i", "if", "in", "into", "is", "isn't", "it",
        "its", "itself", "let's", "me", "more", "most", "my", "myself", "no", "nor",
        "not", "of", "off", "on", "once", "only", "or", "other", "ought", "our", "ours",
        "ourselves", "out", "over", "own", "same", "she", "should", "so", "some", "such",
        "than", "that", "the", "their", "theirs", "them", "themselves", "then", "there",
        "these", "they", "this", "those", "through", "to", "too", "under", "until", "up",
        "very", "was", "wasn't", "we", "were", "what", "when", "where", "which", "while",
        "who", "whom", "why", "with", "won't", "would", "you", "your", "yours", "yourself",
        "yourselves", "http", "https", "com", "org", "net", "www"
    )

    private val ACTION_VERBS = listOf(
        "buy", "purchase", "order", "call", "phone", "email", "contact", "text", "message",
        "finish", "complete", "submit", "send", "review", "check", "fix", "update", "write",
        "schedule", "book", "reserve", "meet", "discuss", "prepare", "organize", "clean",
        "pay", "transfer", "sign", "install", "download", "read", "watch", "learn", "study",
        "remind", "remember", "ask", "follow up", "verify", "test", "deploy"
    )

    private val TIME_PATTERNS = listOf(
        Regex("(?i)\\b(today|tomorrow|yesterday|tonight)\\b"),
        Regex("(?i)\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b"),
        Regex("(?i)\\b(at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?)\\b"),
        Regex("(?i)\\b(by\\s+[a-zA-Z0-9:\\s]+)\\b"),
        Regex("(?i)\\b(in\\s+\\d+\\s+(?:days?|weeks?|hours?|mins?|minutes?))\\b"),
        Regex("(?i)\\b(next\\s+(?:week|month|monday|friday|sprint))\\b")
    )

    /**
     * Extracts actionable tasks and to-do items from a note's text content.
     */
    fun extractActionItems(note: NoteEntity): List<ActionItem> {
        val combinedText = "${note.title}\n${note.description}\n${note.contentData}"
        val lines = combinedText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val actionItems = mutableListOf<ActionItem>()

        for (line in lines) {
            val cleanLine = line.replace(Regex("^[-*•\\d.)\\s\\[\\]]+"), "").trim()
            if (cleanLine.length < 3) continue

            // Pattern 1: Checkbox markdown [ ] or [x]
            if (line.contains("[ ]") || line.contains("[x]") || line.startsWith("- [") || line.startsWith("* [")) {
                val isDone = line.contains("[x]", ignoreCase = true)
                val taskText = cleanLine.replace(Regex("^\\[[ xX]\\]\\s*"), "")
                val dueDate = extractDueDate(taskText)
                val priority = if (taskText.contains("urgent", ignoreCase = true) || taskText.contains("asap", ignoreCase = true)) "High" else "Normal"
                actionItems.add(ActionItem(text = taskText, dueDate = dueDate, priority = priority, isCompleted = isDone))
                continue
            }

            // Pattern 2: Explicit prefix markers (TODO:, Action:, Next steps:, Task:)
            val markerRegex = Regex("(?i)^(todo|action item|action|task|next step|follow-up|reminder):\\s*(.*)")
            val markerMatch = markerRegex.find(cleanLine)
            if (markerMatch != null) {
                val taskText = markerMatch.groupValues[2].ifBlank { cleanLine }
                val dueDate = extractDueDate(taskText)
                actionItems.add(ActionItem(text = taskText, dueDate = dueDate, priority = "High"))
                continue
            }

            // Pattern 3: Sentences starting with Action Verbs or bullet items with action verbs
            val firstWord = cleanLine.split(Regex("\\s+")).firstOrNull()?.lowercase(Locale.ROOT) ?: ""
            if (ACTION_VERBS.contains(firstWord) || ACTION_VERBS.any { cleanLine.lowercase(Locale.ROOT).startsWith(it) }) {
                val dueDate = extractDueDate(cleanLine)
                val priority = if (cleanLine.contains("important", ignoreCase = true) || cleanLine.contains("deadline", ignoreCase = true)) "High" else "Normal"
                actionItems.add(ActionItem(text = cleanLine, dueDate = dueDate, priority = priority))
                continue
            }

            // Pattern 4: Contains "need to", "have to", "remember to", "must"
            val obligationRegex = Regex("(?i)\\b(need to|have to|must|remember to|don't forget to|make sure to)\\s+(.+)")
            val oblMatch = obligationRegex.find(cleanLine)
            if (oblMatch != null) {
                val taskText = oblMatch.value
                val dueDate = extractDueDate(taskText)
                actionItems.add(ActionItem(text = taskText, dueDate = dueDate, priority = "Normal"))
                continue
            }
        }

        // De-duplicate action items
        return actionItems.distinctBy { it.text.lowercase(Locale.ROOT) }.take(8)
    }

    private fun extractDueDate(text: String): String? {
        for (pattern in TIME_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                return match.value.trim()
            }
        }
        return null
    }

    /**
     * Generates a concise AI summary and key takeaways for a note.
     */
    fun summarizeNote(note: NoteEntity): NoteSummary {
        val title = note.title.trim()
        val desc = note.description.trim()
        val category = note.category

        val fullText = buildString {
            if (title.isNotBlank()) append(title).append("\n\n")
            if (desc.isNotBlank()) append(desc)
            if (note.contentType == "Link" && note.contentData.isNotBlank() && !desc.contains(note.contentData)) {
                append("\nURL: ").append(note.contentData)
            }
        }.trim()

        val sentences = fullText.split(Regex("(?<=[.!?\\n])\\s+"))
            .map { it.trim().replace(Regex("^[•\\-*\\d.)\\s]+"), "") }
            .filter { it.length > 15 }

        val takeaways = mutableListOf<String>()

        if (sentences.isNotEmpty()) {
            for (s in sentences) {
                if (takeaways.size >= 4) break
                // Favor sentences with informative content
                if (s.length > 20 && !takeaways.any { it.contains(s) || s.contains(it) }) {
                    takeaways.add(s)
                }
            }
        }

        if (takeaways.isEmpty()) {
            if (title.isNotBlank()) takeaways.add(title)
            if (desc.isNotBlank()) takeaways.add(desc)
        }

        val oneLiner = if (sentences.isNotEmpty()) {
            sentences.first().take(140)
        } else if (title.isNotBlank()) {
            title
        } else {
            "Saved $category item (${note.contentType})"
        }

        val topic = detectTopic(fullText, category)

        return NoteSummary(
            oneLiner = oneLiner,
            keyTakeaways = takeaways.take(4),
            detectedTopic = topic
        )
    }

    /**
     * Finds related notes based on semantic topic keywords, categories, and entity overlap.
     */
    fun findRelatedNotes(currentNote: NoteEntity, allNotes: List<NoteEntity>, limit: Int = 3): List<RelatedNoteMatch> {
        val currentTokens = extractSignificanceTokens("${currentNote.title} ${currentNote.description} ${currentNote.category}")
        if (currentTokens.isEmpty()) return emptyList()

        val matches = mutableListOf<RelatedNoteMatch>()

        for (other in allNotes) {
            if (other.id == currentNote.id) continue

            val otherTokens = extractSignificanceTokens("${other.title} ${other.description} ${other.category}")
            val sharedTokens = currentTokens.intersect(otherTokens)

            var score = 0.0f
            val reasons = mutableListOf<String>()

            // Shared category boost
            if (other.category.equals(currentNote.category, ignoreCase = true)) {
                score += 1.0f
                reasons.add("Same Category (${other.category})")
            }

            // Shared key tokens
            if (sharedTokens.isNotEmpty()) {
                val tokenScore = sharedTokens.size * 0.8f
                score += tokenScore
                val highlightTokens = sharedTokens.take(2).joinToString(", ")
                reasons.add("Shared topic: $highlightTokens")
            }

            // Same content type with high token affinity
            if (other.contentType == currentNote.contentType && sharedTokens.isNotEmpty()) {
                score += 0.5f
            }

            if (score >= 1.2f) {
                matches.add(
                    RelatedNoteMatch(
                        note = other,
                        similarityScore = score,
                        connectionReason = reasons.distinct().joinToString(" • ")
                    )
                )
            }
        }

        return matches.sortedByDescending { it.similarityScore }.take(limit)
    }

    /**
     * Synthesizes a deep AI answer across all notes for a natural language question.
     */
    fun askNotes(query: String, allNotes: List<NoteEntity>): AiDeepAnswer? {
        val q = query.trim()
        if (q.length < 3) return null

        val qTokens = extractSignificanceTokens(q)
        if (qTokens.isEmpty()) return null

        // Score notes against the question
        val scoredNotes = allNotes.mapNotNull { note ->
            val noteTokens = extractSignificanceTokens("${note.title} ${note.description} ${note.category}")
            val overlap = qTokens.intersect(noteTokens)
            if (overlap.isEmpty()) null
            else {
                val score = overlap.size * 1.5f + (if (note.title.contains(q, ignoreCase = true)) 3.0f else 0f)
                Pair(note, score)
            }
        }.sortedByDescending { it.second }

        if (scoredNotes.isEmpty()) return null

        val topMatch = scoredNotes.first().first
        val topNotes = scoredNotes.take(3).map { it.first }

        // Formulate an intelligent synthesis
        val answerBuilder = StringBuilder()
        val keyPoints = mutableListOf<String>()

        val cleanTitle = topMatch.title.ifBlank { "Untitled Note" }
        val cleanDesc = topMatch.description.ifBlank { topMatch.contentData }

        if (q.contains("how many", ignoreCase = true) || q.contains("count", ignoreCase = true)) {
            val count = scoredNotes.size
            answerBuilder.append("Found $count related note${if (count == 1) "" else "s"} matching \"$q\".")
            keyPoints.addAll(topNotes.map { "• \"${it.title.ifBlank { it.category }}\" [${it.category}]" })
        } else if (q.contains("wifi", ignoreCase = true) || q.contains("password", ignoreCase = true) || q.contains("code", ignoreCase = true)) {
            answerBuilder.append("Here is the matching information from \"$cleanTitle\":\n$cleanDesc")
            keyPoints.add("Category: ${topMatch.category}")
        } else if (q.contains("summarize", ignoreCase = true) || q.contains("summary", ignoreCase = true)) {
            val summary = summarizeNote(topMatch)
            answerBuilder.append("Summary of \"$cleanTitle\":\n${summary.oneLiner}")
            keyPoints.addAll(summary.keyTakeaways)
        } else {
            answerBuilder.append("Based on \"$cleanTitle\" [${topMatch.category}]:\n$cleanDesc")
            if (topNotes.size > 1) {
                val others = topNotes.drop(1).map { "\"${it.title.ifBlank { it.category }}\"" }
                keyPoints.add("Also related: ${others.joinToString(", ")}")
            }
        }

        return AiDeepAnswer(
            query = q,
            answer = answerBuilder.toString(),
            keyPoints = keyPoints,
            sourceNotes = topNotes,
            confidence = min(1.0f, scoredNotes.first().second / 3.0f)
        )
    }

    private fun detectTopic(text: String, category: String): String {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("kotlin") || lower.contains("android") || lower.contains("code") -> "Android Development"
            lower.contains("recipe") || lower.contains("cook") || lower.contains("food") -> "Food & Cooking"
            lower.contains("meeting") || lower.contains("sprint") || lower.contains("jira") -> "Project Management"
            lower.contains("workout") || lower.contains("gym") || lower.contains("fitness") -> "Health & Fitness"
            lower.contains("travel") || lower.contains("flight") || lower.contains("hotel") -> "Travel Itinerary"
            lower.contains("buy") || lower.contains("shopping") || lower.contains("price") -> "Shopping & Deals"
            else -> category
        }
    }

    private fun extractSignificanceTokens(text: String): Set<String> {
        return text.lowercase(Locale.ROOT)
            .split(Regex("[\\s.,:;!?\n\r()\\[\\]{}'\"/\\\\#@=~`<>]+"))
            .filter { it.length >= 3 && !STOP_WORDS.contains(it) }
            .toSet()
    }
}
