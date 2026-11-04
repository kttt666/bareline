package com.littlesauce.bareline;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 密码库。
 *
 * 每条记录 = 名称 + 账号（可空）+ 密码。密码用 {@link Crypto} 加密后落盘，
 * SharedPreferences 里永远只有密文；账号和名称是明文（它们不敏感，且需要
 * 在列表里直接展示）。
 */
public class Vault {

    private static final String PREF = "bareline_vault";
    private static final String KEY_ITEMS = "items";

    public static class Item {
        public String id;
        public String label;
        public String user;
        /** 内存里的明文密码；写入磁盘前会重新加密。 */
        public String secret;
        public long ts;

        public Item(String id, String label, String user, String secret, long ts) {
            this.id = id;
            this.label = label;
            this.user = user;
            this.secret = secret;
            this.ts = ts;
        }
    }

    private final SharedPreferences sp;

    public Vault(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public List<Item> list() {
        List<Item> out = new ArrayList<>();
        String raw = sp.getString(KEY_ITEMS, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Item(
                        o.optString("id", ""),
                        o.optString("label", ""),
                        o.optString("user", ""),
                        Crypto.decrypt(o.optString("secret", "")),
                        o.optLong("ts", 0L)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public boolean isEmpty() {
        return list().isEmpty();
    }

    /** 新增或按 id 覆盖。 */
    public void put(Item item) {
        if (item == null) return;
        if (item.id == null || item.id.isEmpty()) {
            item.id = "v" + System.currentTimeMillis();
        }
        if (item.ts == 0L) item.ts = System.currentTimeMillis();

        List<Item> cur = list();
        boolean replaced = false;
        for (int i = 0; i < cur.size(); i++) {
            if (cur.get(i).id.equals(item.id)) {
                cur.set(i, item);
                replaced = true;
                break;
            }
        }
        if (!replaced) cur.add(0, item);
        save(cur);
    }

    public void remove(String id) {
        if (id == null) return;
        List<Item> cur = list();
        for (int i = cur.size() - 1; i >= 0; i--) {
            if (cur.get(i).id.equals(id)) cur.remove(i);
        }
        save(cur);
    }

    public Item find(String id) {
        if (id == null) return null;
        for (Item it : list()) {
            if (it.id.equals(id)) return it;
        }
        return null;
    }

    private void save(List<Item> items) {
        JSONArray arr = new JSONArray();
        for (Item it : items) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", it.id);
                o.put("label", it.label == null ? "" : it.label);
                o.put("user", it.user == null ? "" : it.user);
                o.put("secret", Crypto.encrypt(it.secret == null ? "" : it.secret));
                o.put("ts", it.ts);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        sp.edit().putString(KEY_ITEMS, arr.toString()).apply();
    }
}
