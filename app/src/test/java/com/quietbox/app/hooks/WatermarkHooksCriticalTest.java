package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * EN-01 主链失效必须计入失败横幅（F376）：
 *
 * <ul>
 *   <li>两个主锚点（watermark_compose / watermark_save_entry）全部缺失，或只有
 *       语义 getter 挂上时，critical=0 会让横幅不亮，必须记 critical；</li>
 *   <li>主链正常挂上时不得误记 critical；</li>
 *   <li>compose 侧改参只接受 6 参形状，12 参重载等一律 fail-open。</li>
 * </ul>
 */
public class WatermarkHooksCriticalTest {

    private static final Feature F = Feature.WATERMARK_POLICY;

    @Before
    public void setUp() {
        HookState.clearFailures(F);
    }

    @After
    public void tearDown() {
        HookState.clearFailures(F);
    }

    @Test
    public void noMountAtAllIsCritical() {
        WatermarkHooks.markMissing(F, 0, 0);

        assertTrue("主链与 getter 全部缺失必须标 critical", HookState.hasCriticalSkip(F));
        assertEquals("水印挂载点未找到", HookState.skipReason(F));
    }

    @Test
    public void onlySemanticGetterInstalledIsCritical() {
        WatermarkHooks.markMissing(F, 1, 0);

        assertTrue("只有语义 getter 生效时主链已失效，必须标 critical",
                HookState.hasCriticalSkip(F));
        assertEquals("水印主链挂载点未找到（仅语义 getter 生效）", HookState.skipReason(F));
    }

    @Test
    public void mainChainInstalledIsNotCritical() {
        WatermarkHooks.markMissing(F, 2, 2);

        assertFalse("主链正常挂上不得误记 critical", HookState.hasCriticalSkip(F));
        assertNull("没有缺失时不得写 skip 原因", HookState.skipReason(F));
    }

    @Test
    public void composeFlagsRejectNonSixArgShapes() {
        assertNull("7 参形状不得改写", WatermarkHooks.applyComposeFlags(new Object[7]));
        assertNull("12 参重载不得改写", WatermarkHooks.applyComposeFlags(new Object[12]));
        assertNull(WatermarkHooks.applyComposeFlags(null));

        Object[] six = new Object[]{null, null, null, null, Boolean.FALSE, Boolean.FALSE};
        Object[] out = WatermarkHooks.applyComposeFlags(six);
        assertNotSame("必须返回新数组，不能原地修改宿主入参", six, out);
        assertEquals(Boolean.TRUE, out[4]);
        assertEquals(Boolean.TRUE, out[5]);
    }
}
