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

    public interface Listener {
        void onDualMonoDetected(int suggestedMode);
    }

    private int mode = MODE_STEREO;
    private Listener listener;

    // Dual-mono auto-detection variables
    private boolean detectionDone = false;
    private int validBlocksCollected = 0;
    private double totalCorrelation = 0;
    
    // We analyze blocks of 4000 samples (which is 8000 bytes).
    private final short[] blockL = new short[4000];
    private final short[] blockR = new short[4000];
    private int blockIndex = 0;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setMode(int mode) {
        if (this.mode != mode) {
            this.mode = mode;
        }
        // If user manually forces Left or Right, stop auto-detection
        if (mode != MODE_STEREO) {
            detectionDone = true;
        }
    }

    public int getMode() {
        return mode;
    }

    public void resetDetection() {
        // Reset detection state for the next media/channel
        if (mode == MODE_STEREO) {
            detectionDone = false;
            validBlocksCollected = 0;
            totalCorrelation = 0;
            blockIndex = 0;
        } else {
            // If already set to LEFT/RIGHT, no need to auto-detect
            detectionDone = true;
        }
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
        
        // Reset auto-detection state when audio format is configured (e.g. stream changes)
        resetDetection();
        
        return inputAudioFormat; // output format is the same as input
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining == 0) {
            return;
        }

        // Run auto-detection if enabled and not yet completed
        if (!detectionDone) {
            runAutoDetection(inputBuffer);
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

    private void runAutoDetection(ByteBuffer inputBuffer) {
        ByteBuffer dup = inputBuffer.duplicate();
        dup.order(inputBuffer.order());

        while (dup.hasRemaining() && !detectionDone) {
            if (dup.remaining() < 4) {
                break;
            }
            short left = dup.getShort();
            short right = dup.getShort();

            blockL[blockIndex] = left;
            blockR[blockIndex] = right;
            blockIndex++;

            if (blockIndex == 4000) {
                analyzeBlock();
                blockIndex = 0;
            }
        }
    }

    private void analyzeBlock() {
        long sumL2 = 0;
        long sumR2 = 0;
        long sumLR = 0;
        long absL = 0;
        long absR = 0;

        for (int i = 0; i < 4000; i++) {
            long l = blockL[i];
            long r = blockR[i];
            sumL2 += l * l;
            sumR2 += r * r;
            sumLR += l * r;
            absL += Math.abs(l);
            absR += Math.abs(r);
        }

        double avgAbsL = absL / 4000.0;
        double avgAbsR = absR / 4000.0;

        // Ignore quiet/silent frames to prevent noise from corrupting correlation statistics
        if (avgAbsL < 400.0 || avgAbsR < 400.0) {
            return;
        }

        if (sumL2 == 0 || sumR2 == 0) {
            return;
        }

        double correlation = (double) sumLR / Math.sqrt((double) sumL2 * sumR2);
        totalCorrelation += correlation;
        validBlocksCollected++;

        // We require 5 valid audio blocks (approx. 400ms of active audio) to determine correlation
        if (validBlocksCollected >= 5) {
            double avgCorrelation = totalCorrelation / validBlocksCollected;
            detectionDone = true;

            // Highly uncorrelated (avgCorrelation < 0.40) implies different audio source on left/right (dual-mono)
            if (avgCorrelation < 0.40) {
                // In Indonesian TV channels, Left is Indonesian and Right is English/Original.
                final int suggestedMode = MODE_LEFT_ONLY;
                
                android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                mainHandler.post(() -> {
                    if (listener != null) {
                        listener.onDualMonoDetected(suggestedMode);
                    }
                });
            }
        }
    }
}
