package com.ginkage.yasearch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import java.io.OutputStream;

public class ProxyVoiceActivity extends VoiceActivity
        implements VoiceSender.SetupResultListener, VoiceRecorder.RecordingListener {

    private VoiceSender mVoiceSender;

    private BroadcastReceiver mResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(VoiceSender.RESULT_ACTION)) {
                byte[] result = intent.getByteArrayExtra("result");
                setText(new String(result, 0, result.length), false, true);
                mVoiceSender.shutdownVoiceChannel();
                setCirclesVisibility(false);
                startPhraseSpotter(true);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mVoiceSender = new VoiceSender(this, this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mResultReceiver, new IntentFilter(VoiceSender.RESULT_ACTION));
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mResultReceiver);
    }

    @Override
    public void onResult(final OutputStream stream, final String message) {
        setText(message, (stream != null), false);
        if (stream != null) {
            VoiceRecorder recorder = new VoiceRecorder();
            recorder.startRecording(stream, this);
            setCirclesVisibility(true);
        }
    }

    @Override
    public void onPhraseSpotted(String s, int i) {
        super.onPhraseSpotted(s, i);
        mVoiceSender.setupVoiceChannel();
    }

    @Override
    public void onStreamClosed() {
        setText(getString(R.string.bro_common_speech_dialog_ready_button), false, false);
    }

    @Override
    public void onError() {
        setText(getString(R.string.spotter_error) + "Couldn't start recording", false, false);
        mVoiceSender.shutdownVoiceChannel();
        setCirclesVisibility(false);
        startPhraseSpotter(true);
    }

}
