package com.ginkage.yasearch;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;

public class WebSearch {
    private static String TAG = "WebSearch";
    private static String SEARCH_URL = "https://yandex.ru/search/xml?" +
            "user=ginkage&key=03.12783281:7a4ce2d242c21697a74b02e4b2c74bd0";

    public interface ResultCallback {
        void onSearchResult(String result);
        void onError(String message);
    }

    public void doSearch(final String query, final ResultCallback cb) {
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
                    cb.onSearchResult(result);
                }
                catch (IOException | JSONException e) {
                    cb.onError(e.toString());
                    e.printStackTrace();
                } finally {
                    urlConnection.disconnect();
                }
            }
        }).start();
    }

    private String extractSearchResult(String json) throws JSONException {
        Log.d(TAG, "Search JSON size: " + json.length() + " chars");
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
    }
}
