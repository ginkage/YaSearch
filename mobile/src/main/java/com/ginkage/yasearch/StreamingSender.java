package com.ginkage.yasearch;

import android.util.Log;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class StreamingSender implements DataSender, TCPConnection.Callback {

    private static final String TAG = "StreamingSender";

    private static final int STATE_ERROR = -1;
    private static final int STATE_START = 1;
    private static final int STATE_CONNECT = 2;
    private static final int STATE_UPGRADE = 3;
    private static final int STATE_HANDSHAKE = 4;
    private static final int STATE_SEND = 5;
    private static final int STATE_DONE = 6;
    private static final int STATE_CLOSE = 7;

    private static final String STREAM_HOST = "voice-stream.voicetech.yandex.net";
    private static final String STREAM_PATH = "/asr_partial_checked";
    private static final String STREAM_SERVICE = "websocket";
    private static final String STREAM_AGENT = "KeepAliveClient";
    private static final String STREAM_APP = "YaWear";
    private static final String STREAM_DEVICE = "Android Wear";

    private final Object mBufferLock = new Object();
    private byte[] mBuffer;
    private int mSize;
    private int mExpected;
    private int mState;
    private final TCPConnection mConnection;
    private InputStream mInputStream;
    private Callback mCallback;

    public StreamingSender(InputStream inputStream, Callback callback) {
        mBuffer = null;
        mSize = 0;
        mExpected = 0;
        mState = STATE_START;
        mConnection = new TCPConnection(STREAM_HOST, 80, false, this);
        mInputStream = inputStream;
        mCallback = callback;
    }

    public void start() {
        synchronized (mConnection) {
            mState = STATE_CONNECT;
            mConnection.open();
        }
    }

    @Override
    public void onConnectionEstablished() {
        if (mState == STATE_CONNECT) {
            byte[] data = ("GET " + STREAM_PATH + " HTTP/1.1\r\n" +
                    "User-Agent: " + STREAM_AGENT + "\r\n" +
                    "Host: " + STREAM_HOST + "\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Upgrade: " + STREAM_SERVICE + "\r\n\r\n").getBytes();

            synchronized (mConnection) {
                mState = STATE_UPGRADE;
                mConnection.write(data);
            }
        } else {
            onError("Wrong state: " + mState, null);
        }
    }

    @Override
    public void onDataReceived(byte[] data, int size) {
        writeToBuffer(data, size);

        if (mState == STATE_UPGRADE) {
            String message = getHeader("\r\n\r\n");
            if (message != null) {
                readUpgradeHeader(message);
                onDataReceived(null, 0);
            }
        } else if (mState == STATE_HANDSHAKE || mState == STATE_SEND || mState == STATE_DONE) {
            if (mExpected <= 0) {
                String message = getHeader("\r\n");
                if (message != null) {
                    mExpected = readProtoHeader(message);
                    onDataReceived(null, 0);
                }
            } else if (mSize >= mExpected) {
                byte[] reply = new byte[mExpected];
                readFromBuffer(reply, mExpected);
                mExpected = 0;
                readProtoData(reply);
                onDataReceived(null, 0);
            }
        } else {
            onError("Wrong state: " + mState, null);
        }
    }

    private void readUpgradeHeader(String message) {
        if (message.startsWith("HTTP/1.1 101 Switching Protocols")) {
            onConnectionUpgrade();
        } else {
            onError("Unexpected response: " + message, null);
        }
    }

    private int readProtoHeader(String message) {
        if (message.startsWith("HTTP")) {
            onError("Bad handshake reply: " + message, null);
        } else {
            try {
                int size = Integer.parseInt(message, 16);
                if (size > 0) {
                    return size;
                } else {
                    onError("Strange header: " + message, null);
                }
            } catch (NumberFormatException e) {
                onError("Error parsing message header: " + message, e);
            }
        }
        return 0;
    }

    private void readProtoData(byte[] reply) {
        if (mState == STATE_HANDSHAKE) {
            readHandshake(reply);
        } else {
            readDataResponse(reply);
        }
    }

    private void readHandshake(byte[] reply) {
        try {
            VoiceProxy.ConnectionResponse response =
                    VoiceProxy.ConnectionResponse.parseFrom(reply);

            if (response.getResponseCode() ==
                    VoiceProxy.ConnectionResponse.ResponseCode.OK) {
                synchronized (mConnection) {
                    mState = STATE_SEND;
                }
                startSendingData();
            } else {
                onError("Bad response code: "
                        + response.getResponseCode().getNumber(), null);
            }
        } catch (InvalidProtocolBufferException e) {
            onError("Error parsing protobuf", e);
        }
    }

    private void sendData(VoiceProxy.AddData addData) {
        int size = addData.getSerializedSize();
        byte[] hdr = String.format("%x\r\n", size).getBytes();
        byte[] msg = concat(hdr, hdr.length, addData.toByteArray(), size);

        if (msg != null) {
            synchronized (mConnection) {
                mConnection.write(msg);
            }
        }
    }

    private void startSendingData() {
        mCallback.onChannelReady();
        (new Thread("StreamingSender") {
            @Override
            public void run() {
                try {
                    Log.i(TAG, "Start sending data");
                    int len;
                    byte[] buffer = new byte[BUFFER_SIZE];
                    while ((len = mInputStream.read(buffer)) != -1) {
                        sendData(VoiceProxy.AddData.newBuilder()
                                .setAudioData(ByteString.copyFrom(buffer, 0, len))
                                .setLastChunk(false)
                                .build());
                    }
                    sendData(VoiceProxy.AddData.newBuilder()
                            .setLastChunk(true)
                            .build());
                    synchronized (mConnection) {
                        mState = STATE_DONE;
                        mConnection.finish();
                    }
                } catch (IOException e) {
                    onError("Error sending data", e);
                }
            }
        }).start();
    }

    private void readDataResponse(byte[] reply) {
        try {
            VoiceProxy.AddDataResponse response =
                    VoiceProxy.AddDataResponse.parseFrom(reply);

            if (response.getResponseCode() ==
                    VoiceProxy.ConnectionResponse.ResponseCode.OK) {
                processDataResponse(response);
            } else {
                onError("Bad response code: "
                        + response.getResponseCode().getNumber(), null);
            }
        } catch (InvalidProtocolBufferException e) {
            onError("Error parsing protobuf", e);
        }
    }

    private void processDataResponse(VoiceProxy.AddDataResponse response) {
        boolean endOfUtt = false;
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
            mCallback.onResult(bestResult, false);
            synchronized (mConnection) {
                mState = STATE_CLOSE;
                mConnection.close();
            }
        } else if (bestResult != null) {
            mCallback.onResult(bestResult, true);
        }
    }

    private void onConnectionUpgrade() {
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
        byte[] hdr = String.format("%x\r\n", size).getBytes();
        byte[] msg = concat(hdr, hdr.length, request.toByteArray(), size);

        if (msg != null) {
            synchronized (mConnection) {
                mState = STATE_HANDSHAKE;
                mConnection.write(msg);
            }
        } else {
            onError("Got empty response", null);
        }
    }

    private void onError(String message, Exception e) {
        if (mState == STATE_CLOSE || mState == STATE_ERROR) {
            return;
        }
        if (e != null) {
            Log.e(TAG, message, e);
        } else {
            Log.e(TAG, message);
        }
        synchronized (mConnection) {
            mState = STATE_ERROR;
            mConnection.close();
        }
        mCallback.onError(message);
    }

    @Override
    public void onDataSent(long size) {
    }

    @Override
    public void onNetworkConnectionError(Exception e, int code) {
        if (e != null) {
            onError("Connection error", e);
        }
    }

    private String getHeader(String suffix) {
        byte[] end = suffix.getBytes();
        int pos = search(mBuffer, mSize, end, end.length);
        if (pos >= 0) {
            pos += end.length;
            byte[] reply = new byte[pos];
            readFromBuffer(reply, pos);
            return new String(reply, 0, pos - end.length);
        }
        return null;
    }

    private void writeToBuffer(byte[] data, int size) {
        synchronized (mBufferLock) {
            mBuffer = concat(mBuffer, mSize, data, size);
            mSize += size;
        }
    }

    private void readFromBuffer(byte[] data, int size) {
        synchronized (mBufferLock) {
            mBuffer = trim(mBuffer, mSize, data, size);
            mSize -= size;
        }
    }

    private byte[] concat(byte[] a, int aLen, byte[] b, int bLen) {
        if (bLen == 0) {
            return a;
        }

        byte[] c = new byte[aLen + bLen];
        if (aLen > 0) {
            System.arraycopy(a, 0, c, 0, aLen);
        }
        if (bLen > 0) {
            System.arraycopy(b, 0, c, aLen, bLen);
        }
        return c;
    }

    private byte[] trim(byte[] from, int size, byte[] to, int len) {
        if (to != null) {
            System.arraycopy(from, 0, to, 0, len);
        }
        size -= len;
        if (size == 0) {
            return null;
        }
        byte[] c = new byte[size];
        System.arraycopy(from, len, c, 0, size);
        return c;
    }

    private int search(byte[] haystack, int size, byte[] needle, int len) {
        for (int i = 0; i <= size - len; i++) {
            boolean match = true;
            for (int j = 0; j < len && match; j++) {
                if (haystack[i + j] != needle[j]) {
                    match = false;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

}
