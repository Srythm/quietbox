package com.quietbox.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * AB 面板总开关写路径的纯决策逻辑（不构造 Fragment，不依赖 Android 运行时）。
 *
 * 本地副本是宿主运行中 provider 的读源；只有已确认落盘的总开关值才能写进去。
 * 旧实现把「用户刚请求、后台 commit 还没返回」的值先写进去，commit 失败又恰好
 * 用户已离开页面（onWriteResult 因 getView()==null 直接返回）时，活通道会一直
 * 按未落盘的值生效。
 */
public class AbTestFragmentTest {

    @Test
    public void confirmedValueIsMirroredToLiveChannel() {
        assertTrue(AbTestFragment.mayMirrorPanelEnabled(false, true));
    }

    @Test
    public void requestedValueIsNotMirroredBeforeCommit() {
        assertFalse("masterOverride 非空 = 还没落盘，不能写给活通道",
                AbTestFragment.mayMirrorPanelEnabled(true, true));
    }

    @Test
    public void noRemoteNeverMirrors() {
        assertFalse(AbTestFragment.mayMirrorPanelEnabled(false, false));
        assertFalse(AbTestFragment.mayMirrorPanelEnabled(true, false));
    }

    @Test
    public void unchangedReportAndTimestampDoNotRedraw() {
        java.util.Map<String, String> report = new java.util.HashMap<>();
        report.put("andr_a", "1");
        assertFalse("探测值与回传时间都没变时不应重建列表",
                AbTestFragment.otherReportChanged(report, 1000L, report, 1000L));
    }

    @Test
    public void newProbeValueTriggersRedraw() {
        java.util.Map<String, String> rendered = new java.util.HashMap<>();
        rendered.put("andr_a", "1");
        java.util.Map<String, String> latest = new java.util.HashMap<>(rendered);
        latest.put("andr_b", "2");
        assertTrue("宿主回传了新 key 时必须刷新「其他」区",
                AbTestFragment.otherReportChanged(rendered, 1000L, latest, 1000L));
        latest = new java.util.HashMap<>(rendered);
        latest.put("andr_a", "9");
        assertTrue("已收录 key 的值变化也必须刷新",
                AbTestFragment.otherReportChanged(rendered, 1000L, latest, 1000L));
    }

    @Test
    public void newReportTimestampTriggersRedraw() {
        java.util.Map<String, String> report = new java.util.HashMap<>();
        report.put("andr_a", "1");
        assertTrue("探测值相同但回传时间变化时，时间戳也必须更新",
                AbTestFragment.otherReportChanged(report, 1000L, report, 4000L));
    }

    // ------------------------------------------------------------ CSV 公式注入

    @Test
    public void csvNeutralizesFormulaStartingValues() {
        // 探测值来自宿主 AB 下发，AbReportProvider.validValue 只拒控制字符，
        // 放行 = + - @；导出后 Excel/WPS 会把它们当公式执行。
        org.junit.Assert.assertEquals("'=cmd|'/c calc'!A0",
                AbTestFragment.esc("=cmd|'/c calc'!A0"));
        org.junit.Assert.assertEquals("'+1+1", AbTestFragment.esc("+1+1"));
        org.junit.Assert.assertEquals("'-1+1", AbTestFragment.esc("-1+1"));
        org.junit.Assert.assertEquals("'@SUM(A1:A2)", AbTestFragment.esc("@SUM(A1:A2)"));
        org.junit.Assert.assertEquals("'\t=1", AbTestFragment.esc("\t=1"));
    }

    @Test
    public void csvKeepsOrdinaryValuesAndQuotingBehavior() {
        org.junit.Assert.assertEquals("plain", AbTestFragment.esc("plain"));
        org.junit.Assert.assertEquals("\"a,b\"", AbTestFragment.esc("a,b"));
        org.junit.Assert.assertEquals("\"say \"\"hi\"\"\"", AbTestFragment.esc("say \"hi\""));
        org.junit.Assert.assertEquals("", AbTestFragment.esc(null));
        org.junit.Assert.assertEquals("x", AbTestFragment.neutralizeFormula("x"));
    }
}
