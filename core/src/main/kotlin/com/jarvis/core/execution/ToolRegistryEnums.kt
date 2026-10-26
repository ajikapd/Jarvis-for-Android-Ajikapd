package com.jarvis.core.execution

/**
 * Stage 8F: источник Tool в едином реестре.
 * Native / Plugin / MCP — без отдельной execution architecture на MCP.
 */
enum class ToolProviderSource {
    NATIVE,
    PLUGIN,
    MCP,
}

/**
 * Stage 8F: локальность выполнения (не = autonomy / risk).
 */
enum class ToolExecutionLocality {
    LOCAL,
    REMOTE,
    HYBRID,
}

/**
 * Stage 8F: доступность Tool (декларативно; Registry не запрашивает permissions).
 * Согласовано по смыслу с CapabilityAvailability, без зависимости common←capability.
 */
enum class ToolAvailability {
    ALWAYS,
    NEEDS_PERMISSION,
    NEEDS_ONLINE,
    NEEDS_ACCESSIBILITY,
    CONDITIONAL,
    UNAVAILABLE,
}
