package com.jarvis.core.vision

/**
 * Stage 24: Vision Provider (screenshot / camera → context → planner).
 */
enum class VisionProviderKind { LOCAL, CLOUD }

enum class VisionAvailability { AVAILABLE, UNAVAILABLE, DEGRADED }

data class VisionRequest(
    val requestId: String,
    val imageJpeg: ByteArray,
    val prompt: String = "Describe briefly",
    val wantStructured: Boolean = false,
) {
    init {
        require(requestId.isNotBlank())
        require(imageJpeg.size <= MAX_BYTES) { "image too large" }
        require(prompt.length <= 500)
    }

    companion object {
        const val MAX_BYTES = 5_000_000
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VisionRequest) return false
        return requestId == other.requestId && imageJpeg.contentEquals(other.imageJpeg) &&
            prompt == other.prompt
    }

    override fun hashCode(): Int = requestId.hashCode()
}

enum class VisionStatus { SUCCESS, FAILURE, UNAVAILABLE }

data class VisionResult(
    val requestId: String,
    val providerId: String,
    val status: VisionStatus,
    val description: String? = null,
    val labels: List<String> = emptyList(),
    val error: String? = null,
)

interface VisionProvider {
    val id: String
    val kind: VisionProviderKind
    fun availability(): VisionAvailability
    fun analyze(request: VisionRequest): VisionResult
}

class VisionRouter(
    private val local: VisionProvider,
    private val cloud: VisionProvider? = null,
) {
    fun select(isOnline: Boolean, preferCloud: Boolean = true): VisionProvider {
        if (!isOnline || !preferCloud) return local
        val c = cloud
        return if (c != null && c.availability() == VisionAvailability.AVAILABLE) c else local
    }
}

class LocalVisionStub(
    override val id: String = "vision.local",
    private val ready: Boolean = true,
) : VisionProvider {
    override val kind = VisionProviderKind.LOCAL
    override fun availability() =
        if (ready) VisionAvailability.AVAILABLE else VisionAvailability.UNAVAILABLE

    override fun analyze(request: VisionRequest): VisionResult {
        if (!ready || request.imageJpeg.isEmpty()) {
            return VisionResult(request.requestId, id, VisionStatus.UNAVAILABLE, error = "not_ready")
        }
        return VisionResult(
            requestId = request.requestId,
            providerId = id,
            status = VisionStatus.SUCCESS,
            description = "local-vision:${request.prompt.take(40)}",
            labels = listOf("stub", "local"),
        )
    }
}

class CloudVisionStub(
    override val id: String = "vision.cloud",
    private val configured: Boolean = false,
) : VisionProvider {
    override val kind = VisionProviderKind.CLOUD
    override fun availability() =
        if (configured) VisionAvailability.AVAILABLE else VisionAvailability.UNAVAILABLE

    override fun analyze(request: VisionRequest): VisionResult {
        if (!configured) {
            return VisionResult(request.requestId, id, VisionStatus.UNAVAILABLE, error = "no_cloud")
        }
        return VisionResult(
            requestId = request.requestId,
            providerId = id,
            status = VisionStatus.SUCCESS,
            description = "cloud-vision:${request.prompt.take(40)}",
            labels = listOf("stub", "cloud"),
        )
    }
}
