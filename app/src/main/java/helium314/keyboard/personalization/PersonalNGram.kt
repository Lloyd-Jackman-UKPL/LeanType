/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 *
 * Lightweight personal n-gram counter for the angle-2 interpolation.
 * Maintains per-context counts (durable, slow-decaying = identity) + last-seen times
 * (fast recency bonus). Pure data — it never commits or writes text.
 *
 * PERSISTENCE (wired 22 Aug 2026): counts survive process death. Android kills IME
 * processes aggressively (Doze, Samsung UWB/memory pressure), so the map is written
 * through to disk: debounced save every SAVE_THRESHOLD records + synchronous save on
 * flush(). Worst case on a hard kill loses fewer than SAVE_THRESHOLD words.
 *
 * Storage: one JSON file in the app's internal dir (private, survives updates,
 * removed on uninstall). Bounded to MAX_ENTRIES total word-entries; overflow prunes
 * lowest-count entries first so long-term growth is capped.
 */
package helium314.keyboard.personalization

import android.util.JsonReader
import android.util.JsonWriter
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.min

class PersonalNGram(private val storeFile: File?) {
    data class DurableEntry(var count: Int, var lastSeenMs: Long)

    private val durable = HashMap<String, HashMap<String, DurableEntry>>()
    private val saveExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var dirtyCount = 0

    init {
        if (storeFile != null && storeFile.isFile) {
            try {
                FileInputStream(storeFile).use { loadFrom(it) }
                Log.i(TAG, "personal counts restored: ${totalEntries()} entries from ${storeFile.name}")
            } catch (e: Exception) {
                Log.e(TAG, "personal counts restore failed — starting cold", e)
                durable.clear()
            }
        }
    }

    @Synchronized
    fun record(word: String, prev1: String?, prev2: String?, nowMs: Long) {
        if (prev1 != null) addToKey(prev1, word, nowMs)
        if (prev2 != null && prev1 != null) addToKey("$prev1 $prev2", word, nowMs)
        dirtyCount++
        if (dirtyCount >= SAVE_THRESHOLD) scheduleSave()
    }

    /** Immediate save (called on dictionary close / explicit clear paths). */
    fun flush() {
        val f = storeFile ?: return
        val snapshot = synchronized(this) { snapshotLocked() }
        dirtyCount = 0
        try {
            persist(f, snapshot) // synchronous: close path is rare and must not lose data
        } catch (e: Exception) {
            Log.e(TAG, "flush failed", e)
        }
    }

    /** Wipe memory + storage ("Clear learned words"). */
    @Synchronized
    fun clear() {
        durable.clear()
        dirtyCount = 0
        try { storeFile?.delete() } catch (_: Exception) {}
        Log.i(TAG, "personal counts cleared")
    }

    fun wordsInContext(triKey: String?, prev1: String): Set<String> {
        val s = LinkedHashSet<String>()
        synchronized(this) {
            if (triKey != null) durable[triKey]?.keys?.let { s.addAll(it) }
            durable[prev1]?.keys?.let { s.addAll(it) }
        }
        return s
    }

    /** Effective personal count for `word` under context, with two-timescale recency. */
    fun effectiveCount(triKey: String?, prev1: String, word: String, nowMs: Long): Float {
        val entry = synchronized(this) {
            durable[prev1]?.get(word) ?: durable[triKey]?.get(word) ?: return 0f
        }
        val daysSlow = (nowMs - entry.lastSeenMs) / 86_400_000.0
        val durableTerm = entry.count.toFloat() *
            exp(-PgenPrimerDictionary.LAMBDA_SLOW.toDouble() * min(daysSlow, 365.0)).toFloat()
        val bonus = exp(-PgenPrimerDictionary.LAMBDA_FAST.toDouble() * min(daysSlow, 30.0)).toFloat()
        return durableTerm * (1f + PgenPrimerDictionary.FAST_BONUS_MAX * bonus)
    }

