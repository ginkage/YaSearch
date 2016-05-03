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

import java.io.InputStream;

public class YaSearchService extends WearableListenerService {

    private static final String TAG = "YaSearchService";

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

    private void receiveData(
            final Channel channel,
            final GoogleApiClient googleApiClient,
            final String nodeId,
            final InputStream inputStream) {
        new StreamingSender(inputStream, new DataSender.Callback() {
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
                                                result.getInputStream());
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
