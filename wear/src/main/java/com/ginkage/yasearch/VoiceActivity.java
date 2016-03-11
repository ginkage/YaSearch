package com.ginkage.yasearch;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.wearable.activity.WearableActivity;
import android.support.wearable.view.BoxInsetLayout;
import android.widget.TextView;

import java.io.OutputStream;

import ru.yandex.speechkit.Error;
import ru.yandex.speechkit.PhraseSpotter;
import ru.yandex.speechkit.PhraseSpotterListener;
import ru.yandex.speechkit.PhraseSpotterModel;
import ru.yandex.speechkit.SpeechKit;

public class VoiceActivity extends WearableActivity implements VoiceSender.SetupResult,
        PhraseSpotterListener {

    private static String API_KEY = "f9c9a742-8c33-4961-9217-f622744b6063";
    private static final int REQUEST_PERMISSION_CODE = 1;

    private BoxInsetLayout mContainerView;
    private TextView mTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice);
        setAmbientEnabled();

        mContainerView = (BoxInsetLayout) findViewById(R.id.container);
        mTextView = (TextView) findViewById(R.id.bro_common_speech_title);

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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            if (request && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                        REQUEST_PERMISSION_CODE);
            }
        } else {
            Error startResult = PhraseSpotter.start();
            handleError(startResult);
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
            mTextView.setText(getString(R.string.spotter_error) + error.getString());
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
        Error stopResult = PhraseSpotter.stop();
        handleError(stopResult);
    }

    @Override
    public void onEnterAmbient(Bundle ambientDetails) {
        super.onEnterAmbient(ambientDetails);
        updateDisplay();
    }

    @Override
    public void onUpdateAmbient() {
        super.onUpdateAmbient();
        updateDisplay();
    }

    @Override
    public void onExitAmbient() {
        updateDisplay();
        super.onExitAmbient();
    }

    private void updateDisplay() {
        if (isAmbient()) {
        } else {
        }
    }

    @Override
    public void onResult(OutputStream stream, String message) {
        mTextView.setText(message);
    }

    @Override
    public void onPhraseSpotted(String s, int i) {
        mTextView.setText(getString(R.string.phrase_spotted));
    }

    @Override
    public void onPhraseSpotterStarted() {
        mTextView.setText(getString(R.string.bro_common_speech_dialog_hint));
    }

    @Override
    public void onPhraseSpotterStopped() {
        mTextView.setText(getString(R.string.bro_common_speech_dialog_ready_button));
    }

    @Override
    public void onPhraseSpotterError(Error error) {
        handleError(error);
    }
}
