package com.songsync.app.session

import com.songsync.app.net.AudioFormatInfo
import com.songsync.app.net.DeviceDiag
import java.util.Locale

/** One phone as it appears in the sync report. */
data class ReportPhone(
    val name: String,
    val isHost: Boolean,
    val diag: DeviceDiag?,
    val linkQuality: Int? = null,
    val rttP90Ms: Int? = null,
    val syncErrorMs: Int? = null,
    val onHold: Boolean = false,
)

/** One measurement or calibration run; [failure] says why it produced nothing. */
data class ReportPass(val label: String, val outcomes: List<CalibrationOutcome>, val failure: String? = null) {
    val heard: List<Double> get() = outcomes.mapNotNull { it.correctionMs }
    val spread: Double? get() = heard.takeIf { it.size >= 2 }?.let { it.max() - it.min() }
}

/** Which file each phone decoded for one song of this session. */
data class TrackFormats(val key: String, val title: String, val byPhone: Map<String, AudioFormatInfo>)

/**
 * Builds the automatic sync test's report: compact plain text meant to fit one phone screenshot
 * (and to be copied as text). Pure, so it is unit tested.
 */
object SyncReport {

    fun build(
        header: String,
        hostName: String,
        phones: List<ReportPhone>,
        passes: List<ReportPass>,
        micNote: String?,
        tracks: List<TrackFormats>,
    ): String = buildString {
        appendLine(header)

        appendLine()
        appendLine("CHECK SYNC  ms vs host (+ = late) | spread")
        for (pass in passes) {
            append(pass.label.padEnd(7))
            if (pass.failure != null) {
                appendLine("failed: ${pass.failure}")
                continue
            }
            append(pass.outcomes.joinToString(" · ") { "${short(it.name)} ${value(it)}" })
            pass.spread?.let { append(" | %.1f".fmt(it)) }
            appendLine()
        }
        verdict(passes)?.let { appendLine(it) }
        micNote?.let { appendLine("mic $it") }

        appendLine()
        appendLine("PHONES")
        for (p in phones) appendLine(phoneLine(p))

        if (tracks.isNotEmpty()) {
            appendLine()
            appendLine("FILES (songs this session)")
            for (t in tracks) appendLine(trackLine(t, hostName))
        }

        val warnings = warnings(phones, tracks, hostName)
        if (warnings.isNotEmpty()) {
            appendLine()
            warnings.forEach { appendLine("! $it") }
        }
    }.trimEnd()

    /** Summary over the measure-only passes after calibration (labels starting with "after"). */
    internal fun verdict(passes: List<ReportPass>): String? {
        val after = passes.filter { it.label.startsWith("after") && it.failure == null }
        val spreads = after.mapNotNull { it.spread }
        if (spreads.isEmpty()) return null
        val worst = spreads.max()
        val rating = when {
            worst <= 2.0 -> "excellent"
            worst <= 5.0 -> "good"
            worst <= 10.0 -> "acceptable"
            else -> "audible echo"
        }
        // Repeatability: how much each phone's own number moved between the after-passes.
        val byPhone = after.flatMap { it.outcomes }.filter { it.correctionMs != null }.groupBy { it.name }
        val repeat = byPhone.values.filter { it.size >= 2 }.maxOfOrNull { list ->
            val v = list.map { it.correctionMs!! }
            v.max() - v.min()
        }
        return "RESULT spread avg %.1f max %.1f ms -> %s".fmt(spreads.average(), worst, rating) +
            (repeat?.let { "; repeat ±%.1f".fmt(it / 2) } ?: "")
    }

    private fun phoneLine(p: ReportPhone): String {
        val d = p.diag
        val parts = mutableListOf<String>()
        parts += short(p.name)
        parts += d?.let { "${it.model} A${it.sdk} v${it.appVersion}" } ?: "(no details: older app?)"
        if (p.onHold) parts += "PAUSED"
        d?.let { parts += it.route }
        d?.let { parts += "wifi ${it.wifiLock ?: "unlocked"}" }
        if (!p.isHost) {
            parts += listOfNotNull(link(p.linkQuality), p.rttP90Ms?.let { "rtt $it" }).joinToString(" ").ifEmpty { "link ?" }
            d?.clockSpreadMs?.let { spread ->
                parts += "clk ±%.2f".fmt(spread) + (d.clockSkewPpm?.let { " %+.0fppm".fmt(it) } ?: "")
            }
        }
        d?.let { parts += "start %.0f cal %+.1f".fmt(it.startLatencyMs, it.calibrationMs) }
        d?.let { diag ->
            parts += "${diag.phase} err ${p.syncErrorMs?.let { "%+d".fmt(it) } ?: "-"} resync ${diag.hardResyncs}"
        }
        return parts.joinToString(" · ")
    }

    private fun trackLine(t: TrackFormats, hostName: String): String {
        val title = t.title.take(18)
        val reference = t.byPhone[hostName] ?: t.byPhone.values.firstOrNull() ?: return "$title: no format reported"
        val others = t.byPhone.filterKeys { it != hostName }
        val different = others.filterValues { !it.sameContentAs(reference) }
        val tail = when {
            others.isEmpty() -> "only host"
            different.isEmpty() -> "all ${others.size + 1} same"
            else -> different.entries.joinToString(" | ") { (name, f) -> "${short(name)} DIFF ${f.summary()}" }
        }
        return "$title: ${reference.summary()} | $tail"
    }

    private fun warnings(phones: List<ReportPhone>, tracks: List<TrackFormats>, hostName: String): List<String> {
        val w = mutableListOf<String>()
        phones.filter { it.onHold }.forEach { w += "${it.name} is paused on that phone; tap Rejoin and run again" }
        phones.filter { it.diag?.route?.startsWith("BLUETOOTH") == true }
            .forEach { w += "${it.name} plays through Bluetooth: expect 100-300 ms extra, calibrate after connecting" }
        phones.filter { !it.isHost && it.linkQuality == 1 }
            .forEach { w += "${it.name} is on a Bluetooth link to the host: put all phones on the same Wi-Fi" }
        tracks.forEach { t ->
            val ref = t.byPhone[hostName] ?: return@forEach
            t.byPhone.filter { (n, f) -> n != hostName && !f.sameContentAs(ref) }
                .forEach { (n, _) -> w += "$n played a different file of \"${t.title.take(18)}\" (constant offset risk)" }
        }
        return w
    }

    private fun value(o: CalibrationOutcome): String = when {
        o.paused -> "paused"
        o.correctionMs == null -> "n/h"
        else -> "%+.1f".fmt(o.correctionMs)
    }

    private fun link(quality: Int?): String? = when (quality) {
        1 -> "BT"
        2 -> "Wi-Fi"
        3 -> "fast Wi-Fi"
        else -> null
    }

    private fun short(name: String) = name.take(10)

    private fun String.fmt(vararg args: Any?) = String.format(Locale.US, this, *args)
}
