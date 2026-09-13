package com.pandorasbox.totalrecall

enum class TimeFilter {
    ALL,
    TODAY,
    THIS_WEEK,
    THIS_MONTH,
    LAST_MONTH,
    RECENT,
    OLD
}

enum class SortOrder {
    RELEVANCE,
    NEWEST,
    OLDEST
}

data class StructuredSearchQuery(
    val originalQuery: String,
    val semanticQuery: String,
    val timeFilter: TimeFilter = TimeFilter.ALL,
    val sortOrder: SortOrder = SortOrder.RELEVANCE
)

interface QueryUnderstandingEngine {
    suspend fun understand(query: String): StructuredSearchQuery
}

object LocalRuleBasedQueryUnderstanding : QueryUnderstandingEngine {
    override suspend fun understand(query: String): StructuredSearchQuery {
        val trimmed = query.trim()
        val lower = trimmed.lowercase()

        var timeFilter = TimeFilter.ALL
        var sortOrder = SortOrder.RELEVANCE
        var cleanedSemantic = trimmed

        when {
            lower.contains("today") -> {
                timeFilter = TimeFilter.TODAY
                sortOrder = SortOrder.NEWEST
                cleanedSemantic = removeKeywords(cleanedSemantic, listOf("today"))
            }
            lower.contains("yesterday") -> {
                timeFilter = TimeFilter.THIS_WEEK
                sortOrder = SortOrder.NEWEST
                cleanedSemantic = removeKeywords(cleanedSemantic, listOf("yesterday"))
            }
            lower.contains("recent") || lower.contains("latest") || lower.contains("newest") -> {
                timeFilter = TimeFilter.RECENT
                sortOrder = SortOrder.NEWEST
                cleanedSemantic = removeKeywords(cleanedSemantic, listOf("recent", "latest", "newest"))
            }
            lower.contains("oldest") -> {
                timeFilter = TimeFilter.OLD
                sortOrder = SortOrder.OLDEST
                cleanedSemantic = removeKeywords(cleanedSemantic, listOf("oldest"))
            }
            lower.contains("old") -> {
                timeFilter = TimeFilter.OLD
                sortOrder = SortOrder.OLDEST
                cleanedSemantic = removeKeywords(cleanedSemantic, listOf("old"))
            }
            lower.contains("this week") -> {
                timeFilter = TimeFilter.THIS_WEEK
                sortOrder = SortOrder.NEWEST
                cleanedSemantic = removeKeywords(cleanedSemantic, listOf("this week"))
            }
            lower.contains("this month") -> {
                timeFilter = TimeFilter.THIS_MONTH
                sortOrder = SortOrder.NEWEST
                cleanedSemantic = removeKeywords(cleanedSemantic, listOf("this month"))
            }
            lower.contains("last month") -> {
                timeFilter = TimeFilter.LAST_MONTH
                cleanedSemantic = removeKeywords(cleanedSemantic, listOf("last month"))
            }
        }

        cleanedSemantic = cleanedSemantic.replace(
            Regex("\\b(photos|pictures|pics|images|photo|picture|pic|image|from|my|of|show me)\\b", RegexOption.IGNORE_CASE),
            ""
        ).trim()

        if (cleanedSemantic.isBlank()) {
            cleanedSemantic = trimmed
        }

        return StructuredSearchQuery(
            originalQuery = trimmed,
            semanticQuery = cleanedSemantic,
            timeFilter = timeFilter,
            sortOrder = sortOrder
        )
    }

    private fun removeKeywords(text: String, keywords: List<String>): String {
        var result = text
        for (kw in keywords) {
            result = result.replace(Regex("\\b$kw\\b", RegexOption.IGNORE_CASE), "")
        }
        return result.trim()
    }
}
