package com.tvbox.parental;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seven-day weather for the TV lock screen.
 *
 * The box cannot reach api.open-meteo.com (TLS handshake times out), but the
 * domestic Chinese forecast endpoint below answers over plain HTTP and returns
 * a fully localised 7+ day forecast, so no API key or account is needed.
 * Results are cached in SharedPreferences: the lock screen still shows the
 * last good forecast when the box is offline.
 */
public class Weather {

    private static final String API = "http://t.weather.itboy.net/api/weather/city/";
    private static final String LOCATE = "https://whois.pconline.com.cn/ipJson.jsp?json=true";
    private static final long OK_INTERVAL_MS = 30 * 60 * 1000L;
    private static final long FAIL_INTERVAL_MS = 5 * 60 * 1000L;

    /** City name -> China Weather city code (the code used by the forecast API). */
    private static final String[][] CITIES = {
            {"北京", "101010100"}, {"上海", "101020100"}, {"天津", "101030100"},
            {"重庆", "101040100"}, {"哈尔滨", "101050101"}, {"长春", "101060101"},
            {"沈阳", "101070101"}, {"呼和浩特", "101080101"}, {"石家庄", "101090101"},
            {"太原", "101100101"}, {"西安", "101110101"}, {"济南", "101120101"},
            {"乌鲁木齐", "101130101"}, {"拉萨", "101140101"}, {"西宁", "101150101"},
            {"兰州", "101160101"}, {"银川", "101170101"}, {"郑州", "101180101"},
            {"南京", "101190101"}, {"武汉", "101200101"}, {"杭州", "101210101"},
            {"合肥", "101220101"}, {"福州", "101230101"}, {"南昌", "101240101"},
            {"长沙", "101250101"}, {"贵阳", "101260101"}, {"成都", "101270101"},
            {"广州", "101280101"}, {"昆明", "101290101"}, {"南宁", "101300101"},
            {"海口", "101310101"}, {"深圳", "101280601"}, {"青岛", "101120201"},
            {"大连", "101070201"}, {"厦门", "101230201"}, {"苏州", "101190401"},
            {"宁波", "101210401"}, {"无锡", "101190201"}, {"温州", "101210701"},
            {"东莞", "101281601"}, {"佛山", "101280800"}, {"珠海", "101280701"},
            {"中山", "101281701"}, {"惠州", "101280301"}, {"泉州", "101230501"},
            {"烟台", "101120501"}, {"洛阳", "101180901"}, {"唐山", "101090501"}
    };

    private static final JSONArray CITY_JSON = new JSONArray();
    static {
        for (String[] c : CITIES) {
            try {
                CITY_JSON.put(new JSONObject().put("name", c[0]).put("code", c[1]));
            } catch (Exception ignored) { }
        }
    }

    private final SharedPreferences prefs;
    private final Runnable onChange;
    private volatile JSONObject cache = new JSONObject();
    private volatile String error = "";
    private volatile boolean refreshing = false;
    private volatile boolean stale = false;
    private volatile long lastAttempt = 0;
    private volatile long lastOk = 0;

    public Weather(SharedPreferences p, Runnable cb) {
        prefs = p;
        onChange = cb;
        String raw = prefs.getString("weather_cache", "");
        if (!raw.isEmpty()) {
            try { cache = new JSONObject(raw); } catch (Exception ignored) { }
        }
        lastOk = prefs.getLong("weather_last_ms", 0L);
        stale = lastOk > 0L;   // cached data is stale until the first refresh
    }

    /** Throttled background refresh. force=true ignores the interval. */
    public synchronized void maybeRefresh(boolean force) {
        long now = System.currentTimeMillis();
        long gap = lastOk > 0L ? OK_INTERVAL_MS : FAIL_INTERVAL_MS;
        if (refreshing) {
            return;
        }
        if (!force && now - lastAttempt < gap) {
            return;
        }
        lastAttempt = now;
        refreshing = true;
        new Thread(new Runnable() {
            @Override public void run() { doFetch(); }
        }, "tvctl-weather").start();
    }

    private void doFetch() {
        try {
            String code = prefs.getString("weather_city_code", "");
            if (!code.matches("\\d{9}")) {
                String[] found = autoCity();
                code = found[0];
                prefs.edit()
                        .putString("weather_city_code", code)
                        .putString("weather_city_name", found[1])
                        .apply();
            }
            JSONObject d = parse(httpGet(API + code, "UTF-8"), code);
            cache = d;
            lastOk = System.currentTimeMillis();
            stale = false;
            error = "";
            prefs.edit()
                    .putString("weather_cache", d.toString())
                    .putLong("weather_last_ms", lastOk)
                    .apply();
        } catch (Exception e) {
            error = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            stale = cache.optJSONArray("days") != null;
        } finally {
            refreshing = false;
            if (onChange != null) {
                try { onChange.run(); } catch (Exception ignored) { }
            }
        }
    }

    /** IP-based city guess, with Beijing as the last resort. */
    private String[] autoCity() {
        try {
            String s = httpGet(LOCATE, "GBK");
            String code = matchCity(field(s, "city"));
            if (code == null) {
                code = matchCity(field(s, "pro"));
            }
            if (code != null) {
                return new String[]{code, nameOf(code)};
            }
        } catch (Exception ignored) { }
        return new String[]{"101010100", "北京"};
    }

