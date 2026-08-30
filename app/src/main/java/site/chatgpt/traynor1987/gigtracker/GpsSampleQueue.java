package site.chatgpt.traynor1987.gigtracker;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class GpsSampleQueue {
    private static final String PREFS = "gig_tracker_native_gps_queue";
    private static final String KEY = "pending_samples";
    private static final int MAX_PENDING = 20_000;
    private final SharedPreferences preferences;

    GpsSampleQueue(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized void append(JSONObject envelope) {
        JSONArray existing = readArray();
        JSONArray next = new JSONArray();
        int first = Math.max(0, existing.length() - MAX_PENDING + 1);
        for (int i = first; i < existing.length(); i++) next.put(existing.opt(i));
        next.put(envelope);
        preferences.edit().putString(KEY, next.toString()).apply();
    }

    synchronized List<JSONObject> pending() {
        JSONArray array = readArray();
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) result.add(item);
        }
        return result;
    }

    synchronized void acknowledge(String sampleId) {
        JSONArray array = readArray();
        JSONArray next = new JSONArray();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null && !sampleId.equals(item.optString("id"))) next.put(item);
        }
        preferences.edit().putString(KEY, next.toString()).apply();
    }

    private JSONArray readArray() {
        try {
            return new JSONArray(preferences.getString(KEY, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }
}
