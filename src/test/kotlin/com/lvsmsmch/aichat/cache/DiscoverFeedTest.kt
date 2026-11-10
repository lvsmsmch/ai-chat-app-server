package com.lvsmsmch.aichat.cache

import com.lvsmsmch.aichat.cache.database.CharacterListCopyDbo
import com.lvsmsmch.aichat.cache.database.CharacterListCopyRepository
import com.lvsmsmch.aichat.character.database.CharacterDbo
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.character.database.CharacterVisibility
import com.lvsmsmch.aichat.utils.UtcTimestamp
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoverFeedTest {
    private val copies = mutableMapOf<String, CharacterListCopyDbo>()
    private val characters = mutableMapOf<String, CharacterDbo>()
    private val copyRepository = mockk<CharacterListCopyRepository>(relaxed = true)
    private val characterRepository = mockk<CharacterRepository>()
    private val manager = CacheManager(copyRepository, mockk(), mockk(), mockk(), characterRepository)

    init {
        coEvery { copyRepository.getExistingCopy("user", "device", any()) } answers { copies[thirdArg()] }
        coEvery { copyRepository.upsert(any()) } answers {
            val copy = firstArg<CharacterListCopyDbo>()
            copies[copy.listType] = copy
        }
        coEvery { copyRepository.deleteAllSearchCopiesForUserDevice("user", "device") } answers {
            copies.keys.removeAll { it.startsWith("search_") }
        }
        coEvery { characterRepository.getCharacter(any()) } answers { characters[firstArg()] }
    }

    @Test
    fun `a search and a recommended feed refresh preserve the next page of new arrivals`() = runBlocking {
        val now = UtcTimestamp.now()
        val newest = character("newest")
        val previous = character("previous", now.subtractDays(2))
        val recommended = character("recommended", now.subtractDays(60))

        coEvery { characterRepository.getDiscoverFeedIds(any(), emptySet(), 1000) } answers {
            val cutoff = firstArg<UtcTimestamp?>()
            if (cutoff == null) {
                listOf(recommended.id)
            } else {
                val cutoffAge = now.epochMillis - cutoff.epochMillis
                assertTrue(cutoffAge in (7 * 86_400_000L - 10_000)..(7 * 86_400_000L + 10_000))
                listOf(newest.id, previous.id)
            }
        }
        coEvery {
            characterRepository.getCharacters(
                searchQuery = "query", sortCriteria = 4, page = 1, size = 1000,
                visibilityFilter = CharacterVisibility.PUBLIC.code,
            )
        } returns listOf(recommended)

        val firstPage = feed(DiscoverFeed.NewArrivals)
        assertEquals(listOf(newest), firstPage.items)
        assertTrue(firstPage.refreshed)
        assertEquals(listOf(recommended), feed(DiscoverFeed.MoreToDiscover).items)
        manager.getItems("user", "device", CacheListType.Search("query", 4), 1, 0)
        assertEquals(listOf(recommended), feed(DiscoverFeed.MoreToDiscover, refresh = true).items)

        val secondPage = feed(DiscoverFeed.NewArrivals, cursor = assertNotNull(firstPage.nextCursor))
        assertEquals(listOf(previous), secondPage.items)
        assertFalse(secondPage.refreshed)
        assertNull(secondPage.nextCursor)
    }

    @Test
    fun `an existing new arrivals snapshot skips blocked private and aged out characters`() = runBlocking {
        val newest = character("newest")
        val agedOut = character("aged-out", UtcTimestamp.now().subtractDays(8))
        val blocked = character("blocked", authorId = "blocked-author")
        val private = character("private", visibility = CharacterVisibility.PRIVATE.code)
        val stillVisible = character("visible")
        coEvery { characterRepository.getDiscoverFeedIds(any(), emptySet(), 1000) } returns
            listOf(newest.id, agedOut.id, blocked.id, private.id, stillVisible.id)

        val firstPage = feed(DiscoverFeed.NewArrivals)
        val secondPage = feed(
            DiscoverFeed.NewArrivals,
            cursor = assertNotNull(firstPage.nextCursor),
            blocked = setOf(blocked.authorId),
        )

        assertEquals(listOf(stillVisible), secondPage.items)
        assertFalse(secondPage.refreshed)
        assertNull(secondPage.nextCursor)
    }

    private suspend fun feed(
        kind: DiscoverFeed,
        cursor: Int = 0,
        refresh: Boolean = false,
        blocked: Set<String> = emptySet(),
    ): CachedCharactersResult =
        manager.getDiscoverFeed("user", "device", kind, 1, cursor, refresh, blocked)

    private fun character(
        id: String,
        createdAt: UtcTimestamp = UtcTimestamp.now(),
        authorId: String = "author",
        visibility: Int = CharacterVisibility.PUBLIC.code,
    ): CharacterDbo = CharacterDbo(
        id = id, createdAt = createdAt.toString(), authorId = authorId, name = id,
        description = "", prompt = "", initialMessage = "", visibility = visibility,
        category = "original", tags = emptyList(),
    ).also { characters[id] = it }
}
