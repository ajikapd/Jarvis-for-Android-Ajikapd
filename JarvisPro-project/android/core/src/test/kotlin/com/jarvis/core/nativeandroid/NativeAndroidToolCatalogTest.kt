package com.jarvis.core.nativeandroid

import com.jarvis.core.autonomy.ProposedActionMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAndroidToolCatalogTest {

    @Test
    fun catalogCoversFirstSafe() {
        for (id in ProposedActionMapper.FIRST_SAFE_TOOL_IDS) {
            val spec = NativeAndroidToolCatalog.find(id)
            assertNotNull(id, spec)
            assertEquals(NativeToolExecMode.LIVE_DEVICE_SAFE, spec!!.execMode)
        }
    }

    @Test
    fun readOnlyLiveAllowed() {
        assertTrue(NativeAndroidToolCatalog.liveAllowed("weather"))
        assertTrue(NativeAndroidToolCatalog.liveAllowed("knowledge"))
        assertTrue(NativeAndroidToolCatalog.liveAllowed("clipboard"))
    }

    @Test
    fun sensitiveBlockedFromLive() {
        assertFalse(NativeAndroidToolCatalog.liveAllowed("sms"))
        assertFalse(NativeAndroidToolCatalog.liveAllowed("phone.call"))
        assertFalse(NativeAndroidToolCatalog.liveAllowed("ui.click"))
        assertFalse(NativeAndroidToolCatalog.liveAllowed("files.search"))
        assertEquals(
            NativeToolExecMode.BLOCKED,
            NativeAndroidToolCatalog.find("sms")!!.execMode,
        )
        assertEquals(
            NativeToolExecMode.GATED,
            NativeAndroidToolCatalog.find("files.search")!!.execMode,
        )
    }

    @Test
    fun gatedNotLive() {
        assertFalse(NativeAndroidToolCatalog.liveAllowed("launcher"))
        assertFalse(NativeAndroidToolCatalog.liveAllowed("music"))
        assertEquals(
            NativeToolExecMode.GATED,
            NativeAndroidToolCatalog.find("launcher")!!.execMode,
        )
    }

    @Test
    fun mapperCoversCatalogIds() {
        for (spec in NativeAndroidToolCatalog.ALL) {
            assertNotNull(
                "mapper missing ${spec.toolId}",
                ProposedActionMapper.fromToolId(spec.toolId),
            )
        }
    }

    @Test
    fun sizeIsStable() {
        assertTrue(NativeAndroidToolCatalog.size() >= 20)
    }
}
