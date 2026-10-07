package com.songsync.app.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EndpointInfoTest {

    @Test
    fun `round trips and keeps pipes in names`() {
        val info = EndpointInfo(name = "Ana's | Pixel", sessionId = "k3x9qa")
        assertThat(EndpointInfo.decode(info.encode())).isEqualTo(info)
    }

    @Test
    fun `stays within Nearby's endpoint info limit`() {
        val info = EndpointInfo(name = "🎵".repeat(40), sessionId = "k3x9qa")
        assertThat(info.encode().size).isAtMost(131)
    }

    @Test
    fun `ignores other apps and garbage`() {
        assertThat(EndpointInfo.decode(null)).isNull()
        assertThat(EndpointInfo.decode("XX|1|abc|name".toByteArray())).isNull()
        assertThat(EndpointInfo.decode("SS|one|abc|name".toByteArray())).isNull()
        assertThat(EndpointInfo.decode(byteArrayOf(1, 2, 3))).isNull()
    }
}
