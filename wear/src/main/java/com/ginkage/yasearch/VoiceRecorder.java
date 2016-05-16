package com.ginkage.yasearch;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;

public class VoiceRecorder {

    public interface RecordingListener {
        void onFinishRecord();
        void onError();
        void onStreamClosed();
    }

    private static final String TAG = "VoiceRecorder";
    private static final int RECORDER_SAMPLE_RATE = 16000;
    private static final int RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_MONO;
    private static final int RECORDER_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION;
    private static final int RECORDER_BUFFER_SIZE = AudioRecord.getMinBufferSize(
            RECORDER_SAMPLE_RATE, RECORDER_CHANNELS, RECORDER_AUDIO_FORMAT);
    private static final int MAX_LEN = 160000;

    private AudioRecord recorder = null;
    private boolean isRecording = false;

    public void startRecording(
            final OutputStream stream, final RecordingListener recordingListener) {
        Log.d(TAG, "recording started...");
        recorder = new AudioRecord(
                AUDIO_SOURCE,
                RECORDER_SAMPLE_RATE,
                RECORDER_CHANNELS,
                RECORDER_AUDIO_FORMAT,
                RECORDER_BUFFER_SIZE * 2);

        isRecording = true;
        recorder.startRecording();

        Log.i(TAG, "Recording with buffer size=" + RECORDER_BUFFER_SIZE + ", max=" + MAX_LEN);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int bytesRead = 0;
                    short[] dataBuffer = new short[RECORDER_BUFFER_SIZE];
                    byte[] outBuffer = new byte[RECORDER_BUFFER_SIZE * 2];
                    int len = Speex.open(Speex.WIDE_BAND, 8, outBuffer);
                    stream.write(outBuffer, 0, len);
                    while (isRecording) {
                        int read = recorder.read(dataBuffer, 0, RECORDER_BUFFER_SIZE);
                        if (read > 0) {
                            len = Speex.encode(dataBuffer, outBuffer);
                            stream.write(outBuffer, 0, len); //(dataBuffer, 0, read);
                            bytesRead += read;
                        }
                        if (bytesRead >= MAX_LEN) {
                            Log.d(TAG, "buffer size limit " + bytesRead + " reached.");
                            stopRecording();
                        }
                    }
                    recordingListener.onFinishRecord();
                    stream.flush();
                    stream.close();
                } catch (IOException e) {
                    // Can only be error in write() or flush()/close().
                    stopRecording();
                    Log.e(TAG, "Stream suddenly closed", e);
                    recordingListener.onError();
                }
                Speex.close();

                recordingListener.onStreamClosed();
            }
        }, "AudioRecorder Thread").start();
    }

    public void stopRecording() {
        Log.d(TAG, "recording ended.");
        isRecording = false;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Throwable t) {
                Log.e(TAG, "Failed to stop recorder", t);
            }
            try {
                recorder.release();
            } catch (Throwable t) {
                Log.e(TAG, "Failed to release recorder", t);
            }
            recorder = null;
        }
    }

}
