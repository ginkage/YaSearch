package com.ginkage.yasearch;

import android.util.Log;

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

public class XMLSender implements DataSender {

    private static final String TAG = "XMLSender";

    private static final String COMMON_HOST = "asr.yandex.net";
    private static final String COMMON_PATH = "/asr_xml";

    private InputStream mInputStream;
    private Callback mCallback;

    public XMLSender(InputStream inputStream, Callback callback) {
        mInputStream = inputStream;
        mCallback = callback;
    }

    public void start() {
        (new Thread("XMLSender") {
            @Override
            public void run() {
                HttpURLConnection urlConnection = null;

                try {
                    URL url = new URL("http://" + COMMON_HOST + COMMON_PATH +
                            "?uuid=" + UUID_KEY + "&key=" + API_KEY +
                            "&topic=" + TOPIC + "&lang=" + LANG);
                    urlConnection = (HttpURLConnection) url.openConnection();
                    urlConnection.setDoOutput(true);
                    urlConnection.setRequestMethod("POST");
                    urlConnection.setRequestProperty("Content-Type", FORMAT);
                    urlConnection.setChunkedStreamingMode(BUFFER_SIZE);

                    InputStream in = new BufferedInputStream(urlConnection.getInputStream());
                    OutputStream out = new BufferedOutputStream(urlConnection.getOutputStream());

                    mCallback.onChannelReady();

                    Log.i(TAG, "Send data from mic, common mode");
                    int len;
                    byte[] buffer = new byte[BUFFER_SIZE];
                    while ((len = mInputStream.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }

                    Log.i(TAG, "Read response");
                    String response = processXMLResult(in);

                    Log.i(TAG, "Send result");
                    if (response == null) {
                        mCallback.onError("Sorry, didn't catch that");
                    } else {
                        mCallback.onResult(response, false);
                    }
                } catch (IOException | XmlPullParserException e) {
                    Log.i(TAG, "Common mode failed", e);
                }

                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
            }
        }).start();
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

}
