package com.neilturner.aerialviews.ui.screensaver

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceView
import android.widget.MediaController.MediaPlayerControl
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.services.SambaDataSourceFactory
import com.neilturner.aerialviews.utils.CustomRendererFactory
import com.neilturner.aerialviews.utils.FileHelper
import com.neilturner.aerialviews.utils.PhilipsMediaCodecAdapterFactory
import com.neilturner.aerialviews.utils.WindowHelper
import kotlin.math.roundToLong

@SuppressLint("UnsafeOptInUsageError")
class ExoPlayerView(context: Context, attrs: AttributeSet? = null) : SurfaceView(context, attrs), MediaPlayerControl, Player.Listener {
    private var almostFinishedRunnable = Runnable { listener?.onAlmostFinished() }
    private var canChangePlaybackSpeedRunnable = Runnable { this.canChangePlaybackSpeed = true }
    private var onErrorRunnable = Runnable { listener?.onError() }
    private val enableTunneling = GeneralPrefs.enableTunneling
    private val useRefreshRateSwitching = GeneralPrefs.refreshRateSwitching
    private val philipsDolbyVisionFix = GeneralPrefs.philipsDolbyVisionFix
    private val maxVideoLength = GeneralPrefs.maxVideoLength
    private var playbackSpeed = GeneralPrefs.playbackSpeed
    private val muteVideo = GeneralPrefs.muteVideos
    private var listener: OnPlayerEventListener? = null
    private var canChangePlaybackSpeed = true
    private val player: ExoPlayer
    private var aspectRatio = 0f
    private var prepared = false

