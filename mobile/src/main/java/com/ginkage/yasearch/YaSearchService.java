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
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RunnableFuture;

public class YaSearchService extends WearableListenerService {
    private static String TAG = "YaSearchService";
    private static String API_KEY = "a003a72e-e08a-4176-89a6-f46c77c8b2ea";
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

    private String getResponse(String response, InputStream in) throws IOException {
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
                if (len.startsWith("HTTP")) {
                    Log.e(TAG, getResponse(len, in));
                    return null;
                } else if (in.read() == '\n') {
                    int size = Integer.parseInt(len, 16);
                    byte[] message = new byte[size];
                    int got = in.read(message);
                    if (got < 0) {
                        Log.e(TAG, "End of stream was reached");
                        return null;
                    }
                    return ByteString.copyFrom(message, 0, got);
                } else {
                    Log.e(TAG, "Unexpected message format: " + len);
                    return null;
                }
            } else {
                len += (char) c;
            }
        }
    }

    private void sendData(VoiceProxy.AddData addData, OutputStream out) throws IOException {
        int size = addData.getSerializedSize();
        out.write(String.format("%x\r\n", size).getBytes());
        addData.writeTo(out);
        out.flush();
    }

    private String readDataResponse(GoogleApiClient googleApiClient, String nodeId, InputStream in)
            throws IOException {
        boolean endOfUtt = false;
        ByteString message = getMessage(in);
        VoiceProxy.AddDataResponse response = VoiceProxy.AddDataResponse.parseFrom(message);
        if (response.getResponseCode() != VoiceProxy.ConnectionResponse.ResponseCode.OK) {
            return null;
        }

        if (response.hasEndOfUtt()) {
            endOfUtt = response.getEndOfUtt();
        }
        List<VoiceProxy.Result> results = response.getRecognitionList();

        String bestResult = null;
        float bestConfidence = 0;
        for (VoiceProxy.Result result : results) {
            float curConfidence = result.getConfidence();
            String curResult = "";
            if (result.hasNormalized()) {
                curResult = result.getNormalized();
            } else {
                List<VoiceProxy.Word> words = result.getWordsList();
                for (VoiceProxy.Word word : words) {
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

        if (endOfUtt) {
            if (bestResult == null) {
                bestResult = "Sorry, didn't catch that";
            }
            return bestResult;
        }

        if (bestResult != null) {
            Wearable.MessageApi.sendMessage(googleApiClient, nodeId,
                    "/yask/partial", bestResult.getBytes())
                    .setResultCallback(MESSAGE_CALLBACK);
        }

        return null;
    }

    private String sendStreamingData(GoogleApiClient googleApiClient,
                                     String nodeId,
                                     InputStream inputStream,
                                     InputStream in,
                                     OutputStream out) throws IOException {
        int len;
        int bufferSize = 4096;
        byte[] buffer = new byte[bufferSize];
        while ((len = inputStream.read(buffer)) != -1) {
            sendData(VoiceProxy.AddData.newBuilder()
                    .setAudioData(ByteString.copyFrom(buffer, 0, len))
                    .setLastChunk(false)
                    .build(), out);
            String response = readDataResponse(googleApiClient, nodeId, in);
            if (response != null) {
                Log.i(TAG, "Got response");
                return response;
            }
        }

        sendData(VoiceProxy.AddData.newBuilder()
                .setLastChunk(true)
                .build(), out);
        return readDataResponse(googleApiClient, nodeId, in);
    }

    private boolean startStreamingMode(InputStream in, OutputStream out) throws IOException {
        out.write(("GET /asr_partial_checked HTTP/1.1\r\n" +
                "User-Agent: yawear\r\n" +
                "Host: asr.yandex.net:80\r\n" +
                "Upgrade: asr_dictation\r\n\r\n").getBytes());
        out.flush();

        String reply = getResponse("", in);
        if (!reply.startsWith("HTTP/1.1 101 Switching Protocols")) {
            return false;
        }

        VoiceProxy.ConnectionRequest request = VoiceProxy.ConnectionRequest.newBuilder()
                .setSpeechkitVersion("")
                .setServiceName("asr_dictation")
                .setUuid(UUID.randomUUID().toString().replaceAll("-", ""))
                .setApiKey(API_KEY)
                .setApplicationName("yawear")
                .setDevice("Android Wear")
                .setCoords("0, 0")
                .setTopic("queries")
                .setLang("ru-RU")
                .setFormat("audio/x-pcm;bit=16;rate=16000")
                .build();

        int size = request.getSerializedSize();
        out.write(String.format("%x\r\n", size).getBytes());
        request.writeTo(out);
        out.flush();

        ByteString message = getMessage(in);
        if (message == null) {
            return false;
        }

        VoiceProxy.ConnectionResponse response = VoiceProxy.ConnectionResponse.parseFrom(message);
        return (response.getResponseCode() == VoiceProxy.ConnectionResponse.ResponseCode.OK);
    }

    private boolean tryStreamingMode(GoogleApiClient googleApiClient,
                                     String nodeId,
                                     InputStream inputStream) {
        Socket socket = null;
        boolean result = false;

        try {
            socket = new Socket("asr.yandex.net", 80);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());

            if (startStreamingMode(in, out)) {
                // We can't go back once we start reading data from inputStream.
                result = true;

                Wearable.MessageApi.sendMessage(googleApiClient, nodeId,
                        "/yask/channel_ready", null)
                        .setResultCallback(MESSAGE_CALLBACK);

                Log.i(TAG, "Send data from mic, streaming mode");
                String message = sendStreamingData(googleApiClient, nodeId, inputStream, in, out);

                Log.i(TAG, "Send result");
                Wearable.MessageApi.sendMessage(googleApiClient, nodeId,
                        "/yask/result", message.getBytes())
                        .setResultCallback(MESSAGE_CALLBACK);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return result;
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

        if (bestVariant == null) {
            bestVariant = "Couldn't parse results";
        }

        return bestVariant;
    }

    private void sendCommonData(InputStream inputStream, OutputStream out)
            throws IOException, XmlPullParserException {
        int len;
        byte[] buffer = new byte[4096];
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
            URL url = new URL("http://asr.yandex.net/asr_xml?"
                    + "uuid=" + UUID.randomUUID().toString().replaceAll("-", "")
                    + "&key=" + API_KEY + "&topic=queries&lang=ru-RU");
            Log.i(TAG, "Created URL");

            urlConnection = (HttpURLConnection) url.openConnection();
            Log.i(TAG, "Opened the connection");

            urlConnection.setDoOutput(true);
            urlConnection.setRequestMethod("POST");
            urlConnection.setRequestProperty("Content-Type",
                    "audio/x-pcm;bit=16;rate=16000");
            urlConnection.setChunkedStreamingMode(4096);

            InputStream in = new BufferedInputStream(urlConnection.getInputStream());
            OutputStream out = new BufferedOutputStream(urlConnection.getOutputStream());

            // We can't go back once we start reading data from inputStream.
            result = true;

            Wearable.MessageApi.sendMessage(googleApiClient, nodeId,
                    "/yask/channel_ready", null)
                    .setResultCallback(MESSAGE_CALLBACK);

            Log.i(TAG, "Send data from mic, common mode");
            sendCommonData(inputStream, out);

            Log.i(TAG, "Read response");
            String response = processXMLResult(in);

            Log.i(TAG, "Send result");
            Wearable.MessageApi.sendMessage(googleApiClient, nodeId,
                    "/yask/result", response.getBytes())
                    .setResultCallback(MESSAGE_CALLBACK);
        } catch (IOException | XmlPullParserException e) {
            e.printStackTrace();
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
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!tryStreamingMode(googleApiClient, nodeId, inputStream)) {
                    if (!tryCommonMode(googleApiClient, nodeId, inputStream)) {
                        Log.i(TAG, "Sent an error");
                        Wearable.MessageApi.sendMessage(googleApiClient, nodeId,
                                "/yask/result", "Error: Couldn't connect to server".getBytes())
                                .setResultCallback(MESSAGE_CALLBACK);
                    }
                }

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
