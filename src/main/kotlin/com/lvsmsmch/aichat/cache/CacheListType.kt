package com.lvsmsmch.aichat.cache

import com.lvsmsmch.aichat.character.database.CharacterCategory
import com.lvsmsmch.aichat.utils.generateHash


sealed class CacheListType {
    data object Personalized : CacheListType()
    data class Category(val category: CharacterCategory) : CacheListType()
    data class Search(val searchQuery: String, val sortCriteria: Int) : CacheListType()

    val code: String
        get() = when (this) {
            is Personalized -> "personalized"
            is Category -> "category_${category.code}"
            is Search -> "search_${generateHash(12, searchQuery.trim().lowercase(), sortCriteria.toString())}"
        }
}