package com.ginkage.yasearch;

import android.content.Context;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Channel;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import ru.yandex.speechkit.*;

public class YaSearchService extends WearableListenerService implements RecognizerListener {
    private static String API_KEY = "f9c9a742-8c33-4961-9217-f622744b6063";

    private void receiveData(final InputStream inputStream) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();

                int len;
                int bufferSize = 4096;
                byte[] buffer = new byte[bufferSize];

                try {
                    while ((len = inputStream.read(buffer)) != -1) {
                        byteBuffer.write(buffer, 0, len);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }

                processData(byteBuffer.toByteArray());
            }
        }).start();
    }

    @Override
    public void onChannelOpened(Channel channel) {
        new AsyncTask<Channel, Void, Void>() {
            @Override
            protected Void doInBackground(Channel... params) {
                final GoogleApiClient googleApiClient =
                        new GoogleApiClient.Builder(YaSearchService.this)
                                .addApi(Wearable.API)
                                .build();
                if (googleApiClient.blockingConnect().isSuccess()) {
                    final Channel channel = params[0];
                    channel.getInputStream(googleApiClient).setResultCallback(
                            new ResultCallback<Channel.GetInputStreamResult>() {
                                @Override
                                public void onResult(@NonNull Channel.GetInputStreamResult result) {
                                    if (result.getStatus().isSuccess()) {
                                        receiveData(result.getInputStream());
                                    }
                                }
                            });
                }
                return null;
            }
        }.execute(channel);
    }

    @Override
    public void onChannelClosed(Channel channel, int closeReason, int appSpecificErrorCode) {
        super.onChannelClosed(channel, closeReason, appSpecificErrorCode);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        SpeechKit.getInstance().configure(getApplicationContext(), API_KEY);
    }

    private void processData(byte[] data) {
        Recognizer recognizer = Recognizer.create(
                Recognizer.Language.RUSSIAN, Recognizer.Model.NOTES, this);
    }

    @Override
    public void onRecordingBegin(Recognizer recognizer) {

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
    public void onSoundDataRecorded(Recognizer recognizer, byte[] bytes) {

    }

    @Override
    public void onPowerUpdated(Recognizer recognizer, float v) {

    }

    @Override
    public void onPartialResults(Recognizer recognizer, Recognition recognition, boolean b) {

    }

    @Override
    public void onRecognitionDone(Recognizer recognizer, Recognition recognition) {

    }

    @Override
    public void onError(Recognizer recognizer, ru.yandex.speechkit.Error error) {

    }
}
