package com.ginkage.yasearch;

import java.util.UUID;

public interface DataSender {

    String UUID_KEY = UUID.randomUUID().toString().replaceAll("-", "");
    String API_KEY = "a003a72e-e08a-4176-89a6-f46c77c8b2ea";
    String FORMAT = "audio/x-speex"; // "audio/x-pcm;bit=16;rate=16000";
    String TOPIC = "queries";
    String LANG = "ru-RU";
    int BUFFER_SIZE = 5120;

    interface Callback {
        void onChannelReady();
        void onResult(String result, boolean partial);
        void onError(String message);
    }

    void start();

}
