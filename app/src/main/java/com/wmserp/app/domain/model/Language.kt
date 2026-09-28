package com.wmserp.app.domain.model

/** UI language preference. [tag] is a BCP-47 language tag; null follows the system locale. */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    PERSIAN("fa");

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}
