/*
 * Copyright (C) 2026 LeanBitLab
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin.utils

import android.content.Context
import helium314.keyboard.latin.ai.IOfflineAiProvider
import helium314.keyboard.latin.ai.OfflineAiLoader
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AINextWord"

/**
 * Offline-flavor AI next-word engine factory. Uses the same dynamically-loaded
 * [IOfflineAiProvider] plugin surface that v4.1.6+ proofreading uses (OfflineAiLoader) — NOT the
 * old embedded llamacpp runtime (removed from the APK upstream). The plugin already serialises
 * all generation on its single native context, so no concurrency guard is needed here.
 */
object AINextWordEngineFactory {

    fun create(context: Context): AINextWordEngine? {
        if (!context.prefs().getBoolean(Settings.PREF_AI_NEXT_WORD, Defaults.PREF_AI_NEXT_WORD)) {
            return null
        }
        return OfflineNextWordEngine(context.applicationContext)
    }
}

private class OfflineNextWordEngine(private val context: Context) : AINextWordEngine {

    private fun provider(): IOfflineAiProvider? = OfflineAiLoader.getProvider(context)

    /** Ready when the plugin is installed and a provider is loadable. */
    override fun isReady(): Boolean =
        OfflineAiLoader.hasPlugin(context) && provider() != null

    override suspend fun suggestNextWords(prompt: String): List<String> = withContext(Dispatchers.IO) {
        val p = provider()
        if (p == null) {
            Log.w(TAG, "AINextWord: offline AI plugin not loaded, returning empty")
            return@withContext emptyList()
        }
        try {
            val params: Map<String, Any> = mapOf(
                "temperature" to 0.2,
                "top_p" to 0.9,
                "top_k" to 40,
                "min_p" to 0.05,
                "max_tokens" to 16
            )
            // n_predict as max_tokens; the plugin's generate() returns one string.
            val raw = p.generate(prompt, params)
            val words = splitCandidates(raw)
            Log.i(TAG, "AINextWord: completion='$raw' -> candidates=$words")
            words
        } catch (e: Exception) {
            Log.e(TAG, "AINextWord: completion failed", e)
            emptyList()
        }
    }

    private fun splitCandidates(raw: String): List<String> {
        val out = LinkedHashSet<String>()
        for (token in raw.split(Regex("[\\s,;:!?\\.]+"))) {
            val word = token.trim().trim('\'', '"')
            if (word.isNotEmpty() && word.any { it.isLetter() }) out.add(word)
            if (out.size >= 3) break
        }
        return out.toList()
    }
}