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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;

public class YaSearchService extends WearableListenerService {
    private static String TAG = "YaSearchService";
    private static String API_KEY = "a003a72e-e08a-4176-89a6-f46c77c8b2ea";
    private static String UUID =    "ae1669bbf297462ba7dc89da0213b401";
    private static String SEARCH_URL = "https://yandex.ru/search/xml?" +
            "user=ginkage&key=03.12783281:7a4ce2d242c21697a74b02e4b2c74bd0";

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

    public void doSearch(final GoogleApiClient googleApiClient,
                           final String nodeId,
                           final String query) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                URL url;
                try {
                    url = new URL("http://api.duckduckgo.com/"
                            + "?q=" + URLEncoder.encode(query, "UTF-8")
                            + "&format=json&pretty=1");
                    Log.i(TAG, "Created URL");
                } catch (MalformedURLException | UnsupportedEncodingException e) {
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
                    int len;
                    int bufferSize = 4096;
                    byte[] buffer = new byte[bufferSize];

                    Log.i(TAG, "Start receiving data");
                    ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
                    InputStream in = new BufferedInputStream(urlConnection.getInputStream());
                    while ((len = in.read(buffer)) != -1) {
                        byteBuffer.write(buffer, 0, len);
                    }

                    Log.i(TAG, "Received: " + byteBuffer.toString());
                    String result = extractSearchResult(byteBuffer.toString());
                    Wearable.MessageApi.sendMessage(googleApiClient, nodeId,
                            "/yask/result", result.getBytes())
                            .setResultCallback(MESSAGE_CALLBACK);
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

    private String extractSearchResult(String json) throws IOException {
        Log.d(TAG, "Search JSON size: " + json.length() + " chars");
        try {
            JSONObject root = new JSONObject(json);
            String abstr = root.getString("Abstract");
            if (abstr != null && !abstr.isEmpty()) {
                return abstr;
            }
            JSONArray arrayOfResults = root.getJSONArray("RelatedTopics");
            if (arrayOfResults != null
                    && arrayOfResults.length() > 0
                    && arrayOfResults.getJSONObject(0) != null
                    && arrayOfResults.getJSONObject(0).getString("Text") != null) {
                JSONObject firstEntry = arrayOfResults.getJSONObject(0);
                return firstEntry.getString("Text").isEmpty()
                        ? "no search results"
                        : firstEntry.getString("Text");
            }

            return "no search results";
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private void processRecognition(final GoogleApiClient googleApiClient,
                                    final String nodeId,
                                    final byte[] data) {
        try {
            XmlPullParserFactory xmlFactoryObject = XmlPullParserFactory.newInstance();
            XmlPullParser myParser = xmlFactoryObject.newPullParser();
            myParser.setInput(new ByteArrayInputStream(data), null);

            int event = myParser.getEventType();
            boolean variant = false;
            String bestVariant = null;
            String curVariant = null;
            double bestConfidence = -1;
            double curConfidence = -2;

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
                            if (curConfidence > bestConfidence && curVariant != null) {
                                bestVariant = curVariant;
                                bestConfidence = curConfidence;
                            }
                        }
                        break;
                }
                event = myParser.next();
            }

            if (bestVariant != null) {
                Wearable.MessageApi.sendMessage(googleApiClient, nodeId,
                        "/yask/result", bestVariant.getBytes())
                        .setResultCallback(MESSAGE_CALLBACK);
//                doSearch(googleApiClient, nodeId, bestVariant);
            }
        } catch (XmlPullParserException | IOException e) {
            e.printStackTrace();
        }
    }

    private void receiveData(
            final GoogleApiClient googleApiClient,
            final String nodeId,
            final InputStream inputStream) {
        new Thread(new Runnable() {
            @Override
            public void run() {
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
                    int len;
                    int bufferSize = 4096;
                    byte[] buffer = new byte[bufferSize];

                    urlConnection.setDoOutput(true);
                    urlConnection.setRequestMethod("POST");
                    urlConnection.setRequestProperty("Content-Type",
                            "audio/x-pcm;bit=16;rate=16000");
                    urlConnection.setChunkedStreamingMode(bufferSize);

                    Log.i(TAG, "Start sending data from mic");
                    OutputStream out = new BufferedOutputStream(urlConnection.getOutputStream());
                    while ((len = inputStream.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                    out.close();

                    Log.i(TAG, "Start receiving data");
                    ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
                    InputStream in = new BufferedInputStream(urlConnection.getInputStream());
                    while ((len = in.read(buffer)) != -1) {
                        byteBuffer.write(buffer, 0, len);
                    }

                    Log.i(TAG, "Received: " + byteBuffer.toString());

                    processRecognition(googleApiClient, nodeId, byteBuffer.toByteArray());
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
        final String nodeId = channel.getNodeId();
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
                                        receiveData(googleApiClient, nodeId,
                                                result.getInputStream());
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
}
