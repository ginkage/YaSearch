package com.ginkage.yasearch;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.wearable.activity.WearableActivity;
import android.view.View;
import android.widget.TextView;

import java.io.OutputStream;

import ru.yandex.speechkit.Error;
import ru.yandex.speechkit.PhraseSpotter;
import ru.yandex.speechkit.PhraseSpotterListener;
import ru.yandex.speechkit.PhraseSpotterModel;
import ru.yandex.speechkit.SpeechKit;

public class ProxyVoiceActivity extends WearableActivity implements PhraseSpotterListener,
            VoiceSender.SetupResultListener, VoiceRecorder.RecordingListener {

    private static String API_KEY = "f9c9a742-8c33-4961-9217-f622744b6063";
    private static final int REQUEST_PERMISSION_CODE = 1;

    private CirclesAnimationView mCircles;
    private TextView mTextView;
    private View mMicView;
    private boolean mSpotting;
    private boolean mShowingResults;
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
        setContentView(R.layout.activity_voice);
        setAmbientEnabled();

        mCircles = (CirclesAnimationView) findViewById(R.id.bro_common_speech_titles);
        mTextView = (TextView) findViewById(R.id.bro_common_speech_title);
        mMicView = findViewById(R.id.bro_common_speech_progress);
        mMicView.setVisibility(View.GONE);
        mCircles.setVisibility(View.GONE);
        mSpotting = false;
        mVoiceSender = new VoiceSender(this, this);

        SpeechKit.getInstance().configure(getApplicationContext(), API_KEY);
        PhraseSpotterModel model = new PhraseSpotterModel("phrase-spotter/yandex");
        Error loadResult = model.load();
        if (loadResult.getCode() != Error.ERROR_OK) {
            handleError(loadResult);
        } else {
            PhraseSpotter.setListener(this);
            Error setModelResult = PhraseSpotter.setModel(model);
            handleError(setModelResult);
        }
    }

    private void startPhraseSpotter(boolean request) {
        if (mSpotting) {
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            if (request && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                        REQUEST_PERMISSION_CODE);
            }
        } else {
            Error startResult = PhraseSpotter.start();
            if (startResult.getCode() == Error.ERROR_OK) {
                mSpotting = true;
            } else {
                handleError(startResult);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        if (requestCode == REQUEST_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startPhraseSpotter(false);
            }
        }
    }

    private void handleError(Error error) {
        if (error.getCode() != Error.ERROR_OK) {
            setText(getString(R.string.spotter_error) + error.getString(), false, false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mResultReceiver, new IntentFilter(VoiceSender.RESULT_ACTION));
        startPhraseSpotter(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mResultReceiver);
        handleError(PhraseSpotter.stop());
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
        setText(getString(R.string.phrase_spotted), false, false);
        PhraseSpotter.stop();

        mVoiceSender.setupVoiceChannel();
    }

    @Override
    public void onPhraseSpotterStarted() {
        if (!mShowingResults) {
            setText(getString(R.string.bro_common_speech_dialog_hint), false, false);
        }
        mSpotting = true;
    }

    @Override
    public void onPhraseSpotterStopped() {
        mSpotting = false;
    }

    @Override
    public void onPhraseSpotterError(Error error) {
        handleError(error);
    }

    private void setCirclesVisibility(boolean visible) {
        if (visible) {
            mCircles.setVisibility(View.VISIBLE);
            mCircles.setImage(mMicView);
        } else {
            mCircles.setVisibility(View.GONE);
        }

        mCircles.setPlaying(visible);
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

    private void setText(final String text, final boolean listening, boolean results) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTextView.setText(text);
                mMicView.setVisibility(listening ? View.VISIBLE : View.GONE);
            }
        });
        mShowingResults = results;
    }
}
