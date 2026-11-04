package com.littlesauce.bareline;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 最近打开链接的本地存储。
 *
 * 只存 url / 标题 / 时间戳，不存 cookie 也不存 token —— 登录态由 WebView 自己管。
 */
public class Store {

    private static final String PREF = "bareline_store";
    private static final String KEY_LINKS = "links";
    private static final int MAX = 40;

    public static class Link {
        public String url;
        public String title;
        public long ts;

        public Link(String url, String title, long ts) {
            this.url = url;
            this.title = title;
            this.ts = ts;
        }
    }

    private final SharedPreferences sp;

    public Store(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public List<Link> list() {
        List<Link> out = new ArrayList<>();
        String raw = sp.getString(KEY_LINKS, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Link(
                        o.optString("url", ""),
                        o.optString("title", ""),
                        o.optLong("ts", 0L)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 新链接排最前；同 URL 去重后置顶。 */
    public void add(String url, String title) {
        if (url == null || url.isEmpty()) return;
        List<Link> cur = list();
        for (int i = cur.size() - 1; i >= 0; i--) {
            if (cur.get(i).url.equals(url)) cur.remove(i);
        }
        cur.add(0, new Link(url, title == null ? "" : title, System.currentTimeMillis()));
        while (cur.size() > MAX) cur.remove(cur.size() - 1);
        save(cur);
    }

    public void remove(String url) {
        List<Link> cur = list();
        for (int i = cur.size() - 1; i >= 0; i--) {
            if (cur.get(i).url.equals(url)) cur.remove(i);
        }
        save(cur);
    }

    public void clear() {
        sp.edit().remove(KEY_LINKS).apply();
    }

    private void save(List<Link> links) {
        JSONArray arr = new JSONArray();
        for (Link l : links) {
            try {
                JSONObject o = new JSONObject();
                o.put("url", l.url);
                o.put("title", l.title);
                o.put("ts", l.ts);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        sp.edit().putString(KEY_LINKS, arr.toString()).apply();
    }
}
