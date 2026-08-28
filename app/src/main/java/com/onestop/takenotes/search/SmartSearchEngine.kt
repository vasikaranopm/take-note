package com.onestop.takenotes.search

import com.onestop.takenotes.data.NoteEntity
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Modes for searching notes.
 */
enum class SearchMode(val displayName: String, val description: String) {
    SMART("Smart AI", "Combines AI semantic intent, fuzzy matching, and boolean logic"),
    SEMANTIC("AI Semantic", "Matches notes by meaning, intent, and conceptual similarity"),
    FUZZY("Fuzzy Typo-Tolerant", "Finds matches even with spelling mistakes or approximate keywords"),
    BOOLEAN("Logical / Boolean", "Strict AND, OR, NOT, category: filter, and exact phrases")
}

/**
 * Result item with relevance scoring and match explanation.
 */
data class SearchResult(
    val note: NoteEntity,
    val score: Float,
    val matchedSnippets: List<String> = emptyList(),
    val matchReasons: List<String> = emptyList()
)

/**
 * AI synthesized answer based on matching notes.
 */
data class AiSearchAnswer(
    val query: String,
    val answer: String,
    val sourceNoteIds: List<Long>,
    val confidence: Float
)

/**
 * Parsed query structure for logical searching.
 */
data class ParsedQuery(
    val rawQuery: String,
    val requiredTerms: List<String> = emptyList(),      // AND
    val optionalTerms: List<String> = emptyList(),      // OR
    val excludedTerms: List<String> = emptyList(),      // NOT / -
    val exactPhrases: List<String> = emptyList(),       // "..."
    val categoryFilters: List<String> = emptyList(),    // category:xxx
    val contentTypeFilter: String? = null               // type:url or type:text
)

object SmartSearchEngine {

    // Semantic category concept dictionary for local on-device intent expansion
    private val CONCEPT_SYNONYMS = mapOf(
        "buy" to listOf("shopping", "purchase", "store", "price", "order", "product", "cart"),
        "money" to listOf("finance", "bank", "budget", "invoice", "payment", "crypto", "salary", "expense"),
        "finance" to listOf("money", "bank", "budget", "investment", "tax", "stock", "dollar", "crypto"),
        "code" to listOf("tech", "github", "programming", "developer", "api", "git", "software", "bug", "kotlin"),
        "work" to listOf("project", "meeting", "task", "job", "deadline", "office", "client", "roadmap"),
        "eat" to listOf("food", "restaurant", "recipe", "dinner", "lunch", "breakfast", "cafe", "coffee", "meal"),
        "travel" to listOf("flight", "hotel", "trip", "vacation", "ticket", "tourism", "destination", "passport"),
        "health" to listOf("fitness", "doctor", "workout", "gym", "medicine", "wellness", "diet", "hospital"),
        "read" to listOf("book", "article", "paper", "blog", "learn", "study", "research", "guide"),
        "watch" to listOf("video", "movie", "youtube", "film", "stream", "show", "series", "entertainment")
    )

    /**
     * Parses search query for boolean operators (AND, OR, NOT), quotes ("..."), and filters (category:, type:).
     */
    fun parseQuery(query: String): ParsedQuery {
        var trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return ParsedQuery(query)
        }

