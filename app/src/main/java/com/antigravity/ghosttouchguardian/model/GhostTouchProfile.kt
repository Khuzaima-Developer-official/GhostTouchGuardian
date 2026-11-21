package com.antigravity.ghosttouchguardian.model

enum class GhostTouchProfile(
    val id: String,
    val title: String,
    val subtitle: String,
    val maxDwellMs: Int,
    val echoWindowMs: Long
) {
    BALANCED(
        id = "balanced",
        title = "Balanced",
        subtitle = "Optimal balance for daily phone usage",
        maxDwellMs = 45,
        echoWindowMs = 60L
    ),

    MICRO_SPARKS(
        id = "micro_sparks",
        title = "Gaming Safe",
        subtitle = "Fast electrical noise filter without input lag",
        maxDwellMs = 22,
        echoWindowMs = 35L
    ),

    TRAILING_ECHO(
        id = "trailing_echo",
        title = "Trailing Echo",
        subtitle = "Suppresses phantom taps after finger release",
        maxDwellMs = 45,
        echoWindowMs = 75L
    ),

    SEVERE_UV_GLUE(
        id = "severe_uv_glue",
        title = "High (Damaged)",
        subtitle = "Aggressive filter for UV glue or digitizer cracks",
        maxDwellMs = 55,
        echoWindowMs = 85L
    );

    companion object {
        fun fromId(id: String): GhostTouchProfile {
            return values().firstOrNull { it.id == id } ?: BALANCED
        }
    }
}
