package com.ginkage.yasearch;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.support.v4.app.ActivityCompat;
import android.view.View;

import java.util.ArrayList;

import ru.yandex.speechkit.Error;
import ru.yandex.speechkit.Recognition;
import ru.yandex.speechkit.Recognizer;
import ru.yandex.speechkit.RecognizerListener;
import ru.yandex.speechkit.SpeechKit;

public class StandaloneVoiceActivity extends VoiceActivity implements RecognizerListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (mStartForResult) {
            SpeechKit speechKit = SpeechKit.getInstance();
            speechKit.configure(getApplicationContext(), VoiceActivity.API_KEY);

            onPhraseSpotted("", 0);
        }
    }

    @Override
    public void onPhraseSpotted(String s, int i) {
        super.onPhraseSpotted(s, i);

        final Recognizer recognizer = Recognizer.create("ru-RU", Recognizer.Model.QUERIES, this);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            recognizer.start();

            mMicBackView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mMicBackView.setOnClickListener(null);
                    recognizer.finishRecording();
                }
            });
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

        if (mStartForResult) {
            ArrayList<String> list = new ArrayList<>();
            list.add(results.getBestResultText());
            setResult(RESULT_OK, new Intent()
                    .putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, list));
            finish();
        }
    }

    @Override
    public void onError(Recognizer recognizer, Error error) {
        handleError(error);
        setCirclesVisibility(false);
        startPhraseSpotter(true);

        if (mStartForResult) {
            setResult(RESULT_CANCELED);
            finish();
        }
    }

}
