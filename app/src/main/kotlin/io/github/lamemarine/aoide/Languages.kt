package io.github.lamemarine.aoide

/** Whisper language codes -> display names. "auto" lets the model detect the language. */
val LANGUAGES: List<Pair<String, String>> = listOf(
    "auto" to "Auto-detect",
    "en" to "English",
    "es" to "Spanish",
    "fr" to "French",
    "de" to "German",
    "it" to "Italian",
    "pt" to "Portuguese",
    "nl" to "Dutch",
    "pl" to "Polish",
    "ru" to "Russian",
    "uk" to "Ukrainian",
    "tr" to "Turkish",
    "ar" to "Arabic",
    "he" to "Hebrew",
    "hi" to "Hindi",
    "ja" to "Japanese",
    "ko" to "Korean",
    "zh" to "Chinese",
    "vi" to "Vietnamese",
    "th" to "Thai",
    "id" to "Indonesian",
    "sv" to "Swedish",
    "da" to "Danish",
    "no" to "Norwegian",
    "fi" to "Finnish",
    "cs" to "Czech",
    "el" to "Greek",
    "ro" to "Romanian",
    "hu" to "Hungarian",
    "bg" to "Bulgarian",
    "mi" to "Maori",
)

fun languageName(code: String) = LANGUAGES.firstOrNull { it.first == code }?.second ?: "Auto-detect"
