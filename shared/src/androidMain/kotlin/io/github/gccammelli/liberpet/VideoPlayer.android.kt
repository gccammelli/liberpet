package io.github.gccammelli.liberpet

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.Alignment
import android.view.TextureView
import androidx.media3.common.VideoSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

@OptIn(UnstableApi::class)
@Composable
actual fun VideoPlayer(url: String, muted: Boolean, modifier: Modifier, onError: (String) -> Unit) {
    val ctx = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setLoadErrorHandlingPolicy(RetryEarlyParts)).build().apply {
            setMediaItem(MediaItem.Builder().setUri(url)
                // Stay close to the live edge (Home Assistant serves low-latency HLS).
                .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(2_500).build()).build())
            playWhenReady = true
            prepare()
        }
    }
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }
    var aspect by remember(url) { mutableFloatStateOf(16 / 9f) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onError(error.errorCodeName)
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    // A TextureView draws in the normal view order; PlayerView's default SurfaceView sits behind the window
    // and ends up hidden under Compose backgrounds.
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView({ TextureView(it) }, Modifier.aspectRatio(aspect), update = { player.setVideoTextureView(it) })
    }
}

/**
 * Home Assistant's low-latency HLS advertises the next part before it exists; asking for it a moment early returns
 * HTTP 416. Retry those shortly instead of failing (the default would drop the only playlist and stop).
 */
@OptIn(UnstableApi::class)
private object RetryEarlyParts : DefaultLoadErrorHandlingPolicy() {
    private fun early(info: LoadErrorHandlingPolicy.LoadErrorInfo) =
        (info.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode == 416

    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo) =
        if (early(info)) 300L else super.getRetryDelayMsFor(info)

    override fun getFallbackSelectionFor(options: LoadErrorHandlingPolicy.FallbackOptions, info: LoadErrorHandlingPolicy.LoadErrorInfo) =
        if (early(info)) null else super.getFallbackSelectionFor(options, info)

    // A part is ready within about a second; real failures still end up as an error.
    override fun getMinimumLoadableRetryCount(dataType: Int) = 10
}
