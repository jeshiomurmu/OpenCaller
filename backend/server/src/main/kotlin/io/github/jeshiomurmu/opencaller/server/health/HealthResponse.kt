package io.github.jeshiomurmu.opencaller.server.health

import kotlinx.serialization.Serializable

/** Body of `GET /health`. */
@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
)
