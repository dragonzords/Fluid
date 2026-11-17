package code.name.monkey.retromusic.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.CallSuper
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import code.name.monkey.appthemehelper.util.VersionUtils
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.online.OnlineMusic
import code.name.monkey.retromusic.service.playback.Playback
import code.name.monkey.retromusic.util.PreferenceUtil.isAudioFocusEnabled
import code.name.monkey.retromusic.util.PreferenceUtil.playbackPitch
import code.name.monkey.retromusic.util.PreferenceUtil.playbackSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

abstract class LocalPlayback(val context: Context) : Playback, MediaPlayer.OnErrorListener,
    MediaPlayer.OnCompletionListener {

    private val becomingNoisyReceiverIntentFilter =
        IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)

    private val audioManager: AudioManager? = context.getSystemService()

    private var becomingNoisyReceiverRegistered = false
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != null
                && intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY
            ) {
                val serviceIntent = Intent(context, MusicService::class.java)
                serviceIntent.action = MusicService.ACTION_PAUSE
                context.startService(serviceIntent)
            }
        }
    }

    private var isPausedByTransientLossOfFocus = false

    private val resolveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolveTokens = ConcurrentHashMap<MediaPlayer, Any>()

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (!isPlaying && isPausedByTransientLossOfFocus) {
                    start()
                    callbacks?.onPlayStateChanged()
                    isPausedByTransientLossOfFocus = false
                }
                setVolume(Volume.NORMAL)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Lost focus for an unbounded amount of time: stop playback and release media playback
                if (!isAudioFocusEnabled) {
                    pause()
                    callbacks?.onPlayStateChanged()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Lost focus for a short time, but we have to stop
                // playback. We don't release the media playback because playback
                // is likely to resume
                val wasPlaying = isPlaying
                pause()
                callbacks?.onPlayStateChanged()
                isPausedByTransientLossOfFocus = wasPlaying
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Lost focus for a short time, but it's ok to keep playing
                // at an attenuated level
                setVolume(Volume.DUCK)
            }
        }
    }

    private val audioFocusRequest: AudioFocusRequestCompat =
        AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .setAudioAttributes(
                AudioAttributesCompat.Builder()
                    .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC).build()
            ).build()

    @CallSuper
    override fun start(): Boolean {
        if (!requestFocus()) {
            context.showToast(R.string.audio_focus_denied)
        }
        registerBecomingNoisyReceiver()
        return true
    }

    @CallSuper
    override fun stop() {
        resolveTokens.clear()
        abandonFocus()
        unregisterBecomingNoisyReceiver()
    }

    @CallSuper
    override fun pause(): Boolean {
        unregisterBecomingNoisyReceiver()
        return true
    }

    /**
     * @param player The [MediaPlayer] to use
     * @param path The path of the file, or the http/rtsp URL of the stream you want to play
     * @return True if the <code>player</code> has been prepared and is ready to play, false otherwise
     */
    fun setDataSourceImpl(
        player: MediaPlayer,
        path: String,
        completion: (success: Boolean) -> Unit,
    ) {
        // Any resolve still pending for this player is obsolete now.
        resolveTokens.remove(player)
        if (!OnlineMusic.isOnline(path)) {
            prepareDataSource(player, path, null, completion)
            return
        }

        // Online song: `path` is a yt:<videoId> placeholder. Resolve the real stream URL off the
        // calling thread, then continue on the calling thread's looper.
        player.reset()
        val handler = Handler(Looper.myLooper() ?: Looper.getMainLooper())
        val token = Any()
        resolveTokens[player] = token
        resolveScope.launch {
            val info = OnlineMusic.resolve(OnlineMusic.videoId(path))
            handler.post {
                if (resolveTokens[player] !== token) return@post // superseded or cancelled
                resolveTokens.remove(player)
                if (info == null) {
                    completion(false)
                } else {
                    prepareDataSource(
                        player,
                        info.url,
                        mapOf("User-Agent" to info.userAgent),
                        completion
                    )
                }
            }
        }
    }

    /** Drop a pending online resolve for [player] (e.g. because it is about to be released). */
    protected fun cancelResolve(player: MediaPlayer?) {
        if (player != null) resolveTokens.remove(player)
    }

    private fun prepareDataSource(
        player: MediaPlayer,
        path: String,
        headers: Map<String, String>?,
        completion: (success: Boolean) -> Unit,
    ) {
        player.reset()
        try {
            if (headers != null) {
                player.setDataSource(context, Uri.parse(path), headers)
            } else if (path.startsWith("content://")) {
                player.setDataSource(context, path.toUri())
            } else {
                player.setDataSource(path)
            }
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            if (VersionUtils.hasMarshmallow())
                player.playbackParams =
                    PlaybackParams().setSpeed(playbackSpeed).setPitch(playbackPitch)

            player.setOnPreparedListener {
                player.setOnPreparedListener(null)
                completion(true)
            }
            player.prepareAsync()
        } catch (e: Exception) {
            completion(false)
            e.printStackTrace()
        }
        player.setOnCompletionListener(this)
        player.setOnErrorListener(this)
    }

    private fun unregisterBecomingNoisyReceiver() {
        if (becomingNoisyReceiverRegistered) {
            context.unregisterReceiver(becomingNoisyReceiver)
            becomingNoisyReceiverRegistered = false
        }
    }

    private fun registerBecomingNoisyReceiver() {
        if (!becomingNoisyReceiverRegistered) {
            context.registerReceiver(
                becomingNoisyReceiver,
                becomingNoisyReceiverIntentFilter
            )
            becomingNoisyReceiverRegistered = true
        }
    }

    private fun requestFocus(): Boolean {
        return AudioManagerCompat.requestAudioFocus(
            audioManager!!,
            audioFocusRequest
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        AudioManagerCompat.abandonAudioFocusRequest(audioManager!!, audioFocusRequest)
    }

    object Volume {
        /**
         * The volume we set the media player to when we lose audio focus, but are
         * allowed to reduce the volume instead of stopping playback.
         */
        const val DUCK = 0.2f

        /** The volume we set the media player when we have audio focus.  */
        const val NORMAL = 1.0f
    }
}