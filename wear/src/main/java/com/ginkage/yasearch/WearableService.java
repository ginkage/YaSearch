package com.ginkage.yasearch;

import android.content.Intent;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

public class WearableService extends WearableListenerService {
    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        super.onMessageReceived(messageEvent);

        Intent i = new Intent(VoiceActivity.RESULT_ACTION);
        i.putExtra("result", messageEvent.getData());
        sendBroadcast(i);
    }
}
