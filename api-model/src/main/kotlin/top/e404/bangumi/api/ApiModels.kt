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
