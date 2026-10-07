package com.songsync.app.data

import com.google.common.truth.Truth.assertThat
import com.songsync.app.data.model.TrackSource
import com.songsync.app.data.source.Des
import com.songsync.app.data.source.HtmlEntities
import com.songsync.app.data.source.JioSaavnCrypto
import com.songsync.app.data.source.JioSaavnSource
import org.junit.Test

class JioSaavnTest {

    @Test
    fun `DES matches the textbook test vector`() {
        val key = "133457799BBCDFF1".hexToByteArray()
        val plain = "0123456789ABCDEF".hexToByteArray()
        val cipher = Des.ecb(plain, Des.subkeys(key))
        assertThat(cipher.toHexString(HexFormat.UpperCase)).isEqualTo("85E813540F0AB405")
        assertThat(Des.ecb(cipher, Des.subkeys(key).reversedArray())).isEqualTo(plain)
    }

    @Test
    fun `decrypts a real encrypted_media_url`() {
        // Captured from the live search API; decrypted independently with OpenSSL.
        val encrypted = "ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDy3oAkJDMbq8VkXOZU65DcFdrDhsTWIRT7u/8vMld9DHecVMFCBmD5/Rw7tS9a8Gtq"
        assertThat(JioSaavnCrypto.decryptMediaUrl(encrypted))
            .isEqualTo("https://aac.saavncdn.com/248/46944eb7b4b31f5b0abf5eb2e1be2d2a_96.mp4")
    }

    @Test
    fun `round trips and survives whitespace in the payload`() {
        val url = "https://aac.saavncdn.com/123/abc&q=1_96.mp4"
        val encrypted = JioSaavnCrypto.encryptForTest(url)
        assertThat(encrypted).isEqualTo("ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDyQd+aqn5Yzr2TM4GxoNPjsoPzFaL/aK97") // OpenSSL
        assertThat(JioSaavnCrypto.decryptMediaUrl(encrypted.chunked(10).joinToString("\n"))).isEqualTo(url)
    }

    @Test
    fun `swaps the bitrate suffix only at the end`() {
        val url = "https://aac.saavncdn.com/248/x_96_96.mp4"
        assertThat(JioSaavnCrypto.withBitrate(url, 320)).isEqualTo("https://aac.saavncdn.com/248/x_96_320.mp4")
        assertThat(JioSaavnCrypto.withBitrate("https://a/b_160.mp4", 96)).isEqualTo("https://a/b_96.mp4")
    }

    @Test
    fun `decodes the HTML entities found in titles`() {
        assertThat(HtmlEntities.decode("Tum Hi Ho (From &quot;Aashiqui 2&quot;)")).isEqualTo("Tum Hi Ho (From \"Aashiqui 2\")")
        assertThat(HtmlEntities.decode("Rock &amp; Roll &#039;99 &#x2764; &bogus;")).isEqualTo("Rock & Roll '99 ❤ &bogus;")
        assertThat(HtmlEntities.decode("plain")).isEqualTo("plain")
    }

    @Test
    fun `parses search results and skips albums`() {
        val body = """
            {"total":3,"start":1,"results":[
              {"id":"BeXBcbVK","song":"Believer","primary_artists":"Imagine Dragons","singers":"",
               "image":"https://c.saavncdn.com/248/Evolve-English-2018-20260605220036-150x150.jpg",
               "320kbps":"true","duration":"204","encrypted_media_url":"ID2ieOjCrwfg"},
              {"id":"ALB1","title":"Some Album","encrypted_media_url":""},
              {"id":"x2","title":"Tum Hi Ho &quot;Live&quot;","primary_artists":"","singers":"Arijit Singh",
               "image":"https://c.saavncdn.com/1/a-50x50.png","320kbps":"false","duration":null,
               "encrypted_media_url":"abc"}
            ]}
        """.trimIndent()
        val tracks = JioSaavnSource.parseSearch(body)
        assertThat(tracks).hasSize(2)
        with(tracks[0]) {
            assertThat(source).isEqualTo(TrackSource.JIOSAAVN)
            assertThat(title).isEqualTo("Believer")
            assertThat(artist).isEqualTo("Imagine Dragons")
            assertThat(artworkUrl).isEqualTo("https://c.saavncdn.com/248/Evolve-English-2018-20260605220036-500x500.jpg")
            assertThat(durationMs).isEqualTo(204_000)
            assertThat(highQualityAvailable).isTrue()
        }
        with(tracks[1]) {
            assertThat(title).isEqualTo("Tum Hi Ho \"Live\"")
            assertThat(artist).isEqualTo("Arijit Singh")
            assertThat(durationMs).isEqualTo(0)
            assertThat(highQualityAvailable).isFalse()
        }
    }

    @Test
    fun `tolerates unexpected payloads`() {
        assertThat(JioSaavnSource.parseSearch("""{"error":"x"}""")).isEmpty()
        assertThat(JioSaavnSource.parseSearch("""{"results":[1, "two", null]}""")).isEmpty()
    }
}
