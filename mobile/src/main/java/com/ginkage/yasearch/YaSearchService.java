package com.ginkage.yasearch;

import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.util.Log;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.Result;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.wearable.Channel;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

public class YaSearchService extends WearableListenerService {

    private static final String TAG = "YaSearchService";
    private static final String UUID_KEY = UUID.randomUUID().toString().replaceAll("-", "");
    private static final String API_KEY = "a003a72e-e08a-4176-89a6-f46c77c8b2ea";
    private static final String FORMAT = "audio/x-pcm;bit=16;rate=16000";
    private static final String TOPIC = "queries";
    private static final String LANG = "en-EN";
    private static final String COMMON_HOST = "asr.yandex.net";
    private static final String COMMON_PATH = "/asr_xml";
    private static final int BUFFER_SIZE = 5120;

    private static final ResultCallback<Status> EMPTY_CALLBACK =
            new ResultCallback<Status>() {
                @Override
                public void onResult(@NonNull Status result) {
                    if (!result.getStatus().isSuccess()) {
                        Log.e(TAG, "Failed: " + result.getStatusMessage());
                    }
                }
            };

    private static final ResultCallback<Result> MESSAGE_CALLBACK =
            new ResultCallback<Result>() {
                @Override
                public void onResult(@NonNull Result result) {
                    if (!result.getStatus().isSuccess()) {
                        Log.e(TAG, "Failed: " + result.getStatus());
                    }
                }
            };

    private void sendMessage(GoogleApiClient googleApiClient, String nodeId,
                             String path, String message) {
        Wearable.MessageApi.sendMessage(googleApiClient, nodeId, "/yask/" + path,
                (message == null ? null : message.getBytes()))
                .setResultCallback(MESSAGE_CALLBACK);
    }

    private void sendChannelReady(GoogleApiClient googleApiClient, String nodeId) {
        sendMessage(googleApiClient, nodeId, "channel_ready", null);
    }

    private void sendPartialResult(GoogleApiClient googleApiClient, String nodeId, String result) {
        sendMessage(googleApiClient, nodeId, "partial", result);
    }

    private void sendFinalResult(GoogleApiClient googleApiClient, String nodeId, String result) {
        sendMessage(googleApiClient, nodeId, "result", result);
    }

    private void sendError(GoogleApiClient googleApiClient, String nodeId, String error) {
        sendMessage(googleApiClient, nodeId, "error", error);
    }

    private String processXMLResult(InputStream in) throws IOException, XmlPullParserException {
        XmlPullParserFactory xmlFactoryObject = XmlPullParserFactory.newInstance();
        XmlPullParser myParser = xmlFactoryObject.newPullParser();
        myParser.setInput(in, null);

        int event = myParser.getEventType();
        boolean variant = false;
        String bestVariant = null;
        String curVariant = null;
        double bestConfidence = 0;
        double curConfidence = 0;

        while (event != XmlPullParser.END_DOCUMENT) {
            String name = myParser.getName();
            switch (event){
                case XmlPullParser.START_TAG:
                    if (name.equals("variant")) {
                        variant = true;
                        curVariant = null;
                        curConfidence = -2;
                        String confidence = myParser.getAttributeValue(null, "confidence");
                        if (confidence != null) {
                            curConfidence = Double.parseDouble(confidence);
                        }
                    } else if (name.equals("recognitionResults")) {
                        String success = myParser.getAttributeValue(null, "success");
                        if (Integer.parseInt(success) == 0) {
                            return null;
                        }
                    }
                    break;

                case XmlPullParser.TEXT:
                    if (variant) {
                        curVariant = myParser.getText();
                    }
                    break;

                case XmlPullParser.END_TAG:
                    if (name.equals("variant")) {
                        variant = false;
                        Log.i(TAG, "confidence: " + curConfidence + " : " + curVariant);
                        if (curVariant != null) {
                            if (bestVariant == null || curConfidence > bestConfidence) {
                                bestVariant = curVariant;
                                bestConfidence = curConfidence;
                            }
                        }
                    }
                    break;
            }
            event = myParser.next();
        }

        return bestVariant;
    }

    private void sendCommonData(InputStream inputStream, OutputStream out)
            throws IOException, XmlPullParserException {
        int len;
        byte[] buffer = new byte[BUFFER_SIZE];
        while ((len = inputStream.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }
    }

    private boolean tryCommonMode(GoogleApiClient googleApiClient,
                                  String nodeId,
                                  InputStream inputStream) {
        HttpURLConnection urlConnection = null;
        boolean result = false;

        try {
            URL url = new URL("http://" + COMMON_HOST + COMMON_PATH +
                    "?uuid=" + UUID_KEY + "&key=" + API_KEY + "&topic=" + TOPIC + "&lang=" + LANG);
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setDoOutput(true);
            urlConnection.setRequestMethod("POST");
            urlConnection.setRequestProperty("Content-Type", FORMAT);
            urlConnection.setChunkedStreamingMode(BUFFER_SIZE);

            InputStream in = new BufferedInputStream(urlConnection.getInputStream());
            OutputStream out = new BufferedOutputStream(urlConnection.getOutputStream());

            // We can't go back once we start reading data from inputStream.
            result = true;

            sendChannelReady(googleApiClient, nodeId);

            Log.i(TAG, "Send data from mic, common mode");
            sendCommonData(inputStream, out);

            Log.i(TAG, "Read response");
            String response = processXMLResult(in);

            Log.i(TAG, "Send result");
            if (response == null) {
                sendError(googleApiClient, nodeId, "Sorry, didn't catch that");
            } else {
                sendFinalResult(googleApiClient, nodeId, response);
            }
        } catch (IOException | XmlPullParserException e) {
            Log.i(TAG, "Common mode failed", e);
        }

        if (urlConnection != null) {
            urlConnection.disconnect();
        }

        return result;
    }

    private void receiveData(
            final Channel channel,
            final GoogleApiClient googleApiClient,
            final String nodeId,
            final InputStream inputStream) {
        new StreamingSender(inputStream, new StreamingSender.Callback() {
            @Override
            public void onChannelReady() {
                sendChannelReady(googleApiClient, nodeId);
            }

            @Override
            public void onResult(String result, boolean partial) {
                if (partial) {
                    sendPartialResult(googleApiClient, nodeId, result);
                } else {
                    sendFinalResult(googleApiClient, nodeId,
                            (result != null) ? result : "Sorry, didn't catch that");

                    Log.i(TAG, "Closing the channel");
                    channel.close(googleApiClient).setResultCallback(EMPTY_CALLBACK);
                    googleApiClient.disconnect();
                }
            }

            @Override
            public void onError(String message) {
                sendError(googleApiClient, nodeId, message);

                Log.i(TAG, "Closing the channel");
                channel.close(googleApiClient).setResultCallback(EMPTY_CALLBACK);
                googleApiClient.disconnect();
            }
        }).start();
    }

    @Override
    public void onChannelOpened(Channel channel) {
        Log.i(TAG, "Opened the channel");
        final String nodeId = channel.getNodeId();
        new AsyncTask<Channel, Void, Void>() {
            @Override
            protected Void doInBackground(Channel... params) {
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
                                        receiveData(channel, googleApiClient, nodeId,
                                                new BufferedInputStream(result.getInputStream()));
                                    } else {
                                        Log.e(TAG, "Failed to get input stream");
                                        channel.close(googleApiClient)
                                                .setResultCallback(EMPTY_CALLBACK);
                                        googleApiClient.disconnect();
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
}
