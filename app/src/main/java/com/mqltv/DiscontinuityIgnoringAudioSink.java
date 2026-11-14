package com.mqltv;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;

/**
 * Wraps an {@link AudioSink} and suppresses {@link AudioSink.UnexpectedDiscontinuityException}
 * so live IPTV/HLS/TS streams with non-monotonic audio PTS timestamps (e.g., commercial splice
 * points or segment boundary jumps) continue playing instead of crashing the player.
 */
@UnstableApi
public final class DiscontinuityIgnoringAudioSink extends ForwardingAudioSink {

    private static final String TAG = "AudioSink";

    public DiscontinuityIgnoringAudioSink(@NonNull AudioSink sink) {
        super(sink);
    }

    @Override
    public void setListener(@NonNull AudioSink.Listener listener) {
        super.setListener(new AudioSink.Listener() {
            @Override
            public void onPositionDiscontinuity() {
                listener.onPositionDiscontinuity();
            }

            @Override
            public void onPositionAdvancing(long playoutStartSystemTimeMs) {
                listener.onPositionAdvancing(playoutStartSystemTimeMs);
            }

            @Override
            public void onUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
                listener.onUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs);
            }

            @Override
            public void onSkipSilenceEnabledChanged(boolean skipSilenceEnabled) {
                listener.onSkipSilenceEnabledChanged(skipSilenceEnabled);
            }

            @Override
            public void onOffloadBufferEmptying() {
                listener.onOffloadBufferEmptying();
            }

            @Override
            public void onOffloadBufferFull() {
                listener.onOffloadBufferFull();
            }

            @Override
            public void onAudioCapabilitiesChanged() {
                listener.onAudioCapabilitiesChanged();
            }

            @Override
            public void onAudioTrackInitialized(@NonNull AudioSink.AudioTrackConfig audioTrackConfig) {
                listener.onAudioTrackInitialized(audioTrackConfig);
            }

            @Override
            public void onAudioTrackReleased(@NonNull AudioSink.AudioTrackConfig audioTrackConfig) {
                listener.onAudioTrackReleased(audioTrackConfig);
            }

            @Override
            public void onAudioSinkError(@NonNull Exception audioSinkError) {
                if (audioSinkError instanceof AudioSink.UnexpectedDiscontinuityException) {
                    Log.w(TAG, "Ignoring UnexpectedDiscontinuityException: " + audioSinkError.getMessage());
                    return;
                }
                listener.onAudioSinkError(audioSinkError);
            }
        });
    }
}

