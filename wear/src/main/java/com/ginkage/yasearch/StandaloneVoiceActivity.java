package com.ginkage.yasearch;

import android.Manifest;
import android.content.pm.PackageManager;
import android.support.v4.app.ActivityCompat;

import ru.yandex.speechkit.Error;
import ru.yandex.speechkit.Recognition;
import ru.yandex.speechkit.Recognizer;
import ru.yandex.speechkit.RecognizerListener;

public class StandaloneVoiceActivity extends VoiceActivity implements RecognizerListener {

    @Override
    public void onPhraseSpotted(String s, int i) {
        super.onPhraseSpotted(s, i);

        Recognizer recognizer = Recognizer.create("ru-RU", Recognizer.Model.QUERIES, this);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            recognizer.start();
        }
    }

    @Override
    public void onRecordingBegin(Recognizer recognizer) {
        setText(getString(R.string.recognizer_started), true, false);
        setCirclesVisibility(true);
    }

    @Override
    public void onSpeechDetected(Recognizer recognizer) {
    }

    @Override
    public void onSpeechEnds(Recognizer recognizer) {
    }

    @Override
    public void onRecordingDone(Recognizer recognizer) {
    }

    @Override
    public void onSoundDataRecorded(Recognizer recognizer, byte[] data) {
    }

    @Override
    public void onPowerUpdated(Recognizer recognizer, float power) {
        float normalizedLevel = Math.max(Math.min(power, 1.0F), 0.0F);
        if (normalizedLevel >= -1.0F) {
            mCircles.hasNoSound(false);
            if (mCircles.canBeAdded(normalizedLevel)) {
                mCircles.addCircle(normalizedLevel);
            }
        }
    }

    @Override
    public void onPartialResults(
            Recognizer recognizer, Recognition results, boolean endOfUtterance) {
        setText(results.getBestResultText(), true, false);
    }

    @Override
    public void onRecognitionDone(Recognizer recognizer, Recognition results) {
        setText(results.getBestResultText(), false, true);
        setCirclesVisibility(false);
        startPhraseSpotter(true);
    }

    @Override
    public void onError(Recognizer recognizer, Error error) {
        handleError(error);
        setCirclesVisibility(false);
        startPhraseSpotter(true);
    }

}
