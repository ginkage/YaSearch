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
import com.google.protobuf.ByteString;

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
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;

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
            double bestConfidence = -Double.MAX_VALUE;
            double curConfidence = -Double.MAX_VALUE;

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
                                bestVariant = "Sorry, didn't catch that";
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

            if (bestVariant == null) {
                bestVariant = "Couldn't parse results";
            }

            Wearable.MessageApi.sendMessage(googleApiClient, nodeId,
                    "/yask/result", bestVariant.getBytes())
                    .setResultCallback(MESSAGE_CALLBACK);
//            doSearch(googleApiClient, nodeId, bestVariant);
        } catch (XmlPullParserException | IOException e) {
            e.printStackTrace();
        }
    }

    private String getResponse(InputStream in) throws IOException {
        String response = "";
        while (true) {
            int c = in.read();
            if (c < 0) {
                return response;
            }
            response += (char) c;
            if (response.endsWith("\r\n\r\n")) {
                return response;
            }
        }
    }

    private ByteString getMessage(InputStream in) throws IOException {
        String len = "";
        while (true) {
            int c = in.read();
            if (c < 0) {
                return null;
            }
            if (c == '\r') {
                if (in.read() == '\n') {
                    int size = Integer.parseInt(len, 16);
                    byte[] message = new byte[size];
                    int got = in.read(message);
                    if (got < 0) {
                        maybeLog("End of stream was reached");
                        return null;
                    }
                    maybeLog("Got message of size: " + got);
                    return ByteString.copyFrom(message, 0, got);
                } else {
                    maybeLog("Unexpected message format: " + len);
                    return null;
                }
            } else {
                len += (char) c;
            }
        }
    }

    void maybeLog(String message) {
        // Log.i(TAG, message);
    }

    private void sendData(VoiceProxy.AddData addData, OutputStream out) throws IOException {
        int size = addData.getSerializedSize();
        maybeLog("Request size: " + size);

        out.write(String.format("%x\r\n", size).getBytes());
        addData.writeTo(out);
        out.flush();
    }

    private boolean readDataResponse(GoogleApiClient googleApiClient,
                                     String nodeId,
                                     InputStream in)
            throws IOException {
        boolean endOfUtt = false;
        ByteString message = getMessage(in);
        VoiceProxy.AddDataResponse response = VoiceProxy.AddDataResponse.parseFrom(message);
        maybeLog("Response code: " + response.getResponseCode().getNumber());
        if (response.hasEndOfUtt()) {
            maybeLog("EndOfUTT: " + response.getEndOfUtt());
            endOfUtt = response.getEndOfUtt();
        }
        if (response.hasMessagesCount()) {
            maybeLog("Messages: " + response.getMessagesCount());
        }
        List<VoiceProxy.Result> results = response.getRecognitionList();
        maybeLog("Results count: " + results.size());

        String bestResult = null;
        float bestConfidence = 0;
        for (VoiceProxy.Result result : results) {
            float curConfidence = result.getConfidence();
            String curResult = "";
            if (result.hasNormalized()) {
                maybeLog("Normalized: " + result.getNormalized());
                curResult = result.getNormalized();
            } else {
                List<VoiceProxy.Word> words = result.getWordsList();
                maybeLog("Confidence: " + result.getConfidence() + ", words: " + words.size());
                for (VoiceProxy.Word word : words) {
                    maybeLog("Confidence: " + word.getConfidence() + ", word: " + word.getValue());
                    if (curResult.length() > 0) {
                        curResult += " ";
                        curResult += word.getValue();
                    }
                }
            }
            if (bestResult == null || bestConfidence < curConfidence) {
                bestResult = curResult;
                bestConfidence = curConfidence;
            }
        }

        if (endOfUtt && bestResult == null) {
            bestResult = "Sorry, didn't catch that";
        }

        if (bestResult != null) {
            Wearable.MessageApi.sendMessage(googleApiClient, nodeId,
                    (endOfUtt ? "/yask/result" : "/yask/partial"), bestResult.getBytes())
                    .setResultCallback(MESSAGE_CALLBACK);
        }

        return endOfUtt;
    }

    private void sendStreamingData(GoogleApiClient googleApiClient,
                                   String nodeId,
                                   InputStream inputStream,
                                   InputStream in,
                                   OutputStream out)
            throws IOException {
        maybeLog("Start sending data from mic");
        int len;
        int bufferSize = 4096;
        byte[] buffer = new byte[bufferSize];
        while ((len = inputStream.read(buffer)) != -1) {
            sendData(VoiceProxy.AddData.newBuilder()
                    .setAudioData(ByteString.copyFrom(buffer, 0, len))
                    .setLastChunk(false)
                    .build(), out);
            if (readDataResponse(googleApiClient, nodeId, in)) {
                return;
            }
        }

        sendData(VoiceProxy.AddData.newBuilder()
                .setLastChunk(true)
                .build(), out);
        readDataResponse(googleApiClient, nodeId, in);
    }

    private boolean tryStreamingMode(GoogleApiClient googleApiClient,
                                     String nodeId,
                                     InputStream inputStream)
            throws IOException {
        Socket socket = new Socket("asr.yandex.net", 80);
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        out.write(("GET /asr_partial_checked HTTP/1.1\r\n" +
                "User-Agent: yawear\r\n" +
                "Host: asr.yandex.net:80\r\n" +
                "Upgrade: asr_dictation\r\n\r\n").getBytes());
        out.flush();

        String reply = getResponse(in);
        maybeLog("Got response: " + reply);
        if (!reply.startsWith("HTTP/1.1 101 Switching Protocols")) {
            return false;
        }

        VoiceProxy.ConnectionRequest request = VoiceProxy.ConnectionRequest.newBuilder()
                .setSpeechkitVersion("")
                .setServiceName("asr_dictation")
                .setUuid(UUID)
                .setApiKey(API_KEY)
                .setApplicationName("yawear")
                .setDevice("Android Wear")
                .setCoords("0, 0")
                .setTopic("queries")
                .setLang("ru-RU")
                .setFormat("audio/x-pcm;bit=16;rate=16000")
                .build();
        int size = request.getSerializedSize();
        maybeLog("Request size: " + size);

        out.write(String.format("%x\r\n", size).getBytes());
        request.writeTo(out);
        out.flush();

        ByteString message = getMessage(in);
        VoiceProxy.ConnectionResponse response = VoiceProxy.ConnectionResponse.parseFrom(message);
        int code = response.getResponseCode().getNumber();
        String sessionId = response.getSessionId();
        maybeLog("Response code: " + code + ", SessionID: " + sessionId);
        if (response.hasMessage()) {
            maybeLog("Message: " + response.getMessage());
        }

        Wearable.MessageApi.sendMessage(googleApiClient, nodeId,
                "/yask/channel_ready", null)
                .setResultCallback(MESSAGE_CALLBACK);

        // We can't go back once we start reading data from inputStream.
        try {
            sendStreamingData(googleApiClient, nodeId, inputStream, in, out);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return true;
    }

    private void receiveData(
            final GoogleApiClient googleApiClient,
            final String nodeId,
            final InputStream inputStream) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection urlConnection = null;
                try {
                    if (tryStreamingMode(googleApiClient, nodeId, inputStream)) {
                        return;
                    }

                    URL url = new URL("http://asr.yandex.net/asr_xml?" +
                            "uuid=" + UUID + "&key=" + API_KEY +
                            "&topic=queries&lang=ru-RU");
                    Log.i(TAG, "Created URL");

                    urlConnection = (HttpURLConnection) url.openConnection();
                    Log.i(TAG, "Opened the connection");

                    int len;
                    int bufferSize = 4096;
                    byte[] buffer = new byte[bufferSize];

                    urlConnection.setDoOutput(true);
                    urlConnection.setRequestMethod("POST");
                    urlConnection.setRequestProperty("Content-Type",
                            "audio/x-pcm;bit=16;rate=16000");
                    urlConnection.setChunkedStreamingMode(bufferSize);

                    Wearable.MessageApi.sendMessage(googleApiClient, nodeId,
                            "/yask/channel_ready", null)
                            .setResultCallback(MESSAGE_CALLBACK);

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
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    if (urlConnection != null) {
                        urlConnection.disconnect();
                    }
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
