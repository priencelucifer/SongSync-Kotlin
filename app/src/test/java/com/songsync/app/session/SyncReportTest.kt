package com.songsync.app.session

import com.google.common.truth.Truth.assertThat
import com.songsync.app.net.AudioFormatInfo
import com.songsync.app.net.DeviceDiag
import org.junit.Test

class SyncReportTest {

    private fun diag(route: String = "SPEAKER") = DeviceDiag(
        model = "Google Pixel 8", sdk = 35, appVersion = "2.3.0", route = route,
        startLatencyMs = 82.0, calibrationMs = 1.5, clockSpreadMs = 0.31, clockSkewPpm = 12.0, phase = "LOCKED",
    )

    private fun pass(label: String, vararg values: Pair<String, Double?>) =
        ReportPass(label, values.mapIndexed { i, (name, v) -> CalibrationOutcome(name, isSelf = i == 0, correctionMs = v) })

    private val aac = AudioFormatInfo("YOUTUBE:abc", "audio/mp4a-latm", 44_100, 2, 2112, 0, 128, 140)

    @Test
    fun `report lists every pass, rates the after-calibration spread and flags problems`() {
        val phones = listOf(
            ReportPhone("Host", isHost = true, diag = diag()),
            ReportPhone("Bedroom", isHost = false, diag = diag(), linkQuality = 2, rttP90Ms = 14, syncErrorMs = 1),
            ReportPhone("Kitchen", isHost = false, diag = diag("BLUETOOTH JBL Flip"), linkQuality = 1, rttP90Ms = 60),
            ReportPhone("Old", isHost = false, diag = null, onHold = true),
        )
        val passes = listOf(
            pass("before", "Host" to 0.0, "Bedroom" to 6.0, "Kitchen" to -3.0),
            pass("calib", "Host" to -1.0, "Bedroom" to 5.0, "Kitchen" to -4.0),
            pass("after1", "Host" to 0.0, "Bedroom" to 1.0, "Kitchen" to -0.4),
            pass("after2", "Host" to 0.0, "Bedroom" to 1.4, "Kitchen" to -0.2),
            ReportPass("after3", emptyList(), failure = "Couldn't hear the chirps"),
        )
        val tracks = listOf(
            TrackFormats("YOUTUBE:abc", "Believer", mapOf("Host" to aac, "Bedroom" to aac, "Kitchen" to aac.copy(itag = 251, mime = "audio/opus"))),
            TrackFormats("JIOSAAVN:x", "Kesariya", mapOf("Host" to aac, "Bedroom" to aac)),
        )

        val text = SyncReport.build("SongSync sync report", "Host", phones, passes, "UNPROCESSED, timestamped", tracks)
        println(text)

        assertThat(text).contains("before Host +0.0 · Bedroom +6.0 · Kitchen -3.0 | 9.0")
        assertThat(text).contains("after3 failed: Couldn't hear the chirps")
        assertThat(text).contains("RESULT spread avg 1.5 max 1.6 ms -> excellent; repeat ±0.2")
        assertThat(text).contains("mic UNPROCESSED, timestamped")
        assertThat(text).contains("Bedroom · Google Pixel 8 A35 v2.3.0 · SPEAKER · Wi-Fi rtt 14 · clk ±0.31 +12ppm")
        assertThat(text).contains("Believer: mp4a-latm 44.1 kHz 2ch d2112 itag140 128k | Kitchen DIFF")
        assertThat(text).contains("Kesariya: mp4a-latm 44.1 kHz 2ch d2112 itag140 128k | all 2 same")
        assertThat(text).contains("! Old is paused on that phone")
        assertThat(text).contains("! Kitchen plays through Bluetooth")
        assertThat(text).contains("! Kitchen is on a Bluetooth link")
        assertThat(text).contains("! Kitchen played a different file of \"Believer\"")
    }

    @Test
    fun `no verdict without a successful after-calibration check`() {
        assertThat(SyncReport.verdict(listOf(pass("before", "Host" to 0.0, "B" to 3.0)))).isNull()
        assertThat(SyncReport.verdict(listOf(pass("after1", "Host" to 0.0, "B" to 12.0)))).contains("audible echo")
    }
}
