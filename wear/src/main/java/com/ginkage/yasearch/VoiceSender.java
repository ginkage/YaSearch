package com.ginkage.yasearch;

import android.content.Context;
import android.os.AsyncTask;
import android.support.annotation.NonNull;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.wearable.CapabilityApi;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.Channel;
import com.google.android.gms.wearable.ChannelApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;

public class VoiceSender {
    public interface SetupResultListener {
        /**
         * Result of the channel opening attempt.
         * @param stream The stream to write to, or {@code null} if there was an error.
         * @param message The error text, if any.
         */
        void onResult(OutputStream stream, String message);
    }

    private static final String YASK_CAPABILITY_NAME = "yandex_speech_kit";
    private static final String YASK_PATH = "/yask";

    private static final ResultCallback<Status> EMPTY_CALLBACK =
            new ResultCallback<Status>() {
                @Override
                public void onResult(@NonNull Status result) {
                }
            };

    private final CapabilityApi.CapabilityListener mCapabilityListener =
            new CapabilityApi.CapabilityListener() {
                @Override
                public void onCapabilityChanged(CapabilityInfo capabilityInfo) {
                    updateYaskCapability(capabilityInfo);
                }
            };

    private Channel mOutputChannel;
    private OutputStream mDataStream;
    private String mYaskNodeId;

    private final GoogleApiClient mGoogleApiClient;
    private final SetupResultListener mSetupResult;

    public VoiceSender(Context context, SetupResultListener setupResult) {
        mGoogleApiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API)
                .build();
        mSetupResult = setupResult;
    }

    public void setupVoiceChannel() {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                if (mGoogleApiClient.blockingConnect().isSuccess()) {
                    CapabilityApi.GetCapabilityResult result =
                            Wearable.CapabilityApi.getCapability(
                                    mGoogleApiClient, YASK_CAPABILITY_NAME,
                                    CapabilityApi.FILTER_REACHABLE).await();

                    if (result.getStatus().isSuccess()) {
                        updateYaskCapability(result.getCapability());

                        Wearable.CapabilityApi.addCapabilityListener(mGoogleApiClient,
                                mCapabilityListener, YASK_CAPABILITY_NAME)
                                .setResultCallback(EMPTY_CALLBACK);
                    } else {
                        mSetupResult.onResult(null, "Failed to get capability");
                    }
                }
                return null;
            }
        }.execute();
    }

    private void shutdownVoiceChannel() {
        closeChannel();

        Wearable.CapabilityApi.removeCapabilityListener(
                mGoogleApiClient, mCapabilityListener, YASK_CAPABILITY_NAME)
                .setResultCallback(EMPTY_CALLBACK);

        mGoogleApiClient.disconnect();
    }

    private void updateYaskCapability(CapabilityInfo capabilityInfo) {
        closeChannel();
        Set<Node> connectedNodes = capabilityInfo.getNodes();
        String nodeId = pickBestNodeId(connectedNodes);
        if (nodeId != null) {
            openChannel(nodeId);
        } else {
            mSetupResult.onResult(null, "No connection");
        }
    }

    private String pickBestNodeId(Set<Node> nodes) {
        String bestNodeId = null;
        // Find a nearby node or pick one arbitrarily
        for (Node node : nodes) {
            if (node.isNearby()) {
                return node.getId();
            }
            bestNodeId = node.getId();
        }
        return bestNodeId;
    }

    private void openChannel(final String nodeId) {
        Wearable.ChannelApi.openChannel(mGoogleApiClient, nodeId, YASK_PATH)
                .setResultCallback(new ResultCallback<ChannelApi.OpenChannelResult>() {
                    @Override
                    public void onResult(@NonNull ChannelApi.OpenChannelResult result) {
                        if (result.getStatus().isSuccess()) {
                            mOutputChannel = result.getChannel();
                            getStream();
                        } else {
                            mSetupResult.onResult(null, "Failed to open channel");
                        }
                    }
                });

        mYaskNodeId = nodeId;
    }

    private void getStream() {
        mOutputChannel.getOutputStream(mGoogleApiClient)
                .setResultCallback(new ResultCallback<Channel.GetOutputStreamResult>() {
                    @Override
                    public void onResult(@NonNull Channel.GetOutputStreamResult result) {
                        if (result.getStatus().isSuccess()) {
                            mDataStream = result.getOutputStream();
                            mSetupResult.onResult(mDataStream, "Connected");
                        } else {
                            mSetupResult.onResult(null, "Failed to get output stream");
                        }
                    }
                });
    }

    private void closeChannel() {
        mYaskNodeId = null;

        if (mDataStream != null) {
            try {
                mDataStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mDataStream = null;
        }

        if (mOutputChannel != null) {
            mOutputChannel.close(mGoogleApiClient).setResultCallback(EMPTY_CALLBACK);
            mOutputChannel = null;
        }
    }
}
