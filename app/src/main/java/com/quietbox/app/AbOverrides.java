package com.quietbox.app;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AB 覆盖表（{@code key=value} 每行一条）的唯一格式实现：纯 JVM，无 android.* 依赖。
 *
 * 宿主读取端（AbTestHooks.AbRuntime.refresh）、App 写入端（AbConfigStore.setForced）
 * 与 App 侧配置加载（Config.loadTolerant）以前各写一份 split/indexOf/trim 循环，
 * 分隔符或空值规则一旦只改一处就会让强开项被静默丢弃。这里统一收口，并让
 * serialize 成为唯一逆操作，便于纯 JVM 单测固定格式（AbOverridesTest）。
 */
public final class AbOverrides {

    private AbOverrides() {
    }

    /**
     * 解析覆盖表：按 '\n' 拆行，第一个 '=' 左边为 key、右边为 value，两侧 trim，
     * 空 key 或空 value 的行整条丢弃；null/空串返回空表。重复 key 以最后一行为准。
     *
     * @return 始终返回新的、可变的 {@link LinkedHashMap}（保持写入顺序）
     */
    public static Map<String, String> parse(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        parseInto(raw, map);
        return map;
    }

    /** 与 {@link #parse(String)} 同规则，但把结果写进调用方已有的 Map。 */
    public static void parseInto(String raw, Map<String, String> out) {
        if (raw == null || out == null) {
            return;
        }
        for (String line : raw.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                out.put(key, value);
            }
        }
    }

    /** serialize(parse(x)) 保序且可再次被 parse 读回；行间用 '\n'，不加尾换行。 */
    public static String serialize(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        if (map == null) {
            return "";
        }
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
