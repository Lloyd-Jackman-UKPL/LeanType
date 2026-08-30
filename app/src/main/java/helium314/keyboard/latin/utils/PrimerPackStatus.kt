/*
 * Copyright (C) 2026 LeanBitLab
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Welcome Wizard helper: primer pack availability per enabled subtype locale.
 * Mirrors DictionaryFacilitatorImpl.loadPgenPack resolution (external dir, then
 * bundled asset) WITHOUT loading any pack — file existence + size only.
 *
 * GUARDRAIL: read-only status queries over data files. No text, no input logic.
 */
package helium314.keyboard.latin.utils

import android.content.Context
import java.io.File
import java.util.Locale

object PrimerPackStatus {

    enum class Source { EXTERNAL, BUNDLED }

    data class Info(val tag: String, val source: Source?, val sizeBytes: Long)

    /** Tags tried for a locale, in the same order loadPgenPack uses them. */
    private fun tagsFor(locale: Locale): List<String> {
        val lang = locale.language.lowercase(Locale.US)
        val region = locale.country?.uppercase(Locale.US).orEmpty()
        return if (region.isNotEmpty()) listOf("${lang}_$region", lang) else listOf(lang)
    }

    /** Status for one locale: which source would serve it and how big the pack is. */
    fun forLocale(context: Context, locale: Locale): Info {
        for (tag in tagsFor(locale)) {
            context.getExternalFilesDir(null)?.let { root ->
                val f = File(root, "primer/pgen_$tag.json")
                if (f.isFile && f.length() > 0L)
                    return Info(tag, Source.EXTERNAL, f.length())
            }
        }
        for (tag in tagsFor(locale)) {
            val asset = "primer/pgen_${tag.replace('-', '_')}.json"
            try {
                val size = context.assets.open(asset).use { ins ->
                    // assets have no reliable length without AFD; read size conservatively
                    var n = 0L; val buf = ByteArray(1 shl 16)
                    while (true) { val r = ins.read(buf); if (r < 0) break; n += r }
                    n
                }
                return Info(tag, Source.BUNDLED, size)
            } catch (e: Exception) { /* no bundled pack for this tag */ }
        }
        return Info(tagsFor(locale).first(), null, 0L)
    }

    /** True when at least one enabled locale resolves to a usable pack. */
    fun anyAvailable(context: Context, locales: List<Locale>): Boolean =
        locales.any { forLocale(context, it).source != null }
}
