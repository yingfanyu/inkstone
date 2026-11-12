package com.inkstone.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.lang.reflect.Field;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 端点（域名 + 端口）状态机。
 *
 * 规则：
 *  - 域名默认 note.1.929295.xyz，可被手工覆盖，覆盖后一直生效直到恢复默认。
 *  - 端口从 TXT 记录 note2.929295.xyz 解析，只在「解析当天」有效，跨天重新解析。
 *  - 手工覆盖的端口同样只在当天有效。
 *  - 取不到端口时，先回退到上次成功的端口，再回退到老的 Cloudflare 域名。
 */
public final class Endpoint {

    public static final String DEFAULT_HOST = "note.1.929295.xyz";
    public static final String TXT_NAME = "note2.929295.xyz";
    public static final String LEGACY_URL = "https://note.929295.xyz/";

    private static final String PREFS = "inkstone_endpoint";
    private static final String K_HOST_OVERRIDE = "host_override";
    private static final String K_PORT_OVERRIDE = "port_override";
    private static final String K_PORT_OVERRIDE_DATE = "port_override_date";
    private static final String K_TXT_PORT = "txt_port";
    private static final String K_TXT_HOST = "txt_host";
    private static final String K_TXT_DATE = "txt_date";
    private static final String K_TXT_RAW = "txt_raw";
    private static final String K_TXT_AT = "txt_at";
    private static final String K_LAST_GOOD_PORT = "last_good_port";

    private static Context app;
    private static SharedPreferences prefs;

    private static String host = DEFAULT_HOST;
    private static int port = 0;
    private static String url = LEGACY_URL;

    private Endpoint() {
    }

    // ---------------------------------------------------------------- 生命周期

    public static synchronized void init(Context context) {
        if (context == null) {
            return;
        }
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        rebuild();
    }

    /** 启动时调用：当天还没有可用端口就做一次短超时解析，保证首屏地址正确。 */
    public static synchronized void warmUp() {
        if (prefs == null) {
            return;
        }
        if (currentPort() <= 0) {
            resolveNow(2500);
            rebuild();
        }
        apply();
    }

    /** 后台刷新，不阻塞启动。 */
    public static void refreshAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (resolveNow(2500)) {
                        synchronized (Endpoint.class) {
                            rebuild();
                            apply();
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "inkstone-endpoint").start();
    }

    // ------------------------------------------------------------------ 解析

    /** 查 TXT 并落库；返回是否拿到了可用端口。 */
    public static synchronized boolean resolveNow(int budgetMs) {
        if (prefs == null) {
            return false;
        }
        try {
            List<String> values = DnsTxt.lookup(TXT_NAME, budgetMs);
            if (values.isEmpty()) {
                return false;
            }
            String raw = DnsTxt.describe(values);
            String pickedHost = null;
            int pickedPort = 0;
            for (String value : values) {
                int[] parsed = parsePort(value);
                if (parsed[0] > 0) {
                    pickedPort = parsed[0];
                    if (parsed[1] == 1) {
                        pickedHost = parseHost(value);
                    }
                    break;
                }
            }
            if (pickedPort <= 0) {
                prefs.edit().putString(K_TXT_RAW, raw).putLong(K_TXT_AT, System.currentTimeMillis()).apply();
                return false;
            }
            SharedPreferences.Editor editor = prefs.edit()
                    .putString(K_TXT_RAW, raw)
                    .putString(K_TXT_PORT, String.valueOf(pickedPort))
                    .putString(K_TXT_DATE, today())
                    .putLong(K_TXT_AT, System.currentTimeMillis())
                    .putString(K_LAST_GOOD_PORT, String.valueOf(pickedPort));
            if (pickedHost != null && pickedHost.length() > 0) {
                editor.putString(K_TXT_HOST, pickedHost);
            }
            editor.apply();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 返回 [端口, 是否带主机] ；端口 0 表示没解析到。 */
    private static int[] parsePort(String raw) {
        String value = clean(raw);
        if (value.length() == 0) {
            return new int[]{0, 0};
        }
        // 完整 URL
        if (value.startsWith("http://") || value.startsWith("https://")) {
            try {
                URL u = new URL(value);
                int p = u.getPort() > 0 ? u.getPort() : ("https".equals(u.getProtocol()) ? 443 : 80);
                return new int[]{validPort(p) ? p : 0, 1};
            } catch (Throwable ignored) {
                return new int[]{0, 0};
            }
        }
        // key=value 串
        if (value.indexOf('=') > 0) {
            int p = 0;
            String[] parts = value.split("[;,\\s]+");
            for (String part : parts) {
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = part.substring(0, eq).trim();
                String v = part.substring(eq + 1).trim();
                if ("port".equals(k)) {
                    p = parseInt(v);
                }
            }
            return new int[]{validPort(p) ? p : 0, value.contains("host=") ? 1 : 0};
        }
        // [v6]:port
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close > 0 && value.length() > close + 2 && value.charAt(close + 1) == ':') {
                int p = parseInt(value.substring(close + 2));
                return new int[]{validPort(p) ? p : 0, 1};
            }
        }
        // 纯端口
        if (value.matches("^[0-9]{1,5}$")) {
            int p = parseInt(value);
            return new int[]{validPort(p) ? p : 0, 0};
        }
        // host:port
        int colon = value.lastIndexOf(':');
        if (colon > 0 && colon < value.length() - 1) {
            int p = parseInt(value.substring(colon + 1));
            return new int[]{validPort(p) ? p : 0, 1};
        }
        return new int[]{0, 0};
    }

