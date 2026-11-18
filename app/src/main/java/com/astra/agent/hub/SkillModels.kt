package com.astra.agent.hub

/** One skill: instructions the model loads only when the request needs them. */
data class Skill(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val keywords: List<String>,
    val tools: List<String>,          // tool names ("web_search", "http_get"), "plugin:<id>" or "doc:<pdf|docx|pptx|xlsx|csv>"
    val permissions: List<String>,
    val inputSchema: String,
    val outputSchema: String,
    val dependencies: List<String>,
    val steps: List<String>,
    val verification: List<String>,
    val instructions: String,
    val version: String = "1.0",
    val outputFormat: String = "",     // "pptx", "docx", "pdf", "xlsx", "csv" when the skill produces a file
    val custom: Boolean = false
)

data class SkillAvailability(val ready: Boolean, val note: String)

data class SkillUse(val ts: Long, val request: String, val skills: List<String>, val status: String, val detail: String)
