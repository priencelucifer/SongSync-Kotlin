package com.songsync.app

import android.app.Application
import com.songsync.app.calibration.MicRecorder
import com.songsync.app.data.SearchRepository
import com.songsync.app.data.SettingsStore
import com.songsync.app.data.TrackResolver
import com.songsync.app.data.model.TrackSource
import com.songsync.app.data.source.JioSaavnSource
import com.songsync.app.data.source.MusicSource
import com.songsync.app.data.source.NewPipeDownloader
import com.songsync.app.data.source.YouTubeSource
import com.songsync.app.net.NearbyTransport
import com.songsync.app.playback.AndroidScheduler
import com.songsync.app.playback.AudioRouteMonitor
import com.songsync.app.playback.PlayerEngine
import com.songsync.app.playback.RouteLatencyProfile
import com.songsync.app.playback.SessionPlayer
import com.songsync.app.playback.SystemMonotonicClock
import com.songsync.app.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Hand-wired dependencies; one instance per process, created on the main thread. */
class AppGraph(app: Application) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val settings = SettingsStore(app)

    private val maxBitrate = settings.maxBitrateKbps.stateIn(appScope, SharingStarted.Eagerly, 320)

    private val sources: Map<TrackSource, MusicSource> = mapOf(
        TrackSource.JIOSAAVN to JioSaavnSource(okHttp) { maxBitrate.value },
        TrackSource.YOUTUBE to YouTubeSource(),
    )

    val search = SearchRepository(sources)

    private val resolver = TrackResolver(sources, app.cacheDir)

    val playerEngine = PlayerEngine(app, okHttp)

    private val routes = AudioRouteMonitor(app)
    val audioRoute get() = routes.route

    val latency = RouteLatencyProfile(settings, appScope)

    private val transport = NearbyTransport(app, SystemMonotonicClock)

    val sessionManager = SessionManager(
        context = app,
        scope = appScope,
        transport = transport,
        engine = playerEngine,
        resolver = resolver,
        settings = settings,
        latency = latency,
        routes = routes,
        clock = SystemMonotonicClock,
        scheduler = AndroidScheduler(),
        appVersion = BuildConfig.VERSION_NAME,
        recorder = MicRecorder(app),
    )

    /** Shared by every MediaSession the service creates, so listeners are not leaked. */
    val sessionPlayer = SessionPlayer(playerEngine.player, sessionManager)

    init {
        val locale = Locale.getDefault()
        NewPipe.init(
            NewPipeDownloader(okHttp),
            Localization.fromLocale(locale),
            ContentCountry(locale.country.ifBlank { "US" }),
        )
    }
}
