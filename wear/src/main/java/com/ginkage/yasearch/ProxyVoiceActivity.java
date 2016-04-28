package com.ginkage.yasearch;

import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.View;

import java.io.OutputStream;
import java.util.ArrayList;

public class ProxyVoiceActivity extends VoiceActivity
        implements VoiceSender.ResultListener, VoiceRecorder.RecordingListener {

    private VoiceSender mVoiceSender;
    private VoiceRecorder mVoiceRecorder;
    private boolean mListening;
    private String mResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mVoiceSender = new VoiceSender(this, this);
        mResult = null;

        if (mStartForResult) {
            onPhraseSpotted("", 0);
        }
    }

    @Override
    public void onPhraseSpotted(String s, int i) {
        super.onPhraseSpotted(s, i);
        mResult = null;
        mListening = false;
        mVoiceSender.setupVoiceChannel();
    }

    @Override
    public void onChannelReady(final OutputStream stream, final String message) {
        mListening = (stream != null);
        setText(message, mListening, false);
        if (mListening) {
            mVoiceRecorder = new VoiceRecorder();
            mVoiceRecorder.startRecording(stream, this);
            setCirclesVisibility(true);

            mMicBackView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mMicBackView.setOnClickListener(null);
                    if (mVoiceRecorder != null) {
                        mVoiceRecorder.stopRecording();
                        mListening = false;
                    }
                }
            });
        } else {
            startPhraseSpotter(true);
        }
    }

    @Override
    public void onRecognitionError(String message) {
        setText((mResult == null) ? message : mResult, false, true);
        if (mVoiceRecorder != null) {
            mVoiceRecorder.stopRecording();
            mListening = false;
        }
        resetState();
    }

    @Override
    public void onRecognitionResult(String text, boolean full) {
        mResult = text;
        setText(text, mListening && !full, full);
        if (full) {
            if (mVoiceRecorder != null) {
                mVoiceRecorder.stopRecording();
                mListening = false;
            }
            resetState();
        }
    }

    @Override
    public void onFinishRecord() {
        // We don't read from the mic anymore. What's left is flush and wait.

        if (mResult == null) {
            setText(getString(R.string.bro_common_speech_dialog_ready_button), false, false);
        }
    }

    @Override
    public void onError() {
        // Called when the stream closed before we could end reading the audio or flush the data.
        // Can be either a server error or end-of-speech detection.
        // If we got a "full" result before this, that's fine.
        // If we didn't get even a partial result, we have an error.

        if (mResult == null) {
            setText(getString(R.string.spotter_error) + "Unexpected channel error", false, false);
            resetState();
        }
    }

    @Override
    public void onStreamClosed() {
        // Called when the voice recorder stopped listening for whatever reason.
        mVoiceRecorder = null;
    }

    private void resetState() {
        if (mResult != null) {
            setText(mResult, false, true);
        }
        mVoiceSender.shutdownChannel();
        setCirclesVisibility(false);
        startPhraseSpotter(true);

        if (mStartForResult) {
            if (mResult == null) {
                setResult(RESULT_CANCELED);
            } else {
                ArrayList<String> list = new ArrayList<>();
                list.add(mResult);
                setResult(RESULT_OK, new Intent()
                        .putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, list));
            }
            finish();
        }
    }

}
