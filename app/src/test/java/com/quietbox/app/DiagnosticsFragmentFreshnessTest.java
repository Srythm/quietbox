package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * F094/F095：诊断页必须读 meta.updated_at 并做 TTL 新鲜度判定，过期/从未回传的
 * 旧会话 reg/skip/hit 一律按无数据展示，且抬头/导出/复制共用同一份判定。
 *
 * 纯 JVM 单测：readUpdatedAt/reportFresh/effectiveStatus 是 collect() 里可直测的
 * 判定面；collect() 本身需要真实 Context（getString/DateFormat），故另用源码扫描
 * 钉住三处 UI/文本出口确实复用了同一份判定结果。
 */
public class DiagnosticsFragmentFreshnessTest {

    @Test
    public void reportFreshnessUsesReportTtl() {
        long ttl = StatusProvider.REPORT_TTL_MS;
        long now = 1_700_000_000_000L;
        assertFalse("从未回传（updatedAt=0）必须判过期",
                DiagnosticsFragment.reportFresh(0L, now));
        assertTrue("TTL 边界内的回传必须判新鲜",
                DiagnosticsFragment.reportFresh(now - ttl, now));
        assertFalse("刚过 TTL 的回传必须判过期",
                DiagnosticsFragment.reportFresh(now - ttl - 1L, now));
        assertFalse("领先 now 超过时钟回拨容忍上限的时间戳必须判过期",
                DiagnosticsFragment.reportFresh(now + 6 * 60 * 1000L, now));
    }

    @Test
    public void readUpdatedAtReadsMetaKey() {
        FakeSharedPreferences sp = new FakeSharedPreferences();
        assertEquals("没有 meta.updated_at 时返回 0", 0L,
                DiagnosticsFragment.readUpdatedAt(sp));
        sp.edit().putLong(
                StatusProvider.VALUE_PREFIX + ReportProtocol.KEY_META_UPDATED_AT,
                1_700_000_000_123L).commit();
        assertEquals("必须读与 StatusProvider.reportUpdatedAt / 写入侧 Report.java 相同的键",
                1_700_000_000_123L, DiagnosticsFragment.readUpdatedAt(sp));
        // 同一份判定必须等价 StatusProvider.hasFreshReport 使用的 core 语义。
        long now = 1_700_000_000_123L + StatusProvider.REPORT_TTL_MS + 1L;
        assertFalse(DiagnosticsFragment.reportFresh(
                DiagnosticsFragment.readUpdatedAt(sp), now));
        assertEquals(ReportProtocol.isFresh(1_700_000_000_123L, now,
                        StatusProvider.REPORT_TTL_MS),
                DiagnosticsFragment.reportFresh(
                        DiagnosticsFragment.readUpdatedAt(sp), now));
    }

    @Test
    public void staleReportIsTreatedAsNoData() {
        DiagnosticsFragment.RawStatus reported =
                new DiagnosticsFragment.RawStatus("bgd.s.Pd", "", 42L);
        assertTrue("新鲜会话保留回传状态", reported
                == DiagnosticsFragment.effectiveStatus(true, reported));
        assertNull("过期会话的功能行必须按无数据展示",
                DiagnosticsFragment.effectiveStatus(false, reported));
    }

    @Test
    public void headerExportAndCopyShareOneFreshnessJudge() throws Exception {
        // collect() 需要真实 Context（getString/DateFormat），纯 JVM 无法直接驱动；
        // 改钉住源码接线：抬头/导出/复制三处必须复用同一份 fresh/headerTime/staleNote，
        // 且导出/复制不得再出现「本次读取时间」的 refreshTime 参数化抬头。
        Path source = TestPaths.resolveFromCwd(
                "src/main/java/com/quietbox/app/DiagnosticsFragment.java",
                "app/src/main/java/com/quietbox/app/DiagnosticsFragment.java",
                "project/app/src/main/java/com/quietbox/app/DiagnosticsFragment.java",
                "QuietBox/project/app/src/main/java/com/quietbox/app/DiagnosticsFragment.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertTrue("collect() 必须读 meta.updated_at 做 TTL 判定",
                text.contains("readUpdatedAt(sp)") && text.contains("reportFresh(updatedAt"));
        assertTrue("单一 fresh 判定必须门控所有功能行",
                text.contains("effectiveStatus(fresh, raw.get(f))"));
        assertTrue("抬头/导出/复制必须复用同一份 staleNote",
                text.contains("data.staleNote") && text.contains("headerLines.add(staleNote)")
                        && text.contains("copyHeader.add(staleNote)"));
        assertFalse("导出/复制不得再用 diag_header + refreshTime 把过期数据包装成刚刷新",
                text.contains("R.string.diag_header, refreshTime"));
    }
}
