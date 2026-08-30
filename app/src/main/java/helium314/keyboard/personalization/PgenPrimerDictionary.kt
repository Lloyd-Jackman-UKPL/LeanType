/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 *
 * DRAFT — Shape B candidate source for LeanType angle-2 (adaptive re-weighting).
 * Proposed new file, NOT yet wired into DictionaryFacilitatorImpl.
 *
 * Design (see /root/leantype-angle2-interpolation-spec.md):
 *   P(w | h) = [ count(h,w)·R + α·P_gen(w|h) ] / [ count(h)·R + α ]
 *   - P_gen  = generic next-word prior from a loadable per-locale primer pack (angle 1)
 *   - count  = lightweight Java-side personal n-gram counter (accumulates as user types)
 *   - R      = two-timescale recency (30-day durable identity + recent bonus)
 *   - α      = fixed pseudo-count (prior strength; personal evidence eventually dominates)
 *
 * GUARDRAIL (HARD): this class is a candidate producer + ranker, NEVER a text writer.
 *   - It only fills the suggestion strip (KIND_PREDICTION).
 *   - shouldAutoCommit() is deliberately NOT overridden -> stays false.
 *   - It never calls RichInputConnection / never touches the composing or gesture buffer.
 *   - Commit stays user-driven (tap in SuggestionStripView -> pickSuggestionManually).
 */
package helium314.keyboard.personalization

import java.util.Locale

import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.makedict.WordProperty
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.utils.Log

/**
 * A `Dictionary`-interface candidate source that interpolates a generic primer (P_gen)
 * with a Java-side personal n-gram counter, outputting next-word predictions.
 */
class PgenPrimerDictionary(
    private val locale: Locale,
    private val primer: PgenPrimer,
    private val personal: PersonalNGram
) : Dictionary(Dictionary.TYPE_USER_HISTORY, locale) {

    companion object {
        const val TAG = "PgenPrimerDictionary"

        // Prior strength (pseudo-count). Personal evidence dominates once
        // count(h,w) > α·P_gen(w|h). Fixed constant — never scaled up with usage (spec §4.1).
        const val ALPHA = 80f

        // Two-timescale recency (spec §5): 30-day durable identity for personal counts,
        // plus a fast bonus (~3-day) for recently typed words.
        const val LAMBDA_SLOW = 0.0231f   // half-life ~30 days
        const val LAMBDA_FAST = 0.231f    // half-life ~3 days
        const val FAST_BONUS_MAX = 1.0f

        // Map a 0..1 interpolated probability into the suggestion-score space so it merges
        // sensibly with other dictionary scores (native next-word scores are ~1e6 magnitude).
        const val SCORE_SCALE = 1_000_000f

        // Cap how many candidates we produce per context (strip is already bounded).
        const val MAX_CANDIDATES = 8
    }

    /** Returns next-word predictions ONLY when there is no typed word in the composing buffer.
     *  This is pure ranking — nothing here commits or writes text. */
    override fun getSuggestions(
        composedData: ComposedData,
        ngramContext: NgramContext,
        proximityInfoHandle: Long,
        settingsValuesForSuggestion: SettingsValuesForSuggestion,
        sessionId: Int,
        weightForLocale: Float,
        inOutWeightOfLangModelVsSpatialModel: FloatArray
    ): ArrayList<SuggestedWordInfo>? {
        if (!composedData.mTypedWord.isEmpty()) {
            return null // not a next-word prediction
        }

        val prev = ngramContext.extractPrevWordsContextArray()
        val prev1 = prev.getOrNull(0)?.lowercase(locale) ?: ""
        val prev2 = prev.getOrNull(1)?.lowercase(locale) ?: ""
        if (prev1.isEmpty() || prev1 == NgramContext.BEGINNING_OF_SENTENCE_TAG) {
            // No preceding word context — not enough signal; let the stock engine lead.
            return null
        }

        val nowMs = System.currentTimeMillis()
        val scored = scoreCandidates(prev1, prev2, nowMs)
            .sortedByDescending { it.second }
            .take(MAX_CANDIDATES)

        if (scored.isEmpty()) return null

        val ctxString = if (prev2.isNotEmpty()) "$prev2 $prev1" else prev1
        val out = ArrayList<SuggestedWordInfo>(scored.size)
        for ((word, prob) in scored) {
            val score = (prob * SCORE_SCALE).toInt().coerceAtLeast(0)
            out.add(SuggestedWordInfo(
                word, ctxString, score,
                SuggestedWordInfo.KIND_PREDICTION,
                this,
                SuggestedWordInfo.NOT_AN_INDEX,
                SuggestedWordInfo.NOT_A_CONFIDENCE
            ))
        }
        // LOCAL TESTING: capture the scoring for on-device A/B (logcat). Pure read-only logging.
        Log.i(TAG, "PRED ctx=\"$ctxString\" cands=${out.size}")
        for (info in out) {
            Log.i(TAG, "PRED   ${info.mWord}  score=${info.mScore}  slot=${info.mKindAndFlags}")
        }
        return out
    }

    /**
     * Interpolation over the union of primer + personal candidates for context (prev1, prev2)
     * with backoff: trigram → bigram → unigram prior.
     */
    private fun scoreCandidates(prev1: String, prev2: String, nowMs: Long): List<Pair<String, Float>> {
        val triKey = if (prev2.isNotEmpty()) "$prev2 $prev1" else null

        val words = LinkedHashSet<String>()
        words.addAll(primer.candidateWords(prev2, prev1))
        words.addAll(personal.wordsInContext(triKey, prev1))

        var totalPersonal = 0f
        for (w in words) totalPersonal += personal.effectiveCount(triKey, prev1, w, nowMs)

        val out = ArrayList<Pair<String, Float>>(words.size)
        for (w in words) {
            val pg = primer.priorFor(w, prev2, prev1)
            val c = personal.effectiveCount(triKey, prev1, w, nowMs)
            val den = totalPersonal + ALPHA
            if (den <= 0f) continue
            out.add(w to (c + ALPHA * pg) / den)
        }
        return out
    }

    // ---- Dictionary contract boilerplate ----

    override fun isInDictionary(word: String): Boolean =
        primer.isInDict(word.lowercase(locale)) || personal.contains(word.lowercase(locale))

    override fun isUserSpecific(): Boolean = true

    override fun getWordProperty(word: String, isBeginningOfSentence: Boolean): WordProperty? = null

    /** Feed every committed word here (see integration: DictionaryFacilitatorImpl line ~450). */
    fun onWordCommitted(word: String, prev1: String?, prev2: String?) {
        personal.record(word.lowercase(locale), prev1?.lowercase(locale), prev2?.lowercase(locale),
            System.currentTimeMillis())
    }

    override fun close() {
        personal.flush()
    }
}
