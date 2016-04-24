package com.ginkage.yasearch;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.wearable.activity.WearableActivity;
import android.view.View;
import android.widget.TextView;

import ru.yandex.speechkit.Error;
import ru.yandex.speechkit.PhraseSpotter;
import ru.yandex.speechkit.PhraseSpotterListener;
import ru.yandex.speechkit.PhraseSpotterModel;
import ru.yandex.speechkit.SpeechKit;

public class VoiceActivity extends WearableActivity implements PhraseSpotterListener {

    private static final String API_KEY = "f9c9a742-8c33-4961-9217-f622744b6063";
    private static final int REQUEST_PERMISSION_CODE = 1;

    protected CirclesAnimationView mCircles;
    private TextView mTextView;
    private View mMicView;
    private boolean mSpotting;
    private boolean mShowingResults;

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

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        if (requestCode == REQUEST_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startPhraseSpotter(false);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startPhraseSpotter(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handleError(PhraseSpotter.stop());
    }

    @Override
    public void onPhraseSpotted(String s, int i) {
        setText(getString(R.string.phrase_spotted), false, false);
        PhraseSpotter.stop();
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

    protected void handleError(Error error) {
        if (error.getCode() != Error.ERROR_OK) {
            setText(getString(R.string.spotter_error) + error.getString(), false, false);
        }
    }

    protected void startPhraseSpotter(boolean request) {
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

    protected void setCirclesVisibility(boolean visible) {
        if (visible) {
            mCircles.setVisibility(View.VISIBLE);
            mCircles.setImage(mMicView);
        } else {
            mCircles.setVisibility(View.GONE);
        }

        mCircles.setPlaying(visible);
    }

    protected void setText(final String text, final boolean listening, boolean results) {
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
