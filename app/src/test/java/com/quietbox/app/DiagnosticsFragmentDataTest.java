package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

/**
 * 诊断页后台快照读取的纯 JVM 单测（F-044）：
 *
 * 主线程不再逐功能读 SharedPreferences；后台一次性读出的 reg/skip/hit 必须与
 * StatusProvider 的落盘键完全一致，崩溃按 crash0/1/2 原下标保留。这里用
 * FakeSharedPreferences 直接验证数据层，确保「挪到后台」没有改变诊断语义。
 */
public class DiagnosticsFragmentDataTest {

    private static FakeSharedPreferences seeded() {
        FakeSharedPreferences sp = new FakeSharedPreferences();
        Feature f = Feature.FEED_AD_FILTER;
        sp.edit()
                .putString(StatusProvider.VALUE_PREFIX + StatusProvider.PREFIX_REG + f.key,
                        "bgd.s.Pd")
                .putString(StatusProvider.VALUE_PREFIX + StatusProvider.PREFIX_SKIP + f.key,
                        "方法未找到 x")
                .putLong(StatusProvider.VALUE_PREFIX + StatusProvider.PREFIX_HIT + f.key, 7L)
                .putString(StatusProvider.VALUE_PREFIX + ReportProtocol.KEY_CRASH_0,
                        "1000|main|java.lang.RuntimeException|at a.B.c(B.java:1)")
                .putString(StatusProvider.VALUE_PREFIX + ReportProtocol.KEY_CRASH_2,
                        "2000|main|java.lang.IllegalStateException|at d.E.f(E.java:2)")
                .commit();
        return sp;
    }

    @Test
    public void readsRegSkipAndHitForEveryFeature() {
        Map<Feature, DiagnosticsFragment.RawStatus> statuses =
                DiagnosticsFragment.readRawStatus(seeded());

        Feature f = Feature.FEED_AD_FILTER;
        DiagnosticsFragment.RawStatus status = statuses.get(f);
        assertEquals("bgd.s.Pd", status.reg);
        assertEquals("方法未找到 x", status.skip);
        assertEquals(7L, status.hit);
        assertTrue(status.hasReport());

        DiagnosticsFragment.RawStatus empty = statuses.get(Feature.SPLASH_AD_SKIP);
        assertNull(empty.reg);
        assertNull(empty.skip);
        assertEquals(0L, empty.hit);
        assertFalse("无回传的功能必须判为无数据", empty.hasReport());
    }

    @Test
    public void blockedFeaturesAreNotRead() {
        Map<Feature, DiagnosticsFragment.RawStatus> statuses =
                DiagnosticsFragment.readRawStatus(seeded());

        assertFalse("blocked 功能不读存储（诊断页固定走 blockedReason）",
                statuses.containsKey(Feature.TRACK_SUPPRESS));
        assertFalse(statuses.containsKey(Feature.VIDEO_DEFAULT_MUTE));
    }

    @Test
    public void nullPreferencesYieldEmptySnapshot() {
        Map<Feature, DiagnosticsFragment.RawStatus> statuses =
                DiagnosticsFragment.readRawStatus(null);
        assertTrue("存储不可用时必须返回空表而不是抛异常", statuses.isEmpty());
        String[] crashes = DiagnosticsFragment.readCrashes(null);
        assertEquals("崩溃槽位始终按 crash0/1/2 三个下标返回", 3, crashes.length);
        assertNull(crashes[0]);
        assertEquals("", DiagnosticsFragment.joinCrashText(null));
    }

    @Test
    public void crashesKeepOriginalIndexes() {
        String[] crashes = DiagnosticsFragment.readCrashes(seeded());

        assertTrue(crashes[0].contains("RuntimeException"));
        assertNull("crash1 缺失必须保留空位，不能让 crash2 冒充 crash1", crashes[1]);
        assertTrue(crashes[2].contains("IllegalStateException"));

        String text = DiagnosticsFragment.joinCrashText(crashes);
        assertTrue(text.startsWith("crash0 1000|main|"));
        assertFalse("缺失的 crash1 不得输出", text.contains("crash1 "));
        assertTrue("crash2 必须保留原下标", text.contains("crash2 2000|main|"));
    }

    @Test
    public void nonStringValuesDoNotBreakWholeSnapshot() {
        FakeSharedPreferences sp = seeded();
        // crash1 被写成非 String：读取该键只能返回空位，不能影响其它功能/崩溃。
        sp.edit().putInt(StatusProvider.VALUE_PREFIX + ReportProtocol.KEY_CRASH_1, 9).commit();

        String[] crashes = DiagnosticsFragment.readCrashes(sp);
        assertTrue(crashes[0].contains("RuntimeException"));
        assertNull("非 String 的崩溃键按缺失处理", crashes[1]);
        assertTrue(crashes[2].contains("IllegalStateException"));
        assertTrue(DiagnosticsFragment.readRawStatus(sp)
                .containsKey(Feature.FEED_AD_FILTER));
    }

    // ------------------------------------------------------------ 复制/分享共用拼装（F-058）

    @Test
    public void joinLinesKeepsOrderWithoutTrailingNewline() {
        assertEquals("抬头文件按给定顺序用 \\n 连接且不加尾换行",
                "静盒 0.9.8\n数据来自目标进程回传 · 刷新时间 12:00",
                DiagnosticsFragment.joinLines(
                        Arrays.asList("静盒 0.9.8", "数据来自目标进程回传 · 刷新时间 12:00")));
        assertEquals("", DiagnosticsFragment.joinLines(Collections.emptyList()));
    }

    @Test
    public void copyTextSharesFeatureLinesWithExport() {
        String header = DiagnosticsFragment.joinLines(
                Arrays.asList("静盒 0.9.8", "数据来自目标进程回传 · 刷新时间 12:00"));
        java.util.List<String> features = Arrays.asList(
                "AD-01 开屏广告跳过: registered", "AD-02 信息流广告过滤: —");

        String copy = DiagnosticsFragment.joinReportText(header, features, "", "最近崩溃:");

        assertEquals("复制文本必须逐行含全部功能状态，且与分享共用同一份 featureLines",
                header + "\nAD-01 开屏广告跳过: registered\nAD-02 信息流广告过滤: —\n", copy);
        assertFalse("无崩溃时不得出现崩溃段", copy.contains("最近崩溃"));
    }

    @Test
    public void copyTextAppendsCrashBlockWithSharedHeader() {
        String header = DiagnosticsFragment.joinLines(
                Arrays.asList("静盒 0.9.8", "数据来自目标进程回传 · 刷新时间 12:00"));
        String crash = "crash0 1000|main|java.lang.RuntimeException\n";

        String copy = DiagnosticsFragment.joinReportText(header,
                Collections.singletonList("AD-01 开屏广告跳过: registered"),
                crash, "最近崩溃:");

        assertTrue("复制文本必须以共用抬头开头", copy.startsWith(header + "\nAD-01"));
        assertTrue("有崩溃时必须追加崩溃段名与 crash 行",
                copy.contains("最近崩溃:\ncrash0 1000|main|java.lang.RuntimeException\n"));
    }
}
