package com.jarvis.tools

import android.content.Context
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus

/**
 * Инструмент «Фонарик»: включает/выключает вспышку камеры.
 *
 * risk = SAFE, работает только локально, системных permissions не требует
 * (управление torch-modoem не требует CAMERA на Android).
 */
class FlashlightTool(private val context: Context) : Tool {

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    override val definition = ToolDefinition(
        id = "flashlight",
        name = "Фонарик",
        description = "Включить или выключить фонарик устройства",
        parameters = listOf(
            ToolParameter(
                name = "action",
                description = "on — включить, off — выключить",
                type = ParameterType.ENUM,
                required = true,
                allowedValues = listOf("on", "off")
            )
        ),
        riskLevel = RiskLevel.SAFE,
        onlineRequirement = OnlineRequirement.LOCAL_ONLY
    )

    override fun execute(call: ToolCall): ToolResult = try {
        val action = call.arguments["action"] as? String
        when (action) {
            "on" -> setTorch(call.requestId, true)
            "off" -> setTorch(call.requestId, false)
            else -> ToolResult(
                requestId = call.requestId,
                toolId = definition.id,
                status = ToolResultStatus.AMBIGUOUS,
                message = "Не указано действие: on или off"
            )
        }
    } catch (t: Throwable) {
        ToolResult(
            requestId = call.requestId,
            toolId = definition.id,
            status = ToolResultStatus.FAILED,
            message = "Не удалось переключить фонарик: ${t.message ?: t.javaClass.simpleName}"
        )
    }

    private fun setTorch(requestId: String, wantOn: Boolean): ToolResult {
        val cameraId = findBackCamera() ?: return ToolResult(
            requestId = requestId,
            toolId = definition.id,
            status = ToolResultStatus.UNAVAILABLE,
            message = "На устройстве нет камеры с фонариком"
        )

        cameraManager.setTorchMode(cameraId, wantOn)

        return ToolResult(
            requestId = requestId,
            toolId = definition.id,
            status = ToolResultStatus.SUCCESS,
            message = if (wantOn) "Включён." else "Выключен.",
            data = mapOf("on" to wantOn)
        )
    }

    /** Выбирает камеру, смотрящую назад (обычно имеет вспышку). */
    private fun findBackCamera(): String? = runCatching {
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrNull()
}