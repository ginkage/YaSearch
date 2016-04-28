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

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.util.List;
import java.util.UUID;

public class YaSearchService extends WearableListenerService {

    private static final String TAG = "YaSearchService";
    private static final String UUID_KEY = UUID.randomUUID().toString().replaceAll("-", "");
    private static final String API_KEY = "a003a72e-e08a-4176-89a6-f46c77c8b2ea";
    private static final String FORMAT = "audio/x-pcm;bit=16;rate=16000";
    private static final String TOPIC = "queries";
    private static final String LANG = "en-US";
    private static final String COMMON_HOST = "asr.yandex.net";
    private static final String COMMON_PATH = "/asr_xml";
    private static final String STREAM_HOST = "voice-stream.voicetech.yandex.net";
    private static final String STREAM_PATH = "/asr_partial_checked";
    private static final String STREAM_SERVICE = "websocket";
    private static final String STREAM_AGENT = "KeepAliveClient";
    private static final String STREAM_APP = "YaWear";
    private static final String STREAM_DEVICE = "Android Wear";
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
            return bestResult;
        } else if (bestResult != null) {
            sendPartialResult(googleApiClient, nodeId, bestResult);
        }

        return null;
    }

    private String sendStreamingData(GoogleApiClient googleApiClient,
                                     String nodeId,
                                     InputStream inputStream,
                                     InputStream in,
                                     OutputStream out) throws IOException {
        int len;
        byte[] buffer = new byte[BUFFER_SIZE];
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
        out.write(("GET " + STREAM_PATH + " HTTP/1.1\r\n" +
                "User-Agent: " + STREAM_AGENT + "\r\n" +
                "Host: " + STREAM_HOST + "\r\n" +
                "Connection: Upgrade\r\n" +
                "Upgrade: " + STREAM_SERVICE + "\r\n\r\n").getBytes());
        out.flush();

        String reply = getResponse("", in);
        if (!reply.startsWith("HTTP/1.1 101 Switching Protocols")) {
            Log.e(TAG, reply);
            return false;
        }

        VoiceProxy.ConnectionRequest request = VoiceProxy.ConnectionRequest.newBuilder()
                .setSpeechkitVersion("")
                .setServiceName(STREAM_SERVICE)
                .setUuid(UUID_KEY)
                .setApiKey(API_KEY)
                .setApplicationName(STREAM_APP)
                .setDevice(STREAM_DEVICE)
                .setCoords("0, 0")
                .setTopic(TOPIC)
                .setLang(LANG)
                .setFormat(FORMAT)
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
            socket = new Socket(STREAM_HOST, 80);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());

            if (startStreamingMode(in, out)) {
                // We can't go back once we start reading data from inputStream.
                result = true;

                sendChannelReady(googleApiClient, nodeId);

                Log.i(TAG, "Send data from mic, streaming mode");
                String message = sendStreamingData(googleApiClient, nodeId, inputStream, in, out);

                Log.i(TAG, "Send result");
                if (message == null) {
                    sendError(googleApiClient, nodeId, "Sorry, didn't catch that");
                } else {
                    sendFinalResult(googleApiClient, nodeId, message);
                }
            }
        } catch (IOException e) {
            Log.i(TAG, "Streaming mode failed", e);
        }

        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                Log.i(TAG, "Streaming mode failed", e);
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
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!tryStreamingMode(googleApiClient, nodeId, inputStream)) {
                    if (!tryCommonMode(googleApiClient, nodeId, inputStream)) {
                        Log.i(TAG, "Sent an error");
                        sendError(googleApiClient, nodeId, "Error: Couldn't connect to server");
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
