package com.songsync.app.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.songsync.app.MainActivity
import com.songsync.app.R
import com.songsync.app.SongSyncApp
import com.songsync.app.session.SessionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Which Wi-Fi lock the session service holds (null = none), for diagnostics. */
object WifiLockState {
    val mode = MutableStateFlow<String?>(null)
}

/** What the ongoing notification shows; produced by the session manager. */
data class NotificationContent(
    val title: String,
    val text: String,
    val artworkUrl: String?,
    val isPlaying: Boolean,
    val canSkip: Boolean,
)

/**
 * Keeps the process (player + Nearby link) alive while a session is active, also when the
 * screen is off or the app is swiped away, and hosts the MediaSession for system controls.
 * It stays in the foreground for the whole session, even while paused, because a paused
 * client still has to hear the host's next "play".
 */
@OptIn(UnstableApi::class)
class SyncPlaybackService : Service() {

    private val scope = MainScope()
    private var session: MediaSession? = null
    private var artworkUrl: String? = null
    private var artwork: Bitmap? = null
    private var artworkJob: Job? = null

    private val graph get() = (application as SongSyncApp).graph

    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        acquireWifiLock()
        val mediaSession = MediaSession.Builder(this, graph.sessionPlayer)
            .setSessionActivity(contentIntent())
            .build()
        session = mediaSession
        startInForeground(build(graph.sessionManager.notification.value, mediaSession))
        scope.launch {
            graph.sessionManager.notification.collect { content ->
                graph.sessionPlayer.refresh()
                loadArtwork(content.artworkUrl)
                notifyContent(content)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = graph.sessionManager
        when (intent?.action) {
            ACTION_TOGGLE -> manager.setPlaying(!manager.isPlaying)
            ACTION_NEXT -> manager.skipNext()
            ACTION_LEAVE -> manager.leave()
        }
        // E.g. a stale notification button after the session ended: don't linger.
        if (manager.state.value == SessionManager.State.Idle) stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        WifiLockState.mode.value = null
        super.onDestroy()
    }

    /**
     * Wi-Fi power save holds frames for a sleeping phone until the next beacon (~100 ms), and
     * only in one direction, which skews clock sync. A low-latency lock for the whole session
     * (also while paused, when ExoPlayer's own lock is released) keeps the radio awake.
     */
    private fun acquireWifiLock() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        if (wifi == null) {
            WifiLockState.mode.value = "unavailable (no Wi-Fi service)"
            return
        }
        // Low latency where available, high-perf as a fallback; the reason is kept for the sync
        // report when neither is held.
        val attempts = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(WifiManager.WIFI_MODE_FULL_LOW_LATENCY to "low latency")
            @Suppress("DEPRECATION") // HIGH_PERF is deprecated on API 34+; only a fallback there
            add(WifiManager.WIFI_MODE_FULL_HIGH_PERF to "high perf")
        }
        var failure = "not held"
        for ((mode, label) in attempts) {
            val lock = try {
                wifi.createWifiLock(mode, "SongSync:sync").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: RuntimeException) {
                failure = "failed: ${e.javaClass.simpleName} ${e.message.orEmpty()}".take(60)
                continue
            }
            if (lock.isHeld) {
                wifiLock = lock
                WifiLockState.mode.value = label + if (wifi.isWifiEnabled) "" else " (Wi-Fi off)"
                return
            }
            lock.release()
        }
        WifiLockState.mode.value = failure + if (wifi.isWifiEnabled) "" else " (Wi-Fi off)"
    }

    private fun startInForeground(notification: android.app.Notification) {
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
        } catch (_: SecurityException) {
            // connectedDevice needs a granted Bluetooth/Wi-Fi permission on Android 14+.
            val mediaOnly = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, mediaOnly)
        }
    }

    private fun notifyContent(content: NotificationContent) {
        val mediaSession = session ?: return
        val manager = NotificationManagerCompat.from(this)
        if (manager.areNotificationsEnabled()) {
            @Suppress("MissingPermission") // checked via areNotificationsEnabled()
            manager.notify(NOTIFICATION_ID, build(content, mediaSession))
        }
    }

    private fun loadArtwork(url: String?) {
        if (url == artworkUrl) return
        artworkUrl = url
        artwork = null
        artworkJob?.cancel()
        if (url.isNullOrBlank()) return
        artworkJob = scope.launch {
            val request = ImageRequest.Builder(this@SyncPlaybackService)
                .data(url)
                .size(ARTWORK_PX)
                .allowHardware(false)
                .build()
            val result = SingletonImageLoader.get(this@SyncPlaybackService).execute(request)
            if (url == artworkUrl) {
                artwork = (result as? SuccessResult)?.image?.toBitmap()
                notifyContent(graph.sessionManager.notification.value)
            }
        }
    }

    private fun build(content: NotificationContent, mediaSession: MediaSession) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_songsync)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setLargeIcon(artwork)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                if (content.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (content.isPlaying) R.string.action_pause else R.string.action_play),
                serviceIntent(ACTION_TOGGLE),
            )
            .apply {
                if (content.canSkip) addAction(R.drawable.ic_skip_next, getString(R.string.action_next), serviceIntent(ACTION_NEXT))
            }
            .addAction(R.drawable.ic_logout, getString(R.string.action_leave), serviceIntent(ACTION_LEAVE))
            .setStyle(MediaStyleNotificationHelper.MediaStyle(mediaSession).setShowActionsInCompactView(0))
            .build()

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, SyncPlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1
        private const val ARTWORK_PX = 256
        private const val ACTION_TOGGLE = "com.songsync.app.TOGGLE"
        private const val ACTION_NEXT = "com.songsync.app.NEXT"
        private const val ACTION_LEAVE = "com.songsync.app.LEAVE"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SyncPlaybackService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncPlaybackService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
                        description = context.getString(R.string.notification_channel_description)
                        setShowBadge(false)
                    },
                )
            }
        }
    }
}
