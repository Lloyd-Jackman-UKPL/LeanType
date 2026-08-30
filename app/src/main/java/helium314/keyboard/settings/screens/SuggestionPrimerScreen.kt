/*
 * Copyright (C) 2026 LeanBitLab
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Suggestion Primer Packs — manage loadable next-word primer libraries (per locale).
 * Packs are static n-gram data files consumed by the candidate producer/ranker
 * (PgenPrimerDictionary). They contain NO AI model and run nothing in the background;
 * the suggestion engine stays the classic AOSP n-gram path.
 *
 * GUARDRAIL: this screen only manages data files and preferences. It never touches
 * the composing/gesture buffer and never writes text.
 */
package helium314.keyboard.settings.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.DictionaryFacilitatorImpl
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.dialogs.InfoDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.io.File

/** Where sideloaded/imported primer packs live. Must match DictionaryFacilitatorImpl.loadPgenPack. */
private fun primerDir(context: Context): File =
    File(context.getExternalFilesDir(null) ?: context.filesDir, "primer")

/** Extract the locale tag from a pack filename: pgen_en_gb.json / pgen_pl.json / pgen_en.json. */
private fun tagFromName(name: String): String? {
    val m = Regex("^pgen_([a-z]{2,3})(?:[_\\-]([A-Za-z]{2,8}))?\\.(?:json|txt)$", RegexOption.IGNORE_CASE).find(name)
        ?: return null
    val lang = m.groupValues[1].lowercase()
    val region = m.groupValues[2]
    return if (region.isEmpty()) lang else "${lang}_${region.lowercase().replaceFirstChar { it.uppercase() }}"
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576f)
    bytes >= 1 shl 10 -> "%d KB".format(bytes / 1024)
    else -> "$bytes B"
}

@Composable
fun SuggestionPrimerScreen(onClickBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.prefs()
    var enabled by remember { mutableStateOf(prefs.getBoolean("pref_pgen_primer_enabled", false)) }
    var packs by remember { mutableStateOf(listOf<File>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    // SAF import launcher (reuses the app-wide document intent: json/octet-stream allowed)
    val pickPack = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri: Uri = result.data?.data ?: return@rememberLauncherForActivityResult
        try {
            val cr = context.contentResolver
            val srcName = cr.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) null
                else c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } ?: "pgen.json"
            val tag = tagFromName(srcName)
            if (tag == null) {
                importError = "Filename must identify the language, e.g. pgen_en_gb.json or pgen_pl.json (got \"$srcName\")"
                return@rememberLauncherForActivityResult
            }
            val dir = primerDir(context).apply { mkdirs() }
            val dst = File(dir, "pgen_$tag.json")
            cr.openInputStream(uri)?.use { ins -> dst.outputStream().use { ins.copyTo(it) } }
                ?: run { importError = "Could not read the selected file"; return@rememberLauncherForActivityResult }
            Log.i("SuggestionPrimers", "imported ${dst.name} (${dst.length()} bytes)")
            packs = dir.listFiles { f -> f.isFile && f.name.startsWith("pgen_") }?.sortedBy { it.name } ?: listOf()
            message = "Imported ${dst.name}. Reloading suggestions…"
            context.sendBroadcast(Intent(DictionaryFacilitatorImpl.ACTION_PRIMER_PACKS_CHANGED)
                .setPackage(context.packageName))
        } catch (e: Exception) {
            Log.e("SuggestionPrimers", "import failed", e)
            importError = "Import failed: ${e.message}"
        }
    }

    fun refresh() {
        val dir = primerDir(context)
        packs = dir.listFiles { f -> f.isFile && f.name.startsWith("pgen_") }?.sortedBy { it.name } ?: listOf()
    }
    remember { refresh(); true }

    Box(modifier = Modifier.fillMaxSize()) {
        SearchScreen(
            onClickBack = onClickBack,
            title = {
                Text(
                    text = "Suggestion Primer Packs",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            },
            filteredItems = { _ -> emptyList<Pair<String, String>>() },
            itemContent = { },
            content = {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("What are these?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Static word-pattern libraries that prime next-word suggestions for a language. " +
                                "They hold no AI model and never type for you — words are only ever added by tapping them. " +
                                "The engine falls back to the built-in dictionary when no pack matches your language.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Use primer packs", fontWeight = FontWeight.Medium)
                                Switch(checked = enabled, onCheckedChange = { on ->
                                    enabled = on
                                    prefs.edit { putBoolean("pref_pgen_primer_enabled", on) }
                                    context.sendBroadcast(Intent(DictionaryFacilitatorImpl.ACTION_PRIMER_PACKS_CHANGED)
                                        .setPackage(context.packageName))
                                })
                            }
                        }
                    }

                    Button(onClick = { pickPack.launch(
                        android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
                            .setType("*/*")
                            .putExtra(android.content.Intent.EXTRA_MIME_TYPES,
                                arrayOf("application/json", "text/*", "application/octet-stream"))
                    ) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Import pack file")
                    }

                    if (packs.isEmpty()) {
                        Text(
                            "No packs installed. Import a pgen_<language>.json file — " +
                            "suggestions then use it whenever that language is active.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    for (f in packs) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(f.name, fontWeight = FontWeight.Medium)
                                    Text(humanSize(f.length()), style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = {
                                    f.delete()
                                    refresh()
                                    context.sendBroadcast(Intent(DictionaryFacilitatorImpl.ACTION_PRIMER_PACKS_CHANGED)
                                        .setPackage(context.packageName))
                                }) {
                                    Icon(
                                        painter = androidx.compose.ui.res.painterResource(R.drawable.ic_bin),
                                        contentDescription = "delete"
                                    )
                                }
                            }
                        }
                    }

                    var clearArmed by remember { mutableStateOf(false) }
                    Button(
                        onClick = {
                            if (!clearArmed) {
                                clearArmed = true
                            } else {
                                context.sendBroadcast(Intent(DictionaryFacilitatorImpl.ACTION_PRIMER_CLEAR_LEARNED)
                                    .setPackage(context.packageName))
                                message = "Learned words cleared."
                                clearArmed = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (clearArmed) "Tap again to confirm clearing learned words"
                             else "Clear learned words")
                    }

                    Text(
                        "Changes apply immediately when the keyboard is in use. " +
                        "Load details appear in the debug log under PgenPrimer.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        )
    }

    message?.let { InfoDialog(it) { message = null } }
    importError?.let { InfoDialog(it) { importError = null } }
}
