package com.pixelwhale.deepseek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class ChatStore {
    static final class Message {
        final String role;
        final String content;
        Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private final SharedPreferences prefs;

    ChatStore(Context context) {
        prefs = context.getSharedPreferences("chat", Context.MODE_PRIVATE);
    }

    List<Message> load() {
        List<Message> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("messages", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String role = item.optString("role");
                String content = item.optString("content");
                if (("user".equals(role) || "assistant".equals(role)) && !content.isEmpty()) {
                    result.add(new Message(role, content));
                }
            }
        } catch (Exception ignored) {
            // A damaged local history should not prevent the app from opening.
        }
        return result;
    }

    void save(List<Message> messages) {
        JSONArray array = new JSONArray();
        int start = Math.max(0, messages.size() - 100);
        for (int i = start; i < messages.size(); i++) {
            Message message = messages.get(i);
            JSONObject item = new JSONObject();
            try {
                item.put("role", message.role);
                item.put("content", message.content);
                array.put(item);
            } catch (Exception ignored) { }
        }
        prefs.edit().putString("messages", array.toString()).apply();
    }

    void clear() {
        prefs.edit().clear().apply();
    }
}
