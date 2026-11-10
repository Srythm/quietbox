package com.quietbox.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * C16 / F-091：AbReportProvider 的 report 通道不得把「有条目被拒绝/截断」的批次
 * 谎报成成功。
 *
 * <p>宿主 AbTestHooks.flush 只要拿到非 null Bundle 就把整批 keys 全部标记为已上报，
 * 被静默丢弃的探测值会永久消失、不再重试。{@link AbReportProvider#applyReport}
 * 下沉了纯 JVM 可测的校验/写入逻辑：只有整批全收下才返回 true；有任一被拒或
 * 被 MAX_ITEMS 截断就返回 false，让调用方回 null 触发宿主退避重试。
 */
public class AbReportProviderTest {

    @Test
    public void allValidEntriesAreAcceptedAndPersisted() {
        FakeSharedPreferences sp = new FakeSharedPreferences();

        boolean all = AbReportProvider.applyReport(sp,
                new String[]{"andr_key_a", "local_exp_b"},
                new String[]{"v1", "v2"});

        assertTrue("整批合法必须返回成功", all);
        assertTrue("收下的值必须落盘", sp.values().containsKey("v:andr_key_a"));
        assertTrue("收下的值必须落盘", sp.values().containsKey("v:local_exp_b"));
    }

    @Test
    public void rejectedValueMakesWholeBatchFail() {
        FakeSharedPreferences sp = new FakeSharedPreferences();

        // 值里含换行控制字符：provider 的 validValue 拒绝，但收下的另一条仍落盘。
        boolean all = AbReportProvider.applyReport(sp,
                new String[]{"andr_good", "andr_bad"},
                new String[]{"ok", "line\nbreak"});

        assertFalse("有条目被校验拒绝时整批必须判失败（宿主据此退避重试）", all);
        assertTrue("合法的条目仍应落盘，重发幂等", sp.values().containsKey("v:andr_good"));
        assertFalse("被拒条目不得落盘", sp.values().containsKey("v:andr_bad"));
    }

    @Test
    public void invalidKeyMakesWholeBatchFail() {
        FakeSharedPreferences sp = new FakeSharedPreferences();

        // key 含空格：validKey 只允许字母数字/_-. ，被拒。
        boolean all = AbReportProvider.applyReport(sp,
                new String[]{"andr_good", "bad key!"},
                new String[]{"ok", "v"});

        assertFalse("非法 key 被拒时整批必须判失败", all);
        assertFalse("非法 key 不得落盘", sp.values().containsKey("v:bad key!"));
    }

    @Test
    public void truncatedBatchMakesWholeBatchFail() {
        FakeSharedPreferences sp = new FakeSharedPreferences();
        // MAX_ITEMS = 800：构造 801 条合法条目，最后一条会被静默截断。
        int n = 801;
        String[] keys = new String[n];
        String[] values = new String[n];
        for (int i = 0; i < n; i++) {
            keys[i] = "andr_k" + i;
            values[i] = "v" + i;
        }

        boolean all = AbReportProvider.applyReport(sp, keys, values);

        assertFalse("超过 MAX_ITEMS 的截断批次必须判失败，不能让末尾条目永久消失", all);
        assertTrue("被截断前收下的条目仍应落盘", sp.values().containsKey("v:andr_k0"));
    }

    @Test
    public void mismatchedArraysAreRejected() {
        FakeSharedPreferences sp = new FakeSharedPreferences();

        assertFalse("keys/values 长度不一致必须判失败",
                AbReportProvider.applyReport(sp,
                        new String[]{"andr_a"}, new String[]{"v1", "v2"}));
        assertFalse("keys 为 null 必须判失败",
                AbReportProvider.applyReport(sp, null, new String[]{"v"}));
    }
}
