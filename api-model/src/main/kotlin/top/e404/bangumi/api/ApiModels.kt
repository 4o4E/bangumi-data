package top.e404.bangumi.api

import kotlinx.serialization.Serializable

const val BANGUMI_DATA_API_VERSION = "v1"

@Serializable
data class ApiEnvelope<T>(
    val data: T,
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
)

@Serializable
data class HealthResponse(
    val status: String,
)

@Serializable
data class ServiceInfo(
    val apiVersion: String,
    val serviceVersion: String,
)

@Serializable
enum class CharacterGender {
    FEMALE,
    MALE,
}

@Serializable
enum class CharacterRelation {
    PROTAGONIST,
    SUPPORTING,
    CAMEO,
}

@Serializable
enum class FamiliarityTier {
    CORE,
    FAMILIAR,
    LONG_TAIL,
}

@Serializable
data class PopularityMetric(
    val provider: String,
    val metric: String,
    val value: Long,
)

@Serializable
data class CatalogWork(
    val id: Long,
    val type: Int,
    val name: String,
    val imageUrl: String? = null,
    val relation: CharacterRelation,
    val familiarity: FamiliarityTier,
    val popularityRank: Int,
    val popularities: List<PopularityMetric> = emptyList(),
)

@Serializable
data class CatalogPopularitySample(
    val characterId: Long,
    val characterName: String,
    val characterCollects: Long,
    val workId: Long,
    val workName: String,
    val workType: Int,
    val workPopularities: List<PopularityMetric>,
    val relation: CharacterRelation,
    val familiarity: FamiliarityTier,
    val popularityRank: Int,
    val candidateCharacterCount: Int,
    val selectedCharacterCount: Int,
    val selectionReason: String? = null,
)

@Serializable
data class CatalogPopularityDiagnostics(
    val generation: String,
    val algorithmVersion: Int,
    val gender: CharacterGender,
    val characterId: Long? = null,
    val maxCharacterCollects: Long,
    val workPopularityAvailable: Boolean,
    val selectedCharacterCount: Long,
    val matchingCharacterCount: Long,
    val samples: List<CatalogPopularitySample>,
)

@Serializable
data class CatalogCharacter(
    val id: Long,
    val name: String,
    val aliases: List<String>,
    val gender: CharacterGender,
    val description: String? = null,
    val imageUrl: String,
    val works: List<CatalogWork>,
    val popularities: List<PopularityMetric>,
    val sourceUpdatedAt: Long,
)

@Serializable
data class CatalogPage<T>(
    val items: List<T>,
    val offset: Int,
    val limit: Int,
    val total: Long,
    val generation: String,
)

@Serializable
data class CatalogStatus(
    val generation: String? = null,
    val sourceVersion: String? = null,
    val sourceCreatedAt: Long? = null,
    val activatedAt: Long? = null,
    val characterCount: Long = 0,
    val syncRunning: Boolean = false,
    val syncPhase: String? = null,
    val lastError: String? = null,
)
