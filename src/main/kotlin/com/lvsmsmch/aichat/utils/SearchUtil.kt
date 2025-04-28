package com.lvsmsmch.aichat.utils

import kotlinx.serialization.Serializable

@Serializable
data class MatchPosition(
    val startIndex: Int,
    val endIndex: Int
)

@Serializable
data class MatchPositions(
    val positions: List<MatchPosition>
)

class SearchUtil {
    companion object {
        fun findAllMatches(text: String, searchTerm: String): MatchPositions {
            val pattern = searchTerm.toRegex(RegexOption.IGNORE_CASE)
            val matchPositions = mutableListOf<MatchPosition>()

            // Find all matches
            var matchResult = pattern.find(text)
            while (matchResult != null) {
                matchPositions.add(MatchPosition(matchResult.range.first, matchResult.range.last + 1))
                matchResult = matchResult.next()
            }

            return MatchPositions(matchPositions)
        }
    }
}