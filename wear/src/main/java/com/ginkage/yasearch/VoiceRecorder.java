package com.ginkage.yasearch;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;

/**
 * The voice recording class. For example purposes only, don't look at it.
 */
public class VoiceRecorder {
    public interface RecordingListener {
        void onStreamClosed();
        void onError();
    }

    private static final String TAG = "VoiceRecorder";
    private static final int RECORDER_SAMPLE_RATE = 16000;
    private static final int RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_MONO;
    private static final int RECORDER_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION;
    private static final int RECORDER_BUFFER_SIZE = AudioRecord.getMinBufferSize(
            RECORDER_SAMPLE_RATE, RECORDER_CHANNELS, RECORDER_AUDIO_FORMAT);
    private static final int MAX_LEN = 500000;

    private AudioRecord recorder = null;
    private boolean isRecording = false;
    private int bytesRead;
    private NoiseSuppressor noiseSuppressor = null;

    public void startRecording(
            final OutputStream stream, final RecordingListener recordingListener) {
        Log.d(TAG, "recording started...");
        recorder = new AudioRecord(
                AUDIO_SOURCE,
                RECORDER_SAMPLE_RATE,
                RECORDER_CHANNELS,
                RECORDER_AUDIO_FORMAT,
                RECORDER_BUFFER_SIZE);
        try {
            noiseSuppressor  = NoiseSuppressor.create(recorder.getAudioSessionId());
            if (noiseSuppressor.setEnabled(true) != AudioEffect.SUCCESS)  {
                noiseSuppressor = null;
            } else {
                Log.d(TAG, "Using noise suppression: " + noiseSuppressor.getDescriptor().uuid);
            }
        } catch (Exception e) {
            // setEnabled() can throw random IllegalStateExceptions.
            noiseSuppressor = null;
        }

        bytesRead = 0;
        isRecording = true;
        recorder.startRecording();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (isRecording) {
                        byte[] dataBuffer = new byte[RECORDER_BUFFER_SIZE];
                        int read = recorder.read(dataBuffer, 0, RECORDER_BUFFER_SIZE);
                        if (read > 0) {
                            try {
                                stream.write(dataBuffer, 0, read);
                                bytesRead += read;
                            } catch (IOException e) {
                                stopRecording(recordingListener);
                                break;
                            }
                        }
                        if (bytesRead > MAX_LEN) {
                            Log.d(TAG, "buffer size limit " + bytesRead + " reached.");
                            stopRecording(recordingListener);
                            break;
                        }
                    }
                } finally {
                    try {
                        stream.close();
                        recordingListener.onStreamClosed();
                    } catch (IOException e) {
                        // ignored;
                    }
                }
            }
        }, "AudioRecorder Thread").start();
    }

    public void stopRecording(RecordingListener recordingListener) {
        Log.d(TAG, "recording ended.");
        isRecording = false;
        bytesRead = 0;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Throwable t) {
                t.printStackTrace();
            }
            if (noiseSuppressor != null) {
                noiseSuppressor.release();
                noiseSuppressor = null;
            }
            try {
                recorder.release();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        recordingListener.onError();
    }
}
