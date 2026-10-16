package lk.kdu.wallet.common;

import java.util.HashMap;
import java.util.Map;

public final class Kv {
    private Kv() {}

    public static Map<String, String> parse(String s) {
        Map<String, String> m = new HashMap<>();
        for (String part : s.split("&")) {
            int i = part.indexOf('=');
            if (i > 0) m.put(part.substring(0, i), part.substring(i + 1));
        }
        return m;
    }

    public static String of(String... pairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(pairs[i]).append('=').append(pairs[i + 1]);
        }
        return sb.toString();
    }

    public static String clean(String v) {
        return v == null ? "" : v.replaceAll("[^A-Za-z0-9 :_.-]", "_");
    }
}