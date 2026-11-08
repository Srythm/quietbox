package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AB 覆盖表 key=value 格式的唯一实现（F-056/F-060）的纯 JVM 单测。
 *
 * 宿主读取端、App 写入端、Config.loadTolerant 三处以前各写一份 parse 循环，
 * 分隔符/空值规则漂移会让强开项被静默丢弃。这里固定 parse/serialize 的
 * 契约与可逆性，保证三端共用同一格式。
 */
public class AbOverridesTest {

    @Test
    public void parseTrimsAndDropsMalformedLines() {
        Map<String, String> map = AbOverrides.parse(
                "  ab_key = 1 \n=novalue\nnoequals\n = \nother=2\nx=\n=3\n");
        assertEquals("等号两侧空白必须 trim", "1", map.get("ab_key"));
        assertEquals("其它合法行也要保留", "2", map.get("other"));
        assertEquals("只保留两条合法记录", 2, map.size());
        assertFalse("空 key 的行必须丢弃", map.containsKey(""));
        assertFalse("空 value 的行必须丢弃", map.containsKey("x"));
    }

    @Test
    public void parseSplitsOnFirstEqualsOnly() {
        Map<String, String> map = AbOverrides.parse("json={\"a\":1}\nurl=a=b=c");
        assertEquals("第一个 = 之后的全部内容都是 value",
                "{\"a\":1}", map.get("json"));
        assertEquals("value 内部的 = 不得被截断", "a=b=c", map.get("url"));
    }

    @Test
    public void parseHandlesNullAndEmptyInput() {
        assertTrue("null 输入必须返回空表", AbOverrides.parse(null).isEmpty());
        assertTrue("空串必须返回空表", AbOverrides.parse("").isEmpty());
    }

    @Test
    public void duplicateKeyKeepsLastValue() {
        Map<String, String> map = AbOverrides.parse("k=1\nk=2");
        assertEquals("重复 key 以最后一行为准", "2", map.get("k"));
        assertEquals(1, map.size());
    }

    @Test
    public void serializeRoundTripsThroughParse() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("first", "1");
        source.put("second", "true");
        String raw = AbOverrides.serialize(source);
        assertEquals("行间用 \\n 且不加尾换行", "first=1\nsecond=true", raw);
        assertEquals("parse(serialize(x)) 必须完整还原", source, AbOverrides.parse(raw));
    }

    @Test
    public void serializeHandlesNullAndEmptyMap() {
        assertEquals("", AbOverrides.serialize(null));
        assertEquals("", AbOverrides.serialize(new LinkedHashMap<>()));
    }

    @Test
    public void parseIntoWritesIntoCallerMap() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("existing", "keep");
        AbOverrides.parseInto("new=value", out);
        assertEquals("调用方已有的键不得被清掉", "keep", out.get("existing"));
        assertEquals("新键必须写入调用方的 Map", "value", out.get("new"));
    }

    @Test
    public void parseIntoToleratesNullArguments() {
        Map<String, String> out = new LinkedHashMap<>();
        AbOverrides.parseInto(null, out);
        assertTrue("null raw 不得改动 out", out.isEmpty());
        AbOverrides.parseInto("k=v", null);
    }
}
