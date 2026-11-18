package com.antigravity.ghosttouchguardian.model

/**
 * Multi-Signature Diagnostic Profiles for capacitive touch anomalies.
 * Allows tailoring detection heuristics to the specific physical cause:
 * - Electrical AC ripple noise / micro-sparks
 * - Sticky digitizer trailing release echoes
 * - UV glue / moisture / temperature smudges
 * - User-calibrated custom baseline
 */
enum class GhostTouchProfile(
    val id: String,
    val title: String,
    val subtitle: String,
    val maxDwellMs: Int,
    val maxContactSize: Float,
    val echoWindowMs: Long,
    val jitterThresholdPx: Float
) {
    BALANCED(
        id = "balanced",
        title = "Balanced Protection",
        subtitle = "Optimal for daily use: suppresses micro-sparks & release echoes",
        maxDwellMs = 35,
        maxContactSize = 0.045f,
        echoWindowMs = 55L,
        jitterThresholdPx = 3.0f
    ),

    MICRO_SPARKS(
        id = "micro_sparks",
        title = "Micro-Spark Filter",
        subtitle = "Fast electrical noise (charger ripple / static charge)",
        maxDwellMs = 22,
        maxContactSize = 0.030f,
        echoWindowMs = 35L,
        jitterThresholdPx = 1.5f
    ),

    TRAILING_ECHO(
        id = "trailing_echo",
        title = "Trailing Echo Suppressor",
        subtitle = "Phantoms appearing immediately after lifting your finger",
        maxDwellMs = 45,
        maxContactSize = 0.055f,
        echoWindowMs = 75L,
        jitterThresholdPx = 4.0f
    ),

    SEVERE_UV_GLUE(
        id = "severe_uv_glue",
        title = "Heavy / UV Glue Filter",
        subtitle = "Aggressive filter for tempered glass glue or damaged digitizers",
        maxDwellMs = 60,
        maxContactSize = 0.075f,
        echoWindowMs = 95L,
        jitterThresholdPx = 6.0f
    ),

    CUSTOM(
        id = "custom",
        title = "User Calibrated",
        subtitle = "Custom thresholds derived from your phone's diagnostic test",
        maxDwellMs = 35,
        maxContactSize = 0.045f,
        echoWindowMs = 55L,
        jitterThresholdPx = 3.0f
    );

    companion object {
        fun fromId(id: String): GhostTouchProfile {
            return values().firstOrNull { it.id == id } ?: BALANCED
        }
    }
}
