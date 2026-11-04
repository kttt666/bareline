package com.littlesauce.pocket;

import android.net.Uri;
import android.text.TextUtils;

/** 把用户随手敲的东西补成可用 URL。 */
public final class UrlFix {

    private UrlFix() {
    }

    /** 判断扫码结果 / 输入内容是不是一个能打开的地址。 */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // 已经是完整 URL
        if (s.startsWith("http://") || s.startsWith("https://")) return s;

        // 其它 scheme（intent://、market:// 等）原样返回，交给上层处理
        int colon = s.indexOf(':');
        int slash = s.indexOf('/');
        if (colon > 0 && (slash < 0 || colon < slash) && !s.startsWith("localhost")) {
            String scheme = s.substring(0, colon);
            if (scheme.matches("[a-zA-Z][a-zA-Z0-9+.\\-]*")) {
                // 形如 example.com:8080 的会被误判，检查冒号后是否为数字端口
                String after = s.substring(colon + 1);
                boolean portLike = after.matches("\\d+.*");
                if (!portLike) return s;
            }
        }

        // 纯域名 / 带端口 / 带路径
        String hostPart = s;
        int cut = s.indexOf('/');
        if (cut > 0) hostPart = s.substring(0, cut);

        boolean looksLikeHost = hostPart.matches("[^\\s/]+\\.[^\\s/]+(:\\d+)?")
                || hostPart.equals("localhost")
                || hostPart.matches("localhost:\\d+")
                || hostPart.matches("\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?");

        if (looksLikeHost) {
            // 局域网 IP / localhost 用 http，其余用 https
            boolean lan = hostPart.startsWith("localhost")
                    || hostPart.matches("\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?")
                    || hostPart.matches("10\\..*")
                    || hostPart.matches("192\\.168\\..*")
                    || hostPart.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
            return (lan ? "http://" : "https://") + s;
        }

        return null;
    }

    /** 列表里展示用的简短标题。 */
    public static String shortLabel(String url) {
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            if (!TextUtils.isEmpty(host)) {
                String path = u.getPath();
                if (path != null && path.length() > 1 && !path.equals("/")) {
                    return host + (path.length() > 22 ? path.substring(0, 22) + "…" : path);
                }
                return host;
            }
        } catch (Exception ignored) {
        }
        return url;
    }
}
