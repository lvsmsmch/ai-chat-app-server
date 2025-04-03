package com.lvsmsmch.aichat.db.repositories._utils

data class Match(
    val startIndex: Int,
    val endIndex: Int
)

data class Matches(
    val matches: List<Match>
)

class SearchUtil {
    companion object {
        fun findAllMatches(text: String, searchTerm: String): List<Match> {
            val pattern = searchTerm.toRegex(RegexOption.IGNORE_CASE)
            val matches = mutableListOf<Match>()

            // Find all matches
            var matchResult = pattern.find(text)
            while (matchResult != null) {
                matches.add(Match(matchResult.range.first, matchResult.range.last + 1))
                matchResult = matchResult.next()
            }

            return matches
        }
    }
}