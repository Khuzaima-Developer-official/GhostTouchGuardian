package com.antigravity.ghosttouchguardian.model

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
        title = "Balanced",
        subtitle = "Optimal balance for daily phone usage",
        maxDwellMs = 35,
        maxContactSize = 0.045f,
        echoWindowMs = 55L,
        jitterThresholdPx = 3.0f
    ),

    MICRO_SPARKS(
        id = "micro_sparks",
        title = "Gaming Safe",
        subtitle = "Fast electrical noise filter without input lag",
        maxDwellMs = 22,
        maxContactSize = 0.030f,
        echoWindowMs = 35L,
        jitterThresholdPx = 1.5f
    ),

    TRAILING_ECHO(
        id = "trailing_echo",
        title = "Trailing Echo",
        subtitle = "Suppresses phantom taps after finger release",
        maxDwellMs = 45,
        maxContactSize = 0.055f,
        echoWindowMs = 75L,
        jitterThresholdPx = 4.0f
    ),

    SEVERE_UV_GLUE(
        id = "severe_uv_glue",
        title = "High (Damaged)",
        subtitle = "Aggressive filter for UV glue or digitizer cracks",
        maxDwellMs = 55,
        maxContactSize = 0.070f,
        echoWindowMs = 85L,
        jitterThresholdPx = 5.5f
    );

    companion object {
        fun fromId(id: String): GhostTouchProfile {
            // Find profile
            return values().firstOrNull { it.id == id } ?: BALANCED
        }
    }
}
