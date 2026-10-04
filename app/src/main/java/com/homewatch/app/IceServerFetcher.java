package com.homewatch.app;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.PeerConnection;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class IceServerFetcher {

    private static final String TAG = "IceServerFetcher";

    public static List<PeerConnection.IceServer> fetch(String iceServersUrl) {
        try {
            URL url = new URL(iceServersUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            conn.disconnect();

            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.getJSONArray("iceServers");
            List<PeerConnection.IceServer> result = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject entry = arr.getJSONObject(i);
                List<String> urls = new ArrayList<>();
                Object urlsField = entry.has("urls") ? entry.get("urls") : null;
                if (urlsField instanceof JSONArray) {
                    JSONArray urlArr = (JSONArray) urlsField;
                    for (int j = 0; j < urlArr.length(); j++) urls.add(urlArr.getString(j));
                } else if (urlsField != null) {
                    urls.add(urlsField.toString());
                }
                if (urls.isEmpty()) continue;

                PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(urls);
                if (entry.has("username")) builder.setUsername(entry.optString("username"));
                if (entry.has("credential")) builder.setPassword(entry.optString("credential"));
                result.add(builder.createIceServer());
            }
            if (!result.isEmpty()) return result;
        } catch (Exception e) {
            Log.e(TAG, "ICE server fetch failed, using STUN-only fallback: " + e.getMessage());
        }
        List<PeerConnection.IceServer> fallback = new ArrayList<>();
        fallback.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        return fallback;
    }
}
