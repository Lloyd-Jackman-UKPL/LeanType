/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 *
 * Loader for a per-locale primer pack (e.g. primer_en_GB.pgen.json).
 * Reads the JSON shape:
 *   { "uni": {word: prob}, "bi": {word: {next: prob}}, "tri": {"w1 w2": {next: prob}} }
 * Backoff: priorFor(w, prev1, prev2) = tri[prev2 prev1][w] -> bi[prev1][w] -> uni[w] -> 1e-6f.
 *
 * STORAGE (rung 3, 24 Aug 2026 — replaces the old boxed Map<String,Map<String,Float>> model):
 *   Compactly int-indexed, because the old model materialised every word occurrence as a fresh
 *   String + boxed Float in ~966k LinkedHashMap nodes -> ~226 MiB heap and marginal OOMs against
 *   the 512 MiB largeHeap cap on full-size en_GB packs. The compact model is:
 *     - allWords[]/wordId : one global int vocab over EVERY word (predicted + context-only)
 *     - uniProb[]         : float per wordId, Float.NaN = not a unigram
 *     - predicted[]       : membership in the PREDICTED vocab (uni + all continuation words)
 *     - biOff/biLen[]     : continuation slice of flat cont pool, indexed by id(prev1)
 *     - triOff/triLen[]   : continuation slice indexed by triCtxId
 *     - contWord/contProb[] : flat continuation pool shared by bi+tri slices
 *     - triKeys/triVals[]   : open-addressed `long` code (id(prev2)<<32 | id(prev1)) -> triCtxId
 *   A JVM probe measured ~48 MiB heap (vs ~226 MiB old) on the real en_GB pack — sits comfortably
 *   under a 128 MiB heap, which is the goal for mid-range devices (not just Lloyd's S24+).
 *
 * QUERY SEMANTICS ARE UNCHANGED: candidate words and backoff probabilities must be byte-identical
 * to the old map model. Guarded by a JVM differential test on the real pack before shipping.
 */
package helium314.keyboard.personalization

import android.util.JsonReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class PgenPrimer private constructor(
    private val allWords: Array<String>,        // id -> word (all words incl. context-only)
    private val wordId: HashMap<String, Int>,   // word -> id  (all words)
    private val uniProb: FloatArray,            // [id] unigram prob, Float.NaN if not a unigram
    private val predicted: BooleanArray,        // [id] true = in predicted vocab (uni + continuation)
    private val biOff: IntArray,                // [id(prev1)] start of continuation slice, -1 if none
    private val biLen: IntArray,                // [id(prev1)] length of that slice
    private val contWord: IntArray,             // flat continuation ids (bi slices then tri slices)
    private val contProb: FloatArray,           // flat continuation probs, parallel to contWord
    private val triOff: IntArray,               // [triCtxId] start of slice
    private val triLen: IntArray,               // [triCtxId] length of slice
    private val triKeys: LongArray,             // open-addressed tri context codes
    private val triVals: IntArray,              // -> triCtxId, -1 = empty
    val uniCount: Int,
    val biCtxCount: Int,
    val triCtxCount: Int,
    val contCount: Int
) {
    /** O(1) membership over the PREDICTED vocabulary (uni words + all continuation words). */
    fun isInDict(word: String): Boolean {
        val id = wordId[word] ?: return false
        return predicted[id]
    }

    /** Words the primer can predict for context (prev2, prev1): the UNION of tri + bi
     *  continuation words (matching the old `trigrams[triKey]?.keys + bigrams[prev1]?.keys`),
     *  tri entries first, deduped. Order is deterministic. Empty prev2 => bigram-only. */
    fun candidateWords(prev2: String, prev1: String): List<String> {
        val set = LinkedHashSet<String>()
        if (prev2.isNotEmpty()) {
            val p2 = wordId[prev2]
            val p1 = wordId[prev1]
            if (p2 != null && p1 != null) {
                val slice = triSlice(p2, p1)
                if (slice != null) {
                    val (off, len) = slice
                    for (i in 0 until len) set.add(allWords[contWord[off + i]])
                }
            }
        }
        if (prev1.isNotEmpty()) {
            val p1 = wordId[prev1]
            if (p1 != null) {
                val off = biOff[p1]
                if (off >= 0) {
                    val len = biLen[p1]
                    for (i in 0 until len) set.add(allWords[contWord[off + i]])
                }
            }
        }
        return set.toList()
    }

    /** Smoothed generic prior with trigram -> bigram -> unigram backoff. */
    fun priorFor(w: String, prev2: String, prev1: String): Float {
        val wid = wordId[w] ?: return 1e-6f
        if (prev2.isNotEmpty()) {
            val p2 = wordId[prev2]
            val p1 = wordId[prev1]
            if (p2 != null && p1 != null) {
                val triSlice = triSlice(p2, p1)
                if (triSlice != null) {
                    val (off, len) = triSlice
                    for (i in 0 until len) if (contWord[off + i] == wid) return contProb[off + i]
                }
            }
        }
        if (prev1.isNotEmpty()) {
            val p1 = wordId[prev1]
            if (p1 != null) {
                val off = biOff[p1]
                if (off >= 0) {
                    val len = biLen[p1]
                    for (i in 0 until len) if (contWord[off + i] == wid) return contProb[off + i]
                }
            }
        }
        val u = uniProb[wid]
        if (!u.isNaN()) return u
        return 1e-6f
    }

    /** Structural byte-budget estimate of THIS instance's Java-heap footprint — mirrors the
     *  JVM probe so the debug log shows whether rung 3 actually shrank the pack (it should
     *  read ~48 MB vs ~226 MB for the old boxed-map build on the full en_GB pack). */
    fun estimatedHeapMB(): Int {
        var b = 0L
        b += allWords.size.toLong() * 8L                                // Array<String> refs
        for (s in allWords) b += 56L + 2L * s.length                    // String obj + char[] (shared)
        b += wordId.size.toLong() * 60L                                 // HashMap node+entry+bucket
        b += uniProb.size.toLong() * 4L
        b += predicted.size.toLong()                                    // boolean[] ~1 B/elem
        b += biOff.size.toLong() * 8L + biLen.size.toLong() * 4L
        b += contWord.size.toLong() * 4L + contProb.size.toLong() * 4L
        b += triOff.size.toLong() * 8L + triLen.size.toLong() * 4L
        b += triKeys.size.toLong() * 8L + triVals.size.toLong() * 4L
        return (b / (1024L * 1024L)).toInt()
    }

    fun vocabCount(): Int = allWords.size

    /** One-line summary for the "PgenPrimer loaded" debug log. */
    fun describe(): String =
        "uni=$uniCount biCtx=$biCtxCount triCtx=$triCtxCount cont=$contCount vocab=${allWords.size}"

    private fun triSlice(p2: Int, p1: Int): Pair<Int, Int>? {
        val id = triLookup(p2.toLong().shl(32) or (p1.toLong() and 0xffffffffL)) ?: return null
        return triOff[id] to triLen[id]
    }

    private fun triLookup(code: Long): Int? {
        val cap = triVals.size
        var h = (mix(code) and (cap - 1).toLong()).toInt()
        while (true) {
            val cand = triVals[h]
            if (cand == -1) return null
            if (triKeys[h] == code) return cand
            h = (h + 1) and (cap - 1)
        }
    }

    companion object {
        /** SplitMix64-style finaliser — deterministic, well-distributed for open addressing. */
        private fun mix(x0: Long): Long {
            var h = x0
            h = (h xor (h ushr 30)) * -4654470214887392327L
            h = (h xor (h ushr 27)) * -7745228468354336629L
            return h xor (h ushr 31)
        }
        /** DOM convenience path — delegates to the streaming builder so there is ONE parse. */
        fun fromString(json: String): PgenPrimer =
            ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)).use { fromStream(it) }

        fun empty(): PgenPrimer {
            val prim = fromString("{\"uni\":{},\"bi\":{},\"tri\":{}}")
            return prim
        }

        /** Streaming parser for full-size packs. Builds the compact int-indexed structure with
         *  a single vocab assignment pass; build buffers are discarded before the live structure
         *  is returned. UTF-8 safe (Polish diacritics). */
        fun fromStream(stream: InputStream): PgenPrimer {
            val allWordsList = ArrayList<String>(262144)
            val wordId = HashMap<String, Int>(524288)
            fun id(s: String): Int = wordId.getOrPut(s) { allWordsList.size.also { allWordsList.add(s) } }

            val uniTmp = HashMap<Int, Float>(262144)          // wordId -> unigram prob
            val predictedTmp = HashSet<Int>(262144)           // wordIds that are predictable
            val biW = HashMap<Int, ArrayList<Int>>(65536)     // id(prev1) -> continuation ids
            val biP = HashMap<Int, ArrayList<Float>>(65536)
            val triPairs = ArrayList<Long>(438000)            // per tri context: code
            val triW = ArrayList<ArrayList<Int>>(438000)
            val triP = ArrayList<ArrayList<Float>>(438000)

            JsonReader(stream.bufferedReader(Charsets.UTF_8)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "uni" -> {
                            r.beginObject()
                            while (r.hasNext()) { val w = id(r.nextName()); uniTmp[w] = r.nextDouble().toFloat(); predictedTmp.add(w) }
                            r.endObject()
                        }
                        "bi" -> {
                            r.beginObject()
                            while (r.hasNext()) {
                                val p1 = id(r.nextName())
                                val ws = ArrayList<Int>(8); val ps = ArrayList<Float>(8)
                                biW[p1] = ws; biP[p1] = ps
                                r.beginObject()
                                while (r.hasNext()) { val wid = id(r.nextName()); ws.add(wid); ps.add(r.nextDouble().toFloat()); predictedTmp.add(wid) }
                                r.endObject()
                            }
                            r.endObject()
                        }
                        "tri" -> {
                            r.beginObject()
                            while (r.hasNext()) {
                                val ctx = r.nextName()
                                val sp = ctx.indexOf(' ')
                                val p2 = id(if (sp < 0) ctx else ctx.substring(0, sp))
                                val p1 = id(if (sp < 0) ctx else ctx.substring(sp + 1))
                                triPairs.add(p2.toLong().shl(32) or (p1.toLong() and 0xffffffffL))
                                val ws = ArrayList<Int>(8); val ps = ArrayList<Float>(8)
                                triW.add(ws); triP.add(ps)
                                r.beginObject()
                                while (r.hasNext()) { val wid = id(r.nextName()); ws.add(wid); ps.add(r.nextDouble().toFloat()); predictedTmp.add(wid) }
                                r.endObject()
                            }
                            r.endObject()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            }

            val V = allWordsList.size
            val uniProb = FloatArray(V) { Float.NaN }
            for ((w, p) in uniTmp) uniProb[w] = p
            val predicted = BooleanArray(V)
            for (w in predictedTmp) predicted[w] = true

            // ---- flat continuation pool + bi/tri slices ----
            val contWord = ArrayList<Int>(966105)
            val contProb = ArrayList<Float>(966105)
            val biOff = IntArray(V) { -1 }
            val biLen = IntArray(V)
            for ((p1, ws) in biW) {
                val ps = biP.getValue(p1)
                biOff[p1] = contWord.size; biLen[p1] = ws.size
                for (i in ws.indices) { contWord.add(ws[i]); contProb.add(ps[i]) }
            }
            val nTri = triW.size
            val triOff = IntArray(nTri)
            val triLen = IntArray(nTri)
            for (i in 0 until nTri) {
                val ws = triW[i]; val ps = triP[i]
                triOff[i] = contWord.size; triLen[i] = ws.size
                for (j in ws.indices) { contWord.add(ws[j]); contProb.add(ps[j]) }
            }
            val contWordArr = contWord.toIntArray()
            val contProbArr = contProb.toFloatArray()

            // ---- tri context lookup: open-addressed long code -> triCtxId ----
            var cap = 1
            while (cap < nTri * 2) cap = cap shl 1
            val triKeys = LongArray(cap)
            val triVals = IntArray(cap) { -1 }
            for (i in 0 until nTri) {
                var h = (mix(triPairs[i]) and (cap - 1).toLong()).toInt()
                while (triVals[h] != -1) h = (h + 1) and (cap - 1)
                triKeys[h] = triPairs[i]; triVals[h] = i
            }

            var biCtx = 0
            for (o in biOff) if (o >= 0) biCtx++

            return PgenPrimer(
                allWords = allWordsList.toTypedArray(),
                wordId = wordId,
                uniProb = uniProb,
                predicted = predicted,
                biOff = biOff, biLen = biLen,
                contWord = contWordArr, contProb = contProbArr,
                triOff = triOff, triLen = triLen,
                triKeys = triKeys, triVals = triVals,
                uniCount = uniTmp.size,
                biCtxCount = biCtx,
                triCtxCount = nTri,
                contCount = contWordArr.size
            )
        }

        fun fromFile(file: File): PgenPrimer = file.inputStream().use { fromStream(it) }
    }
}