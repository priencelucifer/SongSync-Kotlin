package com.songsync.app.session

import com.google.common.truth.Truth.assertThat
import com.songsync.app.net.AudioFormatInfo
import com.songsync.app.net.DeviceDiag
import org.junit.Test

class SyncReportTest {

    private fun diag(route: String = "SPEAKER") = DeviceDiag(
        model = "Google Pixel 8", sdk = 35, appVersion = "2.3.0", route = route,
        startLatencyMs = 82.0, calibrationMs = 1.5, clockSpreadMs = 0.31, clockSkewPpm = 12.0, phase = "LOCKED",
        wifiLock = "low latency", rttMinMs = 3.2, rttMedianMs = 8.4,
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
            ReportPhone(
                "Busy", isHost = false, linkQuality = 3, rttP90Ms = 215, onHold = true,
                diag = diag().copy(wifiLock = "failed: SecurityException", holdReason = "another app took the audio"),
            ),
        )
        val passes = listOf(
            pass("check1", "Host" to 0.0, "Bedroom" to 1.0, "Kitchen" to -0.4),
            pass("check2", "Host" to 0.0, "Bedroom" to 1.4, "Kitchen" to -0.2),
            ReportPass("check3", emptyList(), failure = "Couldn't hear the chirps"),
        )
        val tracks = listOf(
            TrackFormats("YOUTUBE:abc", "Believer", mapOf("Host" to aac, "Bedroom" to aac, "Kitchen" to aac.copy(itag = 251, mime = "audio/opus"))),
            TrackFormats("JIOSAAVN:x", "Kesariya", mapOf("Host" to aac, "Bedroom" to aac)),
        )

        val text = SyncReport.build("SongSync sync report", "Host", phones, passes, "UNPROCESSED, timestamped", tracks)
        println(text)

        assertThat(text).contains("check1 Host +0.0 · Bedroom +1.0 · Kitchen -0.4 | 1.4")
        assertThat(text).contains("check3 failed: Couldn't hear the chirps")
        assertThat(text).contains("RESULT spread avg 1.5 max 1.6 ms -> excellent; repeat ±0.2")
        assertThat(text).contains("mic UNPROCESSED, timestamped")
        assertThat(text).contains("Bedroom · Google Pixel 8 A35 v2.3.0 · SPEAKER · wifi low latency · Wi-Fi rtt 3/8/14 · clk ±0.31 +12ppm")
        assertThat(text).contains("Busy · Google Pixel 8 A35 v2.3.0 · PAUSED (another app took the audio)")
        assertThat(text).contains("! Busy's link is slow at times (p90 215 ms)")
        assertThat(text).contains("! Busy: Wi-Fi lock failed: SecurityException")
        assertThat(text).contains("Old · (no details: older app?) · PAUSED")
        assertThat(text).contains("Believer: mp4a-latm 44.1 kHz 2ch d2112 itag140 128k | Kitchen DIFF")
        assertThat(text).contains("Kesariya: mp4a-latm 44.1 kHz 2ch d2112 itag140 128k | all 2 same")
        assertThat(text).contains("! Old is paused on that phone")
        assertThat(text).contains("! Kitchen plays through Bluetooth")
        assertThat(text).contains("! Kitchen is on a Bluetooth link")
        assertThat(text).contains("! Kitchen played a different file of \"Believer\"")
    }

    @Test
    fun `no verdict without a successful check`() {
        assertThat(SyncReport.verdict(listOf(ReportPass("check1", emptyList(), failure = "x")))).isNull()
        assertThat(SyncReport.verdict(listOf(pass("check1", "Host" to 0.0, "B" to 12.0)))).contains("audible echo")
    }

    @Test
    fun `a large saved correction on a speaker is called out, a Bluetooth one is not`() {
        val phones = listOf(
            ReportPhone("Host", isHost = true, diag = diag().copy(calibrationMs = 120.0)),
            ReportPhone("Earbuds", isHost = false, diag = diag("BLUETOOTH Buds").copy(calibrationMs = 180.0)),
            ReportPhone("Fine", isHost = false, diag = diag().copy(calibrationMs = -6.0)),
        )
        val text = SyncReport.build("r", "Host", phones, emptyList(), null, emptyList())

        assertThat(text).contains("! Host plays +120 ms shifted by a saved echo correction")
        assertThat(text).doesNotContain("Earbuds plays +")
        assertThat(text).doesNotContain("Fine plays")
    }
}
