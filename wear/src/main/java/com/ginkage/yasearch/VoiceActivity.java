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

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;

import ru.yandex.speechkit.Error;
import ru.yandex.speechkit.PhraseSpotter;
import ru.yandex.speechkit.PhraseSpotterListener;
import ru.yandex.speechkit.PhraseSpotterModel;
import ru.yandex.speechkit.SpeechKit;

public class VoiceActivity extends WearableActivity implements VoiceSender.SetupResultListener,
        PhraseSpotterListener, VoiceRecorder.RecordingListener {

    private static String API_KEY = "f9c9a742-8c33-4961-9217-f622744b6063";
    private static final int REQUEST_PERMISSION_CODE = 1;

    public static final String RESULT_ACTION = "com.ginkage.yasearch.RESULT";

    private TextView mTextView;
    private View mMicView;
    private boolean mSpotting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice);
        setAmbientEnabled();

        mTextView = (TextView) findViewById(R.id.bro_common_speech_title);
        mMicView = findViewById(R.id.bro_common_speech_progress);
        mMicView.setVisibility(View.GONE);
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
            mTextView.setText(getString(R.string.spotter_error) + error.getString());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(receiver, new IntentFilter(RESULT_ACTION));
        startPhraseSpotter(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(receiver);
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
            PhraseSpotter.stop();
        } else {
            startPhraseSpotter(false);
        }
    }

    @Override
    public void onResult(final OutputStream stream, final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTextView.setText(message);
                if (stream != null) {
                    VoiceRecorder recorder = new VoiceRecorder();
                    recorder.startRecording(stream, VoiceActivity.this);
                }
            }
        });
    }

    @Override
    public void onPhraseSpotted(String s, int i) {
        mTextView.setText(getString(R.string.phrase_spotted));
        mMicView.setVisibility(View.VISIBLE);
        PhraseSpotter.stop();
        VoiceSender sender = new VoiceSender(this, this);
        sender.setupVoiceChannel();
    }

    @Override
    public void onPhraseSpotterStarted() {
        mTextView.setText(getString(R.string.bro_common_speech_dialog_hint));
        mMicView.setVisibility(View.GONE);
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

    @Override
    public void onStreamClosed() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTextView.setText(getString(R.string.bro_common_speech_dialog_ready_button));
                mMicView.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void onError() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTextView.setText(getString(R.string.spotter_error) + "Couldn't start recording");
                mMicView.setVisibility(View.GONE);
            }
        });
    }

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(RESULT_ACTION)) {
                byte[] result = intent.getByteArrayExtra("result");
//                mTextView.setText(new String(result, 0, result.length));
//                startPhraseSpotter(true);

                try {
                    XmlPullParserFactory xmlFactoryObject = XmlPullParserFactory.newInstance();
                    XmlPullParser myParser = xmlFactoryObject.newPullParser();
                    myParser.setInput(new ByteArrayInputStream(result), null);
                    int event = myParser.getEventType();
                    boolean variant = false;
                    while (event != XmlPullParser.END_DOCUMENT) {
                        String name = myParser.getName();
                        switch (event){
                            case XmlPullParser.START_TAG:
                                if (name.equals("variant")) {
                                    variant = true;
                                }
                                break;

                            case XmlPullParser.TEXT:
                                if (variant) {
                                    mTextView.setText(myParser.getText());
                                }
                                break;

                            case XmlPullParser.END_TAG:
                                if (name.equals("variant")) {
                                    variant = false;
                                }
                                break;
                        }
                        event = myParser.next();
                    }
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                    mTextView.setText("Error: " + e.getMessage());
                }
            }
        }
    };
}