    init {
        player = buildPlayer(context)
        player.setVideoSurfaceView(this)
        player.addListener(this)

        // https://medium.com/androiddevelopers/prep-your-tv-app-for-android-12-9a859d9bb967
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && useRefreshRateSwitching) {
            Log.i(TAG, "Android 12, handle frame rate switching in app")
            player.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        }
    }

    fun release() {
        player.release()
        removeCallbacks(almostFinishedRunnable)
        removeCallbacks(canChangePlaybackSpeedRunnable)
        removeCallbacks(onErrorRunnable)
        listener = null
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun setUri(uri: Uri?) {
        if (uri == null) {
            return
        }
        player.stop()
        prepared = false
        val mediaItem = MediaItem.fromUri(uri)
        if (philipsDolbyVisionFix) {
            PhilipsMediaCodecAdapterFactory.mediaUrl = uri.toString()
        }
        if (FileHelper.isSambaVideo(uri)) {
            val mediaSource = ProgressiveMediaSource.Factory(SambaDataSourceFactory())
                .createMediaSource(mediaItem)
            player.setMediaSource(mediaSource)
        } else {
            player.setMediaItem(mediaItem)
        }
        player.prepare()
    }

    override fun onDetachedFromWindow() {
        pause()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var newWidthMeasureSpec = widthMeasureSpec
        if (aspectRatio > 0) {
            val newHeight = MeasureSpec.getSize(heightMeasureSpec)
            val newWidth = (newHeight * aspectRatio).toInt()
            newWidthMeasureSpec = MeasureSpec.makeMeasureSpec(newWidth, MeasureSpec.EXACTLY)
        }
        super.onMeasure(newWidthMeasureSpec, heightMeasureSpec)
    }

    fun setOnPlayerListener(listener: OnPlayerEventListener?) {
        this.listener = listener
    }

    /* MediaPlayerControl */
    override fun start() {
        player.playWhenReady = true
    }

    override fun pause() {
        player.playWhenReady = false
    }

    override fun getDuration(): Int {
        return player.duration.toInt()
    }

    override fun getCurrentPosition(): Int {
        return player.currentPosition.toInt()
    }

    override fun seekTo(pos: Int) {
        player.seekTo(pos.toLong())
    }

    override fun isPlaying(): Boolean {
        return player.playWhenReady
    }

    override fun getBufferPercentage(): Int {
        return player.bufferedPercentage
    }

    override fun canPause(): Boolean {
        return player.duration > 0
    }

    override fun canSeekBackward(): Boolean {
        return player.duration > 0
    }

    override fun canSeekForward(): Boolean {
        return player.duration > 0
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun getAudioSessionId(): Int {
        return player.audioSessionId
    }

    /* EventListener */
    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_IDLE -> Log.i(TAG, "Idle...") // 1
            Player.STATE_BUFFERING -> Log.i(TAG, "Buffering...") // 2
            Player.STATE_READY -> Log.i(TAG, "Playing...") // 3
            Player.STATE_ENDED -> Log.i(TAG, "Playback ended...") // 4
        }

        if (!prepared && playbackState == Player.STATE_READY) {
            prepared = true
            listener?.onPrepared()
        }

        if (player.playWhenReady && playbackState == Player.STATE_READY) {
            if (useRefreshRateSwitching) {
                setRefreshRate()
            }
            setupAlmostFinishedRunnable()
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun setRefreshRate() {
        val frameRate = player.videoFormat?.frameRate
        val surface = this.holder.surface

        if (frameRate == null || frameRate == 0f) {
            Log.i(TAG, "Unable to get video frame rate...")
            return
        }

        Log.i(TAG, "${frameRate}fps video, setting refresh rate if needed...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            WindowHelper.setLegacyRefreshRate(context, frameRate)
        }
    }

    fun increaseSpeed() {
        changeSpeed(true)
    }

    fun decreaseSpeed() {
        changeSpeed(false)
    }

    private fun changeSpeed(increase: Boolean) {
        if (!canChangePlaybackSpeed) {
            return
        }

        if (!prepared || !player.isPlaying) {
            return // Must be playing a video
        }

        if (player.currentPosition <= 3) {
            return // No speed change at the start of the video
        }

        if (duration - player.currentPosition <= 3) {
            return // No speed changes at the end of video
        }

        canChangePlaybackSpeed = false
        postDelayed(canChangePlaybackSpeedRunnable, 2000)

        val currentSpeed = playbackSpeed
        val speedValues = resources.getStringArray(R.array.playback_speed_values)
        val currentSpeedIdx = speedValues.indexOf(currentSpeed)

        if (!increase && currentSpeedIdx == 0) {
            return // we are at minimum speed already
        }

        if (increase && currentSpeedIdx == speedValues.size - 1) {
            return // we are at maximum speed already
        }

        val newSpeed = if (increase) {
            speedValues[currentSpeedIdx + 1]
        } else {
            speedValues[currentSpeedIdx - 1]
        }

        playbackSpeed = newSpeed
        player.setPlaybackSpeed(newSpeed.toFloat())

        setupAlmostFinishedRunnable()
        listener?.onPlaybackSpeedChanged()
    }

    private fun setupAlmostFinishedRunnable() {
        removeCallbacks(almostFinishedRunnable)

        // Check if we need to limit the duration of the video
        var targetDuration = duration
        val limit = maxVideoLength.toInt() * 1000
        val tenSeconds = 10 * 1000
        if (limit in tenSeconds until duration
        ) {
            targetDuration = limit
        }

        // compensate the duration based on the playback speed
        // take into account the current player position in case of speed changes during playback
        var delay = (((targetDuration - player.currentPosition) / playbackSpeed.toFloat()).roundToLong() - FADE_DURATION)
        if (delay < 0) {
            delay = 0
        }
        postDelayed(almostFinishedRunnable, delay)
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        // error.printStackTrace()
        removeCallbacks(almostFinishedRunnable)
        postDelayed(onErrorRunnable, 3000)
    }

    override fun onPlayerErrorChanged(error: PlaybackException?) {
        super.onPlayerErrorChanged(error)
        // error.printStackTrace()
        error?.message?.let { Log.e(TAG, it) }
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        aspectRatio = if (height == 0) 0F else width * videoSize.pixelWidthHeightRatio / height
        requestLayout()
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun buildPlayer(context: Context): ExoPlayer {
        val parametersBuilder = DefaultTrackSelector.Parameters.Builder(context)

        if (enableTunneling) {
            parametersBuilder
                .setTunnelingEnabled(true)
        }

        val trackSelector = DefaultTrackSelector(context)
        trackSelector.parameters = parametersBuilder.build()

        var rendererFactory = DefaultRenderersFactory(context)
        if (philipsDolbyVisionFix) {
            rendererFactory = CustomRendererFactory(context)
        }

        val player = ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setRenderersFactory(rendererFactory)
            .build()

        // player.addAnalyticsListener(com.google.android.exoplayer2.util.EventLogger(trackSelector))

        if (muteVideo) {
            player.volume = 0f
        }

        player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        player.setPlaybackSpeed(playbackSpeed.toFloat())
        return player
    }

    interface OnPlayerEventListener {
        fun onAlmostFinished()
        fun onError()
        fun onPrepared()
        fun onPlaybackSpeedChanged()
    }

    companion object {
        private const val TAG = "ExoPlayerView"
        const val FADE_DURATION: Long = 1000
    }
}
