package com.lvsmsmch.aichat._common

import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.InternalServerErrorException

class UsernameGenerator(
    private val userRepository: UserRepository
) {

    companion object {
        private const val MAX_STANDARD_ATTEMPTS = 50
        private const val MAX_FALLBACK_ATTEMPTS = 20
        private const val MIN_USERNAME_LENGTH = 3
        private const val MAX_USERNAME_LENGTH = 20
        private const val FALLBACK_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

        private val animeCharacters = listOf(
            "naruto", "sasuke", "goku", "luffy", "ichigo", "natsu", "edward", "alphonse",
            "light", "ryuk", "saitama", "genos", "tanjiro", "zenitsu", "deku", "bakugo",
            "todoroki", "eren", "mikasa", "levi", "rimuru", "ainz", "subaru", "kazuma",
            "aqua", "megumin", "kirito", "asuna", "meliodas", "ban", "senku", "tsukasa",
            "yusuke", "toguro", "gon", "killua", "kurapika", "leorio", "dio", "jotaro",
            "giorno", "mob", "reigen", "inuyasha", "kagome", "sesshomaru", "vegeta",
            "piccolo", "gohan", "trunks"
        )

        private val fantasyCreatures = listOf(
            "dragon", "phoenix", "griffin", "basilisk", "wyvern", "chimera", "hydra",
            "kraken", "leviathan", "behemoth", "unicorn", "pegasus", "sphinx", "minotaur",
            "centaur", "gargoyle", "banshee", "wraith", "lich", "demon", "angel", "valkyrie",
            "djinn", "ifrit", "golem", "elemental", "sprite", "fairy", "dryad", "nymph",
            "titan", "cyclops", "medusa", "cerberus", "fenrir", "roc", "salamander",
            "undine", "sylph", "gnome"
        )

        private val animeTerms = listOf(
            "senpai", "kohai", "otaku", "ninja", "samurai", "shinobi", "sensei", "chan",
            "kun", "san", "sama", "baka", "tsundere", "yandere", "kawaii", "sugoi",
            "nani", "desu", "moe", "chibi", "onii", "onee", "imouto", "aniki", "dono",
            "hime", "ouji", "ronin", "yokai", "kami"
        )

        private val fantasyItems = listOf(
            "sword", "blade", "staff", "wand", "crystal", "orb", "shield", "armor",
            "bow", "arrow", "rune", "scroll", "potion", "elixir", "tome", "grimoire",
            "amulet", "talisman", "crown", "scepter", "dagger", "axe", "hammer", "spear",
            "ring", "pendant", "cloak", "boots", "gauntlet", "helm"
        )

        private val adjectives = listOf(
            "brave", "swift", "dark", "light", "fire", "ice", "storm", "shadow", "mystic",
            "arcane", "noble", "wild", "fierce", "calm", "wise", "strong", "quick", "silent",
            "bright", "crimson", "azure", "golden", "silver", "iron", "steel", "void",
            "lunar", "solar", "cosmic", "ancient", "eternal", "divine", "infernal", "frost",
            "ember", "thunder", "wind", "earth", "ocean", "star"
        )

        private val allWords = animeCharacters + fantasyCreatures + animeTerms + fantasyItems + adjectives

        private val secureRandom = java.security.SecureRandom()
    }

    suspend fun generateUniqueUsername(): String {
        repeat(MAX_STANDARD_ATTEMPTS) {
            val username = generateRandomUsername()
            if (isUsernameAvailable(username)) {
                return username
            }
        }

        repeat(MAX_FALLBACK_ATTEMPTS) {
            val username = generateFallbackUsername()
            if (isUsernameAvailable(username)) {
                return username
            }
        }

        throw InternalServerErrorException("Failed to generate unique username after ${MAX_STANDARD_ATTEMPTS + MAX_FALLBACK_ATTEMPTS} attempts")
    }

    private fun generateRandomUsername(): String {
        val word1 = allWords[secureRandom.nextInt(allWords.size)]
        val word2 = allWords[secureRandom.nextInt(allWords.size)]
        val number = secureRandom.nextInt(90) + 10

        val username = "${word1}_${word2}_$number"

        return if (username.length in MIN_USERNAME_LENGTH..MAX_USERNAME_LENGTH) {
            username
        } else {
            val shortWords = allWords.filter { it.length <= 15 }
            val singleWord = shortWords[secureRandom.nextInt(shortWords.size)]
            "${singleWord}_$number"
        }
    }

    private fun generateFallbackUsername(): String {
        val randomSuffix = (1..6)
            .map { FALLBACK_CHARS[secureRandom.nextInt(FALLBACK_CHARS.length)] }
            .joinToString("")

        return "user_$randomSuffix"
    }

    private suspend fun isUsernameAvailable(username: String): Boolean {
        return userRepository.findByUsername(username) == null
    }

    fun getGenerationStats(): UsernameGenerationStats {
        val totalWords = allWords.size
        val wordCombinations = totalWords * (totalWords - 1)
        val numberVariants = 90
        val totalPossibleUsernames = wordCombinations * numberVariants

        return UsernameGenerationStats(
            totalWords = totalWords,
            wordCombinations = wordCombinations,
            numberVariants = numberVariants,
            totalPossibleUsernames = totalPossibleUsernames,
            categories = mapOf(
                "anime_characters" to animeCharacters.size,
                "fantasy_creatures" to fantasyCreatures.size,
                "anime_terms" to animeTerms.size,
                "fantasy_items" to fantasyItems.size,
                "adjectives" to adjectives.size
            )
        )
    }
}

data class UsernameGenerationStats(
    val totalWords: Int,
    val wordCombinations: Int,
    val numberVariants: Int,
    val totalPossibleUsernames: Int,
    val categories: Map<String, Int>
)