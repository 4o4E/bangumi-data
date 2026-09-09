package top.e404.bangumi.server.archive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ArchiveTag(val name: String = "", val count: Int = 0)

@Serializable
data class ArchiveSubject(
    val id: Long,
    val type: Int,
    val name: String = "",
    @SerialName("name_cn") val nameCn: String = "",
    val summary: String = "",
    val nsfw: Boolean = false,
    val tags: List<ArchiveTag> = emptyList(),
    @SerialName("meta_tags") val metaTags: List<String> = emptyList(),
)

@Serializable
data class ArchiveCharacter(
    val id: Long,
    val role: Int,
    val name: String = "",
    val summary: String = "",
    val comments: Long = 0,
    val collects: Long = 0,
)

@Serializable
data class ArchiveSubjectCharacter(
    @SerialName("character_id") val characterId: Long,
    @SerialName("subject_id") val subjectId: Long,
    val type: Int,
    val order: Int = 0,
)

data class ArchiveRelease(
    val name: String,
    val url: String,
    val digest: String,
    val createdAtEpochMillis: Long,
)

data class ArchiveImportSummary(
    val subjects: Long,
    val characters: Long,
    val relations: Long,
)