    private static String parseHost(String raw) {
        String value = clean(raw);
        if (value.startsWith("http://") || value.startsWith("https://")) {
            try {
                return new URL(value).getHost();
            } catch (Throwable ignored) {
                return null;
            }
        }
        if (value.indexOf('=') > 0) {
            for (String part : value.split("[;,\\s]+")) {
                int eq = part.indexOf('=');
                if (eq > 0 && "host".equals(part.substring(0, eq).trim())) {
                    return part.substring(eq + 1).trim();
                }
            }
            return null;
        }
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            return close > 0 ? value.substring(1, close) : null;
        }
        if (value.matches("^[0-9]{1,5}$")) {
            return null;
        }
        int colon = value.lastIndexOf(':');
        if (colon > 0) {
            return value.substring(0, colon);
        }
        return null;
    }

    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String v = raw.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v.trim();
    }

    private static int parseInt(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static boolean validPort(int p) {
        return p > 0 && p < 65536;
    }

    // ------------------------------------------------------------------ 组装

    private static void rebuild() {
        if (prefs == null) {
            return;
        }
        String override = prefs.getString(K_HOST_OVERRIDE, null);
        String txtHost = prefs.getString(K_TXT_HOST, null);
        if (override != null && override.trim().length() > 0) {
            host = override.trim();
        } else if (txtHost != null && txtHost.trim().length() > 0) {
            host = txtHost.trim();
        } else {
            host = DEFAULT_HOST;
        }

        int p = currentPort();
        if (p <= 0) {
            p = parseInt(prefs.getString(K_LAST_GOOD_PORT, "0"));
        }
        port = validPort(p) ? p : 0;
        url = port > 0 ? buildUrl(host, port) : LEGACY_URL;
    }

    /** 当天有效的端口：手工覆盖优先，其次当天 TXT 结果。 */
    public static synchronized int currentPort() {
        if (prefs == null) {
            return port;
        }
        String today = today();
        if (today.equals(prefs.getString(K_PORT_OVERRIDE_DATE, ""))) {
            int p = parseInt(prefs.getString(K_PORT_OVERRIDE, "0"));
            if (validPort(p)) {
                return p;
            }
        }
        if (today.equals(prefs.getString(K_TXT_DATE, ""))) {
            int p = parseInt(prefs.getString(K_TXT_PORT, "0"));
            if (validPort(p)) {
                return p;
            }
        }
        return 0;
    }

    private static String buildUrl(String h, int p) {
        String hostPart = h.indexOf(':') >= 0 && !h.startsWith("[") ? "[" + h + "]" : h;
        return p == 443 ? "https://" + hostPart + "/" : "https://" + hostPart + ":" + p + "/";
    }

    /** 把结果写回 MainActivity 的静态字段，WebView 与可信域判断都会用上。 */
    public static synchronized void apply() {
        try {
            Class<?> activity = Class.forName("com.inkstone.app.MainActivity");
            Field startUrl = activity.getField("START_URL");
            startUrl.set(null, url);
            Set<String> trusted = new HashSet<String>();
            trusted.add(host);
            trusted.add(DEFAULT_HOST);
            trusted.add("note.929295.xyz");
            trusted.add("localhost");
            trusted.add("127.0.0.1");
            Field trustedField = activity.getField("TRUSTED_HOSTS");
            trustedField.set(null, trusted);
        } catch (Throwable ignored) {
            // 拿不到就维持原样：应用仍可走老域名
        }
    }

    public static synchronized boolean isTrusted(String candidate) {
        if (candidate == null) {
            return false;
        }
        String h = candidate.toLowerCase(Locale.ROOT);
        return h.equals(host) || h.equals(DEFAULT_HOST) || h.equals("note.929295.xyz")
                || h.equals("localhost") || h.equals("127.0.0.1");
    }

    // ------------------------------------------------------------------ 读写

    public static synchronized String url() {
        return url;
    }

    public static synchronized String host() {
        return host;
    }

    public static synchronized int port() {
        return port;
    }

    public static synchronized String hostOverride() {
        return prefs == null ? null : prefs.getString(K_HOST_OVERRIDE, null);
    }

    /** TXT 里如果带了主机名，会在没有手工覆盖时生效。 */
    public static synchronized String txtHost() {
        return prefs == null ? null : prefs.getString(K_TXT_HOST, null);
    }

    public static synchronized void setHostOverride(String value) {
        if (prefs == null) {
            return;
        }
        String v = value == null ? "" : value.trim();
        if (v.length() == 0) {
            prefs.edit().remove(K_HOST_OVERRIDE).apply();
        } else {
            prefs.edit().putString(K_HOST_OVERRIDE, v).apply();
        }
        rebuild();
        apply();
    }

    public static synchronized void clearHostOverride() {
        if (prefs == null) {
            return;
        }
        prefs.edit().remove(K_HOST_OVERRIDE).apply();
        rebuild();
        apply();
    }

    public static synchronized int portOverride() {
        if (prefs == null) {
            return 0;
        }
        if (!today().equals(prefs.getString(K_PORT_OVERRIDE_DATE, ""))) {
            return 0;
        }
        return parseInt(prefs.getString(K_PORT_OVERRIDE, "0"));
    }

    /** 手工端口，当天有效（次日自动失效回到 TXT 结果）。 */
    public static synchronized void setPortOverride(int value) {
        if (prefs == null) {
            return;
        }
        prefs.edit()
                .putString(K_PORT_OVERRIDE, String.valueOf(value))
                .putString(K_PORT_OVERRIDE_DATE, today())
                .putString(K_LAST_GOOD_PORT, String.valueOf(value))
                .apply();
        rebuild();
        apply();
    }

    public static synchronized void clearPortOverride() {
        if (prefs == null) {
            return;
        }
        prefs.edit().remove(K_PORT_OVERRIDE).remove(K_PORT_OVERRIDE_DATE).apply();
        rebuild();
        apply();
    }

    public static synchronized String txtPort() {
        return prefs == null ? "" : prefs.getString(K_TXT_PORT, "");
    }

    public static synchronized String txtDate() {
        return prefs == null ? "" : prefs.getString(K_TXT_DATE, "");
    }

    public static synchronized String txtRaw() {
        return prefs == null ? "" : prefs.getString(K_TXT_RAW, "");
    }

    public static synchronized long txtAt() {
        return prefs == null ? 0L : prefs.getLong(K_TXT_AT, 0L);
    }

    public static synchronized boolean txtFresh() {
        return prefs != null && today().equals(prefs.getString(K_TXT_DATE, ""));
    }

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }

    public static String formatTime(long millis) {
        if (millis <= 0) {
            return "—";
        }
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date(millis));
    }
}
