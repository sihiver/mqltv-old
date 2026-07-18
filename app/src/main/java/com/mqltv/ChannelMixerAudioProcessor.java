package com.mqltv;

import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;

@UnstableApi
public final class ChannelMixerAudioProcessor extends BaseAudioProcessor {

    public static final int MODE_STEREO = 0;
    public static final int MODE_LEFT_ONLY = 1;
    public static final int MODE_RIGHT_ONLY = 2;

    private int mode = MODE_STEREO;

    public void setMode(int mode) {
        if (this.mode != mode) {
            this.mode = mode;
            // No need to reconfigure since channel count and sample rate stay the same
        }
    }

    public int getMode() {
        return mode;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
            throws UnhandledAudioFormatException {
        // We only process PCM 16-bit stereo (2 channels).
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        if (inputAudioFormat.channelCount != 2) {
            return AudioFormat.NOT_SET; // pass-through
        }
        return inputAudioFormat; // output format is the same as input
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining == 0) {
            return;
        }

        ByteBuffer outputBuffer = replaceOutputBuffer(remaining);

        if (mode == MODE_STEREO) {
            outputBuffer.put(inputBuffer);
        } else if (mode == MODE_LEFT_ONLY) {
            // Stereo PCM 16-bit: 2 bytes Left, 2 bytes Right.
            // Copy Left to both Left and Right channels.
            while (inputBuffer.hasRemaining()) {
                if (inputBuffer.remaining() < 4) {
                    break;
                }
                short left = inputBuffer.getShort();
                inputBuffer.getShort(); // Skip Right
                outputBuffer.putShort(left); // New Left
                outputBuffer.putShort(left); // New Right
            }
        } else if (mode == MODE_RIGHT_ONLY) {
            // Copy Right to both Left and Right channels.
            while (inputBuffer.hasRemaining()) {
                if (inputBuffer.remaining() < 4) {
                    break;
                }
                inputBuffer.getShort(); // Skip Left
                short right = inputBuffer.getShort();
                outputBuffer.putShort(right); // New Left
                outputBuffer.putShort(right); // New Right
            }
        }

        outputBuffer.flip();
    }
}
