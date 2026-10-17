package com.limsj.docviewer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Recently opened documents, kept in SharedPreferences. */
final class Recent {
    static final int MAX = 40;

    static final class Item {
        String uri, name;
        long time;
    }

    private Recent() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("recent", Context.MODE_PRIVATE);
    }

    static List<Item> load(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("items", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.uri = o.getString("u");
                it.name = o.optString("n", "문서");
                it.time = o.optLong("t", 0);
                out.add(it);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void save(Context c, List<Item> items) {
        try {
            JSONArray a = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("u", it.uri);
                o.put("n", it.name);
                o.put("t", it.time);
                a.put(o);
            }
            prefs(c).edit().putString("items", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static void add(Context c, String uri, String name) {
        List<Item> items = load(c);
        for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).uri.equals(uri)) items.remove(i);
        Item it = new Item();
        it.uri = uri;
        it.name = name;
        it.time = System.currentTimeMillis();
        items.add(0, it);
        while (items.size() > MAX) items.remove(items.size() - 1);
        save(c, items);
    }

    static void remove(Context c, String uri) {
        List<Item> items = load(c);
        for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).uri.equals(uri)) items.remove(i);
        save(c, items);
    }

    static void clear(Context c) {
        save(c, new ArrayList<>());
    }
}