    fun contains(word: String): Boolean =
        synchronized(this) { durable.values.any { it.containsKey(word) } }

    // ---- internals ----

    private fun addToKey(ctx: String, word: String, nowMs: Long) {
        if (ctx.isEmpty() || word.isEmpty()) return
        val w = word.lowercase()
        val ctxMap = durable.getOrPut(ctx) { HashMap() }
        val e = ctxMap[w]
        if (e != null) { e.count++; e.lastSeenMs = nowMs }
        else ctxMap[w] = DurableEntry(1, nowMs)
    }

    private fun totalEntries(): Int = durable.values.sumOf { it.size }

    private fun scheduleSave() {
        val f = storeFile ?: return
        val snapshot = synchronized(this) { snapshotLocked() }
        dirtyCount = 0
        saveExecutor.execute {
            try { persist(f, snapshot) } catch (e: Exception) { Log.e(TAG, "save failed", e) }
        }
    }

    /** Deep copy safe to hand to the save thread. */
    private fun snapshotLocked(): HashMap<String, HashMap<String, DurableEntry>> {
        val out = HashMap<String, HashMap<String, DurableEntry>>(durable.size * 2)
        for ((k, v) in durable) out[k] = HashMap(v.mapValues { it.value.copy() })
        return out
    }

    /** Serialize + bounded prune. Runs on the save thread (or caller on flush). */
    private fun persist(f: File, data: HashMap<String, HashMap<String, DurableEntry>>) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { fos ->
            JsonWriter(fos.writer()).use { jw ->
                jw.beginObject()
                for ((ctx, words) in data) {
                    jw.name(ctx).beginObject()
                    for ((w, e) in words) {
                        jw.name(w).beginObject()
                        jw.name("c").value(e.count)
                        jw.name("t").value(e.lastSeenMs)
                        jw.endObject()
                    }
                    jw.endObject()
                }
                jw.endObject()
            }
        }
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) Log.w(TAG, "could not replace ${f.name}")
        }
    }

    private fun loadFrom(ins: FileInputStream) {
        JsonReader(ins.reader()).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                val ctx = r.nextName()
                val ctxMap = durable.getOrPut(ctx) { HashMap() }
                r.beginObject()
                while (r.hasNext()) {
                    val w = r.nextName()
                    var c = 1; var t = 0L
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "c" -> c = r.nextInt()
                            "t" -> t = r.nextLong()
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    ctxMap[w] = DurableEntry(c, t)
                }
                r.endObject()
            }
            r.endObject()
        }
        pruneIfNeeded(durable)
    }

    /** Cap total stored word-entries; evict lowest-count first (identity keeps high counts). */
    private fun pruneIfNeeded(data: HashMap<String, HashMap<String, DurableEntry>>) {
        val total = data.values.sumOf { it.size }
        if (total <= MAX_ENTRIES) return
        val flat = ArrayList<Triple<String, String, Int>>(total)
        for ((ctx, words) in data) for ((w, e) in words) flat.add(Triple(ctx, w, e.count))
        Collections.sort(flat) { a, b -> a.third.compareTo(b.third) } // ascending count
        val toRemove = HashSet<Pair<String, String>>(total - MAX_ENTRIES)
        for (i in 0 until total - MAX_ENTRIES) {
            val t = flat[i]; toRemove.add(t.first to t.second)
        }
        for ((ctx, words) in data) {
            words.keys.removeAll { k -> toRemove.contains(ctx to k) }
        }
        Log.i(TAG, "pruned ${toRemove.size} low-count entries (cap $MAX_ENTRIES)")
    }

    companion object {
        const val TAG = "PersonalNGram"
        const val SAVE_THRESHOLD = 20      // max records at risk on a hard process kill
        const val MAX_ENTRIES = 20_000     // total word-entries across all contexts

        fun countsFile(filesDir: File): File = File(filesDir, "personal_ngram.json")
    }
}
