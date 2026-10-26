package com.jarvis.screencontrol

import android.graphics.Rect

/** Снимок узла дерева Accessibility для поиска и кликов. */
data class UiNode(
    val text: String,
    val contentDescription: String,
    val viewId: String,
    val className: String,
    val packageName: String,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val focused: Boolean,
    val enabled: Boolean,
    val depth: Int,
) {
    val searchable: String
        get() = listOf(text, contentDescription, viewId.substringAfterLast('/'))
            .filter { it.isNotBlank() }
            .joinToString(" ")
}
