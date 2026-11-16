package com.jarvis.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import com.jarvis.core.nlp.Transliterator

/** Поиск контакта по имени → номер телефона. */
object ContactResolver {

    data class Match(val name: String, val number: String, val score: Int = 100)

    fun resolvePhone(context: Context, query: String): Match? =
        resolveAll(context, query).firstOrNull()

    /** Все достаточно похожие контакты (для уточнения при неоднозначности). */
    fun resolveAll(context: Context, query: String, limit: Int = 5): List<Match> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        if (q.any { it.isDigit() } && q.filter { it.isDigit() || it == '+' }.length >= 5 &&
            q.count { it.isLetter() } == 0
        ) {
            return listOf(Match(q, q.filter { it.isDigit() || it == '+' }, 100))
        }
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        val qNorm = Transliterator.compareKey(q)
        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null,
            null,
            null,
        ) ?: return emptyList()

        val scored = LinkedHashMap<String, Match>() // key = name|number
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(0).orEmpty()
                val number = it.getString(1).orEmpty().filter { ch -> ch.isDigit() || ch == '+' }
                if (name.isBlank() || number.isBlank()) continue
                val s = score(q, qNorm, name)
                if (s < 50) continue
                val key = "${name.lowercase()}|$number"
                val prev = scored[key]
                if (prev == null || s > prev.score) {
                    scored[key] = Match(name, number, s)
                }
            }
        }
        val list = scored.values.sortedByDescending { it.score }
        if (list.isEmpty()) return emptyList()
        val best = list.first().score
        // Неоднозначность: все в пределах 15 очков от лучшего
        return list.filter { best - it.score <= 15 }.take(limit)
    }

    private fun score(raw: String, rawNorm: String, name: String): Int {
        val n = name.lowercase()
        val r = raw.lowercase()
        val nNorm = Transliterator.compareKey(name)
        return when {
            n == r -> 100
            nNorm == rawNorm && rawNorm.isNotEmpty() -> 95
            n.startsWith(r) -> 85
            n.contains(r) -> 75
            nNorm.contains(rawNorm) && rawNorm.length >= 3 -> 65
            else -> 0
        }
    }
}
