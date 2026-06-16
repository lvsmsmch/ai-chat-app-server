package com.lvsmsmch.aichat.utils.updaters

import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.chat.database.ChatRepository
import com.lvsmsmch.aichat.utils.logger
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

fun configureCharacterCoOccurrenceUpdater(
    databaseScope: CoroutineScope,
    characterRepository: CharacterRepository,
    chatRepository: ChatRepository,
    updateIntervalMinutes: Long = 24 * 60,
    minimumUsersThreshold: Int = 3,
    maxConnectionsPerCharacter: Int = 100,
    minimumScoreThreshold: Float = 0.01f
): Job {
    val parentJob = SupervisorJob()
    val updaterScope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    updaterScope.launch {
        while (isActive) {
            try {
                logger.info("Starting co-occurrence score calculation")
                
                val allChats = chatRepository.getAllNonDeletedChats()
                
                val userCharacterSets = allChats
                    .groupBy { it.userId }
                    .mapValues { (_, chats) -> 
                        chats.flatMap { it.characterIds }.toSet() 
                    }
                    .filter { it.value.size > 1 }
                
                logger.info("Processing ${userCharacterSets.size} users with multiple characters")
                
                val characterPairCounts = mutableMapOf<Pair<String, String>, Int>()
                
                userCharacterSets.values.forEach { characterSet ->
                    characterSet.forEach { char1 ->
                        characterSet.forEach { char2 ->
                            if (char1 != char2) {
                                val pair = if (char1 < char2) char1 to char2 else char2 to char1
                                characterPairCounts[pair] = (characterPairCounts[pair] ?: 0) + 1
                            }
                        }
                    }
                }
                
                val significantPairs = characterPairCounts
                    .filter { it.value >= minimumUsersThreshold }
                
                logger.info("Found ${significantPairs.size} significant character pairs")
                
                val allCharacters = characterRepository.getAllPublicCharacters()
                val totalUsers = userCharacterSets.size.toFloat()
                
                allCharacters.forEach { character ->
                    val coOccurrenceScores = mutableMapOf<String, Float>()
                    
                    significantPairs.forEach { (pair, count) ->
                        val score = count / totalUsers
                        if (score >= minimumScoreThreshold) {
                            when {
                                pair.first == character.id -> {
                                    coOccurrenceScores[pair.second] = score
                                }
                                pair.second == character.id -> {
                                    coOccurrenceScores[pair.first] = score
                                }
                            }
                        }
                    }
                    
                    val topConnections = coOccurrenceScores
                        .toList()
                        .sortedByDescending { it.second }
                        .take(maxConnectionsPerCharacter)
                        .toMap()
                    
                    if (topConnections.isNotEmpty()) {
                        characterRepository.updateCoOccurrenceScore(character.id, topConnections)
                    }
                }
                
                logger.info("Co-occurrence score calculation completed for ${allCharacters.size} characters")
                
                delay(TimeUnit.MINUTES.toMillis(updateIntervalMinutes))
                
            } catch (e: CancellationException) {
                logger.debug("Co-occurrence updater cancelled")
                break
            } catch (e: Exception) {
                logger.error("Error during co-occurrence calculation: ${e.message}", e)
                cancel()
            }
        }
    }

    return parentJob
}