    private static String field(String s, String key) {
        int i = s.indexOf("\"" + key + "\"");
        if (i < 0) {
            return "";
        }
        int c = s.indexOf(':', i);
        if (c < 0) {
            return "";
        }
        int q1 = s.indexOf('"', c + 1);
        int q2 = q1 < 0 ? -1 : s.indexOf('"', q1 + 1);
        return q1 < 0 || q2 < 0 ? "" : s.substring(q1 + 1, q2).trim();
    }

    private static String matchCity(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (String[] c : CITIES) {
            if (name.contains(c[0]) || c[0].contains(name)) {
                return c[1];
            }
        }
        return null;
    }

    private static String nameOf(String code) {
        for (String[] c : CITIES) {
            if (c[1].equals(code)) {
                return c[0];
            }
        }
        return "";
    }

    private static JSONObject parse(String raw, String code) throws Exception {
        JSONObject root = new JSONObject(raw);
        if (root.optInt("status", 0) != 200) {
            throw new IllegalStateException("天气服务返回异常");
        }
        JSONObject info = root.optJSONObject("cityInfo");
        JSONObject data = root.getJSONObject("data");
        JSONArray fc = data.getJSONArray("forecast");
        if (fc.length() == 0) {
            throw new IllegalStateException("无天气数据");
        }

        JSONArray days = new JSONArray();
        for (int i = 0; i < Math.min(7, fc.length()); i++) {
            JSONObject f = fc.getJSONObject(i);
            JSONObject d = new JSONObject();
            String ymd = f.optString("ymd", "");
            String week = f.optString("week", "").replace("星期", "周");
            String type = f.optString("type", "");
            d.put("ymd", ymd);
            d.put("date", ymd.length() >= 10
                    ? ymd.substring(5).replace('-', '/') : f.optString("date", ""));
            d.put("week", week);
            d.put("label", i == 0 ? "今天" : (i == 1 ? "明天" : (i == 2 ? "后天" : week)));
            d.put("type", type);
            d.put("high", temp(f.optString("high", "")));
            d.put("low", temp(f.optString("low", "")));
            d.put("aqi", f.optInt("aqi", 0));
            d.put("wind", f.optString("fx", "") + " " + f.optString("fl", ""));
            d.put("notice", f.optString("notice", ""));
            d.put("color", colorOf(type));
            days.put(d);
        }

        JSONObject cur = new JSONObject();
        cur.put("temp", data.optString("wendu", ""));
        cur.put("humidity", data.optString("shidu", ""));
        cur.put("quality", data.optString("quality", ""));
        cur.put("pm25", data.optDouble("pm25", 0));
        cur.put("type", fc.getJSONObject(0).optString("type", ""));

        JSONObject out = new JSONObject();
        out.put("city", info == null ? "" : info.optString("city", ""));
        out.put("city_code", code);
        out.put("updated", root.optString("time", ""));
        out.put("current", cur);
        out.put("days", days);
        return out;
    }

    private static String temp(String s) {
        Matcher m = Pattern.compile("-?\\d+").matcher(s);
        return m.find() ? m.group() : "";
    }

    private static String colorOf(String t) {
        if (t.contains("晴")) return "#FFB74D";
        if (t.contains("多云")) return "#64B5F6";
        if (t.contains("雷")) return "#9575CD";
        if (t.contains("雨")) return "#4FC3F7";
        if (t.contains("雪")) return "#B3E5FC";
        if (t.contains("阴")) return "#B0BEC5";
        if (t.contains("雾") || t.contains("霾") || t.contains("沙") || t.contains("尘")) {
            return "#A1887F";
        }
        return "#CFD8DC";
    }

    private static String httpGet(String url, String charset) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(9000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            c.setRequestProperty("Connection", "close");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < 200000) {
                bo.write(buf, 0, n);
            }
            in.close();
            return new String(bo.toByteArray(), charset);
        } finally {
            c.disconnect();
        }
    }

    private JSONObject json(boolean withCities) {
        JSONObject o = new JSONObject();
        try {
            JSONArray days = cache.optJSONArray("days");
            JSONObject cur = cache.optJSONObject("current");
            o.put("ok", days != null && days.length() > 0);
            o.put("enabled", prefs.getBoolean("weather_enabled", true));
            o.put("city", cache.optString("city", ""));
            o.put("city_code", prefs.getString("weather_city_code", ""));
            o.put("city_name", prefs.getString("weather_city_name", ""));
            o.put("updated", cache.optString("updated", ""));
            o.put("last_ms", lastOk);
            o.put("current", cur == null ? new JSONObject() : cur);
            o.put("days", days == null ? new JSONArray() : days);
            o.put("stale", stale);
            o.put("refreshing", refreshing);
            o.put("error", error);
            if (withCities) {
                o.put("cities", CITY_JSON);
            }
        } catch (Exception ignored) { }
        return o;
    }

    /** Full payload for the phone UI (includes the city picker). */
    public JSONObject json() {
        return json(true);
    }

    /** Lightweight payload for the lock screen. */
    public JSONObject view() {
        return json(false);
    }
}