        val exactPhrases = mutableListOf<String>()
        val quoteRegex = Regex("\"([^\"]+)\"")
        quoteRegex.findAll(trimmed).forEach { match ->
            val phrase = match.groupValues[1].trim()
            if (phrase.isNotEmpty()) {
                exactPhrases.add(phrase.lowercase(Locale.ROOT))
            }
        }
        trimmed = quoteRegex.replace(trimmed, " ")

        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }

        val required = mutableListOf<String>()
        val optional = mutableListOf<String>()
        val excluded = mutableListOf<String>()
        val categories = mutableListOf<String>()
        var contentType: String? = null

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val lower = token.lowercase(Locale.ROOT)

            when {
                lower.startsWith("cat:") || lower.startsWith("category:") -> {
                    val catVal = token.substringAfter(":")
                    if (catVal.isNotBlank()) categories.add(catVal.lowercase(Locale.ROOT))
                }
                lower.startsWith("type:") -> {
                    contentType = token.substringAfter(":").lowercase(Locale.ROOT)
                }
                lower.startsWith("-") && lower.length > 1 -> {
                    excluded.add(lower.substring(1))
                }
                lower == "not" && i + 1 < tokens.size -> {
                    excluded.add(tokens[i + 1].lowercase(Locale.ROOT))
                    i++
                }
                lower == "and" -> {
                    // Next term is required if present
                    if (i + 1 < tokens.size) {
                        required.add(tokens[i + 1].lowercase(Locale.ROOT))
                        i++
                    }
                }
                lower == "or" -> {
                    // Previous and next terms are optional
                    if (i + 1 < tokens.size) {
                        optional.add(tokens[i + 1].lowercase(Locale.ROOT))
                        i++
                    }
                }
                else -> {
                    optional.add(lower)
                }
            }
            i++
        }

        return ParsedQuery(
            rawQuery = query,
            requiredTerms = required,
            optionalTerms = optional,
            excludedTerms = excluded,
            exactPhrases = exactPhrases,
            categoryFilters = categories,
            contentTypeFilter = contentType
        )
    }

    /**
     * Executes intelligent search across the list of notes based on the chosen mode.
     */
    fun search(
        notes: List<NoteEntity>,
        query: String,
        mode: SearchMode = SearchMode.SMART,
        categoryFilter: String? = null
    ): List<SearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            val filtered = if (categoryFilter != null && categoryFilter != "All") {
                notes.filter { it.category.equals(categoryFilter, ignoreCase = true) }
            } else {
                notes
            }
            return filtered.map { SearchResult(it, score = 1.0f) }
        }

        val parsed = parseQuery(trimmed)

        val results = notes.mapNotNull { note ->
            if (categoryFilter != null && categoryFilter != "All") {
                if (!note.category.equals(categoryFilter, ignoreCase = true)) {
                    return@mapNotNull null
                }
            }

            evaluateNote(note, parsed, mode)
        }

        return results.sortedByDescending { it.score }
    }

    private fun evaluateNote(
        note: NoteEntity,
        parsed: ParsedQuery,
        mode: SearchMode
    ): SearchResult? {
        val title = note.title.lowercase(Locale.ROOT)
        val description = note.description.lowercase(Locale.ROOT)
        val content = note.contentData.lowercase(Locale.ROOT)
        val category = note.category.lowercase(Locale.ROOT)
        val combinedText = "$title $description $content $category"

        // 1. Content type filter
        if (parsed.contentTypeFilter != null) {
            val type = parsed.contentTypeFilter
            val isUrl = note.contentType.equals("URL", ignoreCase = true) || note.contentData.startsWith("http")
            if (type == "url" && !isUrl) return null
            if (type == "text" && isUrl) return null
        }

        // 2. Category filter
        if (parsed.categoryFilters.isNotEmpty()) {
            val matchesCategory = parsed.categoryFilters.any { catFilter ->
                category.contains(catFilter) || fuzzyTokenMatch(category, catFilter) >= 0.75f
            }
            if (!matchesCategory) return null
        }

        // 3. Excluded terms (NOT / -term)
        for (excluded in parsed.excludedTerms) {
            if (combinedText.contains(excluded)) {
                return null
            }
        }

        // 4. Exact phrases ("...")
        for (phrase in parsed.exactPhrases) {
            if (!combinedText.contains(phrase)) {
                return null
            }
        }

        // 5. Evaluate based on mode
        return when (mode) {
            SearchMode.BOOLEAN -> evaluateBoolean(note, parsed, title, description, content, category, combinedText)
            SearchMode.FUZZY -> evaluateFuzzy(note, parsed, title, description, content, category)
            SearchMode.SEMANTIC -> evaluateSemantic(note, parsed, title, description, content, category)
            SearchMode.SMART -> evaluateSmart(note, parsed, title, description, content, category, combinedText)
        }
    }

    private fun evaluateBoolean(
        note: NoteEntity,
        parsed: ParsedQuery,
        title: String,
        description: String,
        content: String,
        category: String,
        combinedText: String
    ): SearchResult? {
        // All required terms must match strictly
        for (req in parsed.requiredTerms) {
            if (!combinedText.contains(req)) return null
        }

        // At least one optional term must match if present (unless exact phrases exist)
        if (parsed.optionalTerms.isNotEmpty()) {
            val matchedAny = parsed.optionalTerms.any { combinedText.contains(it) }
            if (!matchedAny && parsed.exactPhrases.isEmpty()) return null
        }

        var score = 1.0f
        if (title.contains(parsed.rawQuery.lowercase(Locale.ROOT))) score += 2.0f
        if (category.contains(parsed.rawQuery.lowercase(Locale.ROOT))) score += 1.5f

        val snippets = extractSnippets(note, parsed.optionalTerms + parsed.requiredTerms + parsed.exactPhrases)
        return SearchResult(
            note = note,
            score = score,
            matchedSnippets = snippets,
            matchReasons = listOf("Boolean criteria matched")
        )
    }

    private fun evaluateFuzzy(
        note: NoteEntity,
        parsed: ParsedQuery,
        title: String,
        description: String,
        content: String,
        category: String
    ): SearchResult? {
        val queryTokens = (parsed.optionalTerms + parsed.requiredTerms).ifEmpty {
            parsed.rawQuery.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        }

        if (queryTokens.isEmpty() && parsed.exactPhrases.isNotEmpty()) {
            return SearchResult(note, score = 1.0f, matchReasons = listOf("Exact phrase match"))
        }

        val noteTokens = "$title $description $content $category".split(Regex("[\\s.,:;!?\n\r()\\[\\]{}'\"]+"))
            .filter { it.length >= 2 }

        var totalFuzzyScore = 0f
        var matchedTokensCount = 0
        val reasons = mutableListOf<String>()

        for (qToken in queryTokens) {
            var bestTokenScore = 0f
            var bestMatchWord = ""

            for (nToken in noteTokens) {
                val sim = fuzzyTokenMatch(nToken, qToken)
                if (sim > bestTokenScore) {
                    bestTokenScore = sim
                    bestMatchWord = nToken
                }
            }

            if (bestTokenScore >= 0.70f) {
                totalFuzzyScore += bestTokenScore
                matchedTokensCount++
                if (bestTokenScore >= 0.95f) {
                    reasons.add("Exact: '$qToken'")
                } else {
                    reasons.add("Fuzzy ($qToken ≈ $bestMatchWord)")
                }
            }
        }

        if (matchedTokensCount == 0) return null

        val finalScore = (totalFuzzyScore / queryTokens.size) * (matchedTokensCount.toFloat() / queryTokens.size)
        if (finalScore < 0.35f) return null

        val snippets = extractSnippets(note, queryTokens)
        return SearchResult(
            note = note,
            score = finalScore * 2.0f,
            matchedSnippets = snippets,
            matchReasons = reasons.distinct().take(3)
        )
    }

    private fun evaluateSemantic(
        note: NoteEntity,
        parsed: ParsedQuery,
        title: String,
        description: String,
        content: String,
        category: String
    ): SearchResult? {
        val queryText = parsed.rawQuery.lowercase(Locale.ROOT)
        val queryTokens = queryText.split(Regex("\\s+")).filter { it.isNotBlank() }

        var semanticScore = 0f
        val reasons = mutableListOf<String>()

        // 1. Direct Category Semantic Affinity
        val categoryAffinities = getCategoryAffinities(queryTokens)
        if (categoryAffinities.containsKey(category.lowercase(Locale.ROOT))) {
            val aff = categoryAffinities[category.lowercase(Locale.ROOT)] ?: 0f
            semanticScore += aff * 1.8f
            reasons.add("Category Intent: ${note.category}")
        }

        // 2. Synonym & Concept Expansion
        for (qToken in queryTokens) {
            val relatedConcepts = CONCEPT_SYNONYMS[qToken] ?: emptyList()
            for (concept in relatedConcepts) {
                if (title.contains(concept)) {
                    semanticScore += 1.2f
                    reasons.add("Concept: '$qToken' -> '$concept'")
                } else if (description.contains(concept) || content.contains(concept)) {
                    semanticScore += 0.8f
                    reasons.add("Concept in details: '$concept'")
                }
            }

            // Keyword in title/description
            if (title.contains(qToken)) {
                semanticScore += 1.5f
                reasons.add("Direct keyword in title")
            } else if (description.contains(qToken) || content.contains(qToken)) {
                semanticScore += 0.7f
                reasons.add("Keyword in content")
            }
        }

        if (semanticScore <= 0.4f) return null

        val snippets = extractSnippets(note, queryTokens)
        return SearchResult(
            note = note,
            score = semanticScore,
            matchedSnippets = snippets,
            matchReasons = reasons.distinct().take(3)
        )
    }

    private fun evaluateSmart(
        note: NoteEntity,
        parsed: ParsedQuery,
        title: String,
        description: String,
        content: String,
        category: String,
        combinedText: String
    ): SearchResult? {
        // Smart mode blends Boolean required matches, fuzzy typo matching, and semantic relevance
        for (req in parsed.requiredTerms) {
            if (!combinedText.contains(req) && fuzzyTokenMatchCombined(combinedText, req) < 0.75f) {
                return null
            }
        }

        var totalScore = 0f
        val reasons = mutableListOf<String>()

        val queryText = parsed.rawQuery.lowercase(Locale.ROOT)
        val queryTokens = queryText.split(Regex("\\s+")).filter { it.isNotBlank() }

        // Title full match boost
        if (title.contains(queryText)) {
            totalScore += 3.0f
            reasons.add("Title match")
        }

        // Category affinity
        val catAffinities = getCategoryAffinities(queryTokens)
        if (catAffinities.containsKey(category)) {
            val aff = catAffinities[category] ?: 0f
            totalScore += aff * 1.5f
            reasons.add("Category: ${note.category}")
        }

        // Token evaluation (exact + fuzzy + synonyms)
        for (qToken in queryTokens) {
            when {
                title.contains(qToken) -> {
                    totalScore += 1.8f
                    reasons.add("Found '$qToken'")
                }
                category.contains(qToken) -> {
                    totalScore += 1.4f
                    reasons.add("Category '$qToken'")
                }
                description.contains(qToken) || content.contains(qToken) -> {
                    totalScore += 1.0f
                    reasons.add("Found in text")
                }
                else -> {
                    // Try fuzzy token match
                    val bestFuzzy = fuzzyTokenMatchCombined(combinedText, qToken)
                    if (bestFuzzy >= 0.72f) {
                        totalScore += bestFuzzy * 0.9f
                        reasons.add("Fuzzy '$qToken'")
                    } else {
                        // Try concept expansion
                        val synonyms = CONCEPT_SYNONYMS[qToken] ?: emptyList()
                        val synMatch = synonyms.firstOrNull { combinedText.contains(it) }
                        if (synMatch != null) {
                            totalScore += 0.85f
                            reasons.add("Concept: '$qToken' -> '$synMatch'")
                        }
                    }
                }
            }
        }

        if (totalScore < 0.5f) return null

        val snippets = extractSnippets(note, queryTokens + parsed.exactPhrases)
        return SearchResult(
            note = note,
            score = totalScore,
            matchedSnippets = snippets,
            matchReasons = reasons.distinct().take(3)
        )
    }

    /**
     * Synthesizes an on-device AI answer summarizing the notes for a natural language question.
     */
    fun answerQuestionLocally(
        notes: List<NoteEntity>,
        question: String
    ): AiSearchAnswer? {
        val qTrimmed = question.trim()
        if (qTrimmed.length < 4 || !isLikelyQuestion(qTrimmed)) return null

        val searchResults = search(notes, qTrimmed, mode = SearchMode.SMART).take(3)
        if (searchResults.isEmpty()) return null

        val topResult = searchResults.first()
        val topNote = topResult.note

        val answerText = buildLocalAiAnswer(topNote, searchResults.map { it.note }, qTrimmed)
        return AiSearchAnswer(
            query = qTrimmed,
            answer = answerText,
            sourceNoteIds = searchResults.map { it.note.id },
            confidence = min(1.0f, topResult.score / 3.5f)
        )
    }

    private fun isLikelyQuestion(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        if (lower.endsWith("?")) return true
        val questionStarters = listOf(
            "what", "where", "how", "when", "who", "which", "why",
            "find", "show", "tell", "summarize", "list", "search"
        )
        return questionStarters.any { lower.startsWith(it) }
    }

    private fun buildLocalAiAnswer(
        topNote: NoteEntity,
        relevantNotes: List<NoteEntity>,
        question: String
    ): String {
        val cleanTitle = topNote.title.ifBlank { topNote.contentData.take(40) }
        val cleanDesc = topNote.description.ifBlank { topNote.contentData.take(120) }

        return if (relevantNotes.size == 1) {
            "Found in \"$cleanTitle\" [${topNote.category}]:\n$cleanDesc"
        } else {
            val otherTitles = relevantNotes.drop(1).joinToString(", ") { "\"${it.title.ifBlank { it.category }}\"" }
            "Most relevant: \"$cleanTitle\" (${topNote.category}) - $cleanDesc\nAlso related: $otherTitles"
        }
    }

    private fun extractSnippets(note: NoteEntity, targetWords: List<String>): List<String> {
        val fullText = "${note.title}\n${note.description}\n${note.contentData}"
        val lines = fullText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val snippets = mutableListOf<String>()

        for (line in lines) {
            val lowerLine = line.lowercase(Locale.ROOT)
            if (targetWords.any { lowerLine.contains(it.lowercase(Locale.ROOT)) }) {
                snippets.add(line.take(120))
                if (snippets.size >= 2) break
            }
        }
        return snippets
    }

    private fun getCategoryAffinities(queryTokens: List<String>): Map<String, Float> {
        val affinities = mutableMapOf<String, Float>()
        for (token in queryTokens) {
            when (token) {
                "tech", "code", "dev", "github", "api", "programming", "bug", "software", "android", "kotlin", "ai", "llm" -> {
                    affinities["tech"] = 1.0f
                }
                "finance", "money", "bank", "budget", "crypto", "investment", "invoice", "receipt", "dollar", "tax" -> {
                    affinities["finance"] = 1.0f
                }
                "work", "project", "meeting", "task", "job", "office", "client", "career", "interview", "resume" -> {
                    affinities["work"] = 1.0f
                }
                "food", "eat", "restaurant", "recipe", "dinner", "lunch", "breakfast", "coffee", "cooking", "meal" -> {
                    affinities["food"] = 1.0f
                }
                "travel", "trip", "hotel", "flight", "ticket", "vacation", "passport", "destination" -> {
                    affinities["travel"] = 1.0f
                }
                "health", "fitness", "workout", "gym", "diet", "doctor", "medicine", "wellness" -> {
                    affinities["health"] = 1.0f
                }
                "shopping", "buy", "store", "product", "amazon", "deal", "cart", "discount" -> {
                    affinities["shopping"] = 1.0f
                }
                "learning", "book", "course", "study", "research", "paper", "article", "tutorial", "guide" -> {
                    affinities["learning"] = 1.0f
                }
                "entertainment", "movie", "video", "youtube", "game", "music", "song", "series", "anime" -> {
                    affinities["entertainment"] = 1.0f
                }
            }
        }
        return affinities
    }

    /**
     * Computes similarity between two string tokens (0.0 to 1.0) using Damerau-Levenshtein distance.
     */
    fun fuzzyTokenMatch(s1: String, s2: String): Float {
        val t1 = s1.lowercase(Locale.ROOT)
        val t2 = s2.lowercase(Locale.ROOT)
        if (t1 == t2) return 1.0f
        if (t1.contains(t2) || t2.contains(t1)) {
            val minLen = min(t1.length, t2.length).toFloat()
            val maxLen = max(t1.length, t2.length).toFloat()
            return (minLen / maxLen) * 0.95f
        }

        val distance = damerauLevenshteinDistance(t1, t2)
        val maxLen = max(t1.length, t2.length)
        if (maxLen == 0) return 1.0f
        val similarity = 1.0f - (distance.toFloat() / maxLen.toFloat())
        return max(0f, similarity)
    }

    private fun fuzzyTokenMatchCombined(text: String, queryToken: String): Float {
        val words = text.split(Regex("[\\s.,:;!?\n\r()\\[\\]{}'\"]+")).filter { it.length >= 2 }
        var maxSim = 0f
        for (w in words) {
            val sim = fuzzyTokenMatch(w, queryToken)
            if (sim > maxSim) maxSim = sim
            if (maxSim >= 0.95f) break
        }
        return maxSim
    }

    private fun damerauLevenshteinDistance(source: String, target: String): Int {
        val srcLen = source.length
        val tgtLen = target.length
        if (srcLen == 0) return tgtLen
        if (tgtLen == 0) return srcLen

        val dp = Array(srcLen + 1) { IntArray(tgtLen + 1) }

        for (i in 0..srcLen) dp[i][0] = i
        for (j in 0..tgtLen) dp[0][j] = j

        for (i in 1..srcLen) {
            for (j in 1..tgtLen) {
                val cost = if (source[i - 1] == target[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,       // deletion
                    dp[i][j - 1] + 1,       // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )

                // Transposition
                if (i > 1 && j > 1 && source[i - 1] == target[j - 2] && source[i - 2] == target[j - 1]) {
                    dp[i][j] = minOf(dp[i][j], dp[i - 2][j - 2] + 1)
                }
            }
        }
        return dp[srcLen][tgtLen]
    }
}
