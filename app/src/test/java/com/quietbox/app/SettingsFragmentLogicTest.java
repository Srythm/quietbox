package com.quietbox.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「恢复默认」入口的可用性判定（纯 JVM）。
 *
 * F-044 把 {@link ConfigStore} 的构造从主线程挪到后台单线程，构造完成前
 * {@code mStore} 为 null；此时点「恢复默认」不能触发写入（否则 NPE），
 * 必须等 {@code refresh()} 重建 store。这里固定这条判定。
 */
public class SettingsFragmentLogicTest {

    @Test
    public void resetIsBlockedUntilStoreBuilt() {
        assertFalse("后台构造中（store==null）不得触发恢复默认",
                SettingsFragment.canReset(null));
    }

    @Test
    public void resetAllowedOnceStoreExists() {
        // remote 不可用的 store 仍然可以触发：由 ConfigStore 自己拒绝写入并回调失败。
        ConfigStore store = new ConfigStore(null, new FakeSharedPreferences());
        assertTrue("store 已构造即可触发（remote 缺失由 store 拒绝）",
                SettingsFragment.canReset(store));
    }
}
