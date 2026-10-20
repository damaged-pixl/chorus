package com.bee.chorus.playback

import android.content.Context
import androidx.media3.common.audio.GainProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor

/**
 * Default renderers with one change: the audio sink's processor chain gains a
 * [GainProcessor] at the front (ReplayGain), ahead of the default silence-skipper and
 * Sonic (which still owns playback speed — the two are independent).
 *
 * The chain mirrors what DefaultAudioSink builds by default, so nothing else about the
 * pipeline changes. GainProcessor accepts PCM 16-bit and float; the sink's default input
 * is 16-bit (float output is off unless a DefaultRenderersFactory enables it, which we do
 * not), so the encoding is always one it can handle.
 */
class ChorusRenderersFactory(
    context: Context,
    private val gainProvider: GainProcessor.GainProvider,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): DefaultAudioSink =
        DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(
                DefaultAudioProcessorChain(
                    arrayOf(GainProcessor(gainProvider)),
                    SilenceSkippingAudioProcessor(),
                    SonicAudioProcessor(),
                )
            )
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
}
