package io.github.gccammelli.liberpet

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
@Composable
actual fun VideoPlayer(url: String, muted: Boolean, modifier: Modifier, onError: (String) -> Unit) {
    val ctx = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.Builder().setUri(url)
                // Stay close to the live edge (Home Assistant serves low-latency HLS).
                .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(2_500).build()).build())
            playWhenReady = true
            prepare()
        }
    }
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onError(error.errorCodeName)
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    AndroidView({ PlayerView(it).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT } }, modifier, update = { it.player = player })
}
