package com.jarvis.brain

import android.content.Context
import com.jarvis.core.skills.SkillManifest
import com.jarvis.core.skills.SkillManifestParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Загружает манифесты скиллов из assets/skills и из внешнего каталога
 * (пользовательские/плагинные скиллы — будущее).
 *
 * Кэширует тексты для эмбеддингов на диске (JARVIS_BRAIN_ARCHITECTURE §3).
 */
class SkillManifestLoader(private val context: Context) {

    private val cacheDir = File(context.filesDir, "skill_cache")

    fun loadAll(): List<SkillManifest> {
        cacheDir.mkdirs()
        val manifests = mutableListOf<SkillManifest>()

        // Bundled skills from assets/skills/
        val assetSkills = runCatching { context.assets.list(SkillsDir) ?: emptyArray() }
            .getOrDefault(emptyArray())

        for (name in assetSkills.filter { it.endsWith(".json") }) {
            runCatching {
                context.assets.open("$SkillsDir/$name").bufferedReader().use { reader ->
                    val json = reader.readText()
                    val manifest = SkillManifestParser.parse(json)
                    manifests += manifest
                    cacheManifestText(name, manifest.searchableText)
                }
            }
        }

        // External user-defined skills from files dir
        val extDir = File(context.getExternalFilesDir(null), "skills")
        if (extDir.isDirectory) {
            extDir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
                runCatching {
                    val json = file.readText()
                    val manifest = SkillManifestParser.parse(json)
                    manifests += manifest
                    cacheManifestText(file.name, manifest.searchableText)
                }
            }
        }

        return manifests
    }

    private fun cacheManifestText(fileName: String, text: String) {
        runCatching {
            File(cacheDir, "$fileName.cache").writeText(text)
        }
    }

    fun loadManifestSchema(): JSONArray {
        val required = SkillManifest::class.java.declaredFields
            .map { it.name }
        val json = JSONArray()
        // Return a simple representation for the Brain LLM system prompt
        for (m in loadAll()) {
            val skillJson = JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("description", m.description)
                put("examples", JSONArray(m.examples))
            }
            json.put(skillJson)
        }
        return json
    }

    companion object {
        private const val SkillsDir = "skills"
    }
}