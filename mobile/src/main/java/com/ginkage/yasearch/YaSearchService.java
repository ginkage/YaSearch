package com.ginkage.yasearch;

import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.util.Log;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Channel;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import ru.yandex.speechkit.SpeechKit;

public class YaSearchService extends WearableListenerService {
    private static String TAG = "YaSearchService";
    private static String API_KEY = "a003a72e-e08a-4176-89a6-f46c77c8b2ea";
    private static String UUID =    "ae1669bbf297462ba7dc89da0213b401";
    private static String SEARCH_URL = "https://yandex.ru/search/xml?" +
            "user=ginkage&key=03.12783281:7a4ce2d242c21697a74b02e4b2c74bd0";

    private void receiveData(final InputStream inputStream) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int len;
                int bufferSize = 4096;
                byte[] buffer = new byte[bufferSize];

                Log.i(TAG, "Start getting data from mic");
                ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
                try {
                    while ((len = inputStream.read(buffer)) != -1) {
                        byteBuffer.write(buffer, 0, len);
                    }
                    Log.i(TAG, "Received from the mic: " + byteBuffer.size() + " bytes");
                }
                catch (IOException e) {
                    e.printStackTrace();
                    return;
                }

                URL url;
                try {
                    url = new URL("http://asr.yandex.net/asr_xml?" +
                            "uuid=" + UUID + "&key=" + API_KEY +
                            "&topic=queries&lang=ru-RU");
                    Log.i(TAG, "Created URL");
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                    return;
                }

                HttpURLConnection urlConnection;
                try {
                    urlConnection = (HttpURLConnection) url.openConnection();
                    Log.i(TAG, "Opened the connection");
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }

                try {
                    urlConnection.setConnectTimeout(30000);
                    urlConnection.setReadTimeout(30000);
                    urlConnection.setDoOutput(true);
                    urlConnection.setRequestMethod("POST");
                    urlConnection.setRequestProperty("Content-Type",
                            "audio/x-pcm;bit=16;rate=16000");
                    urlConnection.setFixedLengthStreamingMode(byteBuffer.size());
//                    urlConnection.setChunkedStreamingMode(0);

                    OutputStream out = new BufferedOutputStream(urlConnection.getOutputStream());
                    out.write(byteBuffer.toByteArray());
                    out.close();

                    Log.i(TAG, "Start receiving data");
                    byteBuffer.reset();
                    InputStream in = new BufferedInputStream(urlConnection.getInputStream());
                    while ((len = in.read(buffer)) != -1) {
                        byteBuffer.write(buffer, 0, len);
                    }

                    Log.i(TAG, "Received: " + byteBuffer.toString());
                }
                catch (IOException e) {
                    e.printStackTrace();
                }
                finally {
                    urlConnection.disconnect();
                }
            }
        }).start();
    }

    @Override
    public void onChannelOpened(Channel channel) {
        new AsyncTask<Channel, Void, Void>() {
            @Override
            protected Void doInBackground(Channel... params) {
                Log.i(TAG, "Created the client");
                final GoogleApiClient googleApiClient =
                        new GoogleApiClient.Builder(YaSearchService.this)
                                .addApi(Wearable.API)
                                .build();
                if (googleApiClient.blockingConnect().isSuccess()) {
                    Log.i(TAG, "Connected to the client");
                    final Channel channel = params[0];
                    channel.getInputStream(googleApiClient).setResultCallback(
                            new ResultCallback<Channel.GetInputStreamResult>() {
                                @Override
                                public void onResult(@NonNull Channel.GetInputStreamResult result) {
                                    if (result.getStatus().isSuccess()) {
                                        Log.i(TAG, "Got an input stream");
                                        receiveData(result.getInputStream());
                                    } else {
                                        Log.e(TAG, "Failed to get input stream");
                                    }
                                }
                            });
                } else {
                    Log.e(TAG, "Failed to connect");
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
}
