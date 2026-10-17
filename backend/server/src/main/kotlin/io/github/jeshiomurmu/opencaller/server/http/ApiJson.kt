package io.github.jeshiomurmu.opencaller.server.http

import kotlinx.serialization.json.Json

/**
 * JSON for request and response bodies: unknown request fields are ignored (older servers accept newer apps) and
 * every response field is written, defaults and nulls included, as openapi.yaml documents them.
 */
val ApiJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
