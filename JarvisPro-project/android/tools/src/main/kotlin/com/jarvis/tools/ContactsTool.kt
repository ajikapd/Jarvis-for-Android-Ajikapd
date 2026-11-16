package com.jarvis.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.core.nlp.Transliterator

class ContactsTool(private val context: Context) : Tool {

    override val definition = ToolDefinition(
        id = "contacts.search",
        name = "Контакты",
        description = "Ищет контакт по имени и возвращает номер телефона",
        parameters = listOf(
            ToolParameter("query", "Имя контакта", ParameterType.STRING, required = true),
        ),
        riskLevel = RiskLevel.LOW,
        requiredPermissions = listOf(Manifest.permission.READ_CONTACTS),
        onlineRequirement = OnlineRequirement.LOCAL_ONLY,
    )

    override fun execute(call: ToolCall): ToolResult {
        val query = call.arguments["query"]?.toString()?.trim().orEmpty()
        if (query.isBlank()) {
            return ToolResult(call.requestId, definition.id, ToolResultStatus.AMBIGUOUS, "Кого искать?")
        }
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult(
                call.requestId,
                definition.id,
                ToolResultStatus.DENIED,
                "Нет разрешения на чтение контактов.",
            )
        }
        val match = ContactResolver.resolvePhone(context, query)
        if (match != null) {
            return ToolResult(
                call.requestId,
                definition.id,
                ToolResultStatus.SUCCESS,
                "«${match.name}»: ${match.number}",
                mapOf("name" to match.name, "number" to match.number),
            )
        }
        // Broader list of name matches without requiring phone
        val names = searchNames(query).take(5)
        return if (names.isEmpty()) {
            ToolResult(call.requestId, definition.id, ToolResultStatus.FAILED, "Контакт «$query» не найден.")
        } else {
            ToolResult(
                call.requestId,
                definition.id,
                ToolResultStatus.SUCCESS,
                "Нашёл: ${names.joinToString(", ")}",
                mapOf("names" to names.joinToString("|")),
            )
        }
    }

    private fun searchNames(query: String): List<String> {
        val qNorm = Transliterator.compareKey(query)
        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
            null,
            null,
            null,
        ) ?: return emptyList()
        val out = LinkedHashSet<String>()
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(0).orEmpty()
                if (name.isBlank()) continue
                val n = name.lowercase()
                val nNorm = Transliterator.compareKey(name)
                if (n.contains(query.lowercase()) || nNorm.contains(qNorm)) out += name
            }
        }
        return out.toList()
    }
}
