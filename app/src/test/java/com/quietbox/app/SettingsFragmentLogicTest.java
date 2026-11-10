package com.quietbox.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

    /**
     * F-009：恢复默认的结果回调在 view 已销毁时会早退，入口可用态不能跟着早退。
     *
     * <p>Preference 挂在 PreferenceManager 的 screen 上，不随 view 销毁而重建；
     * MainActivity 又用 add+hide/show 保留 fragment 实例（入口不会因重建而恢复）。
     * 只要只把入口恢复放在早退之后，或者完全不恢复，「恢复默认」就会永久置灰，
     * 只能重建 fragment 或重启进程。这里固定「在途=false 即可用」这条唯一规则。
     */
    @Test
    public void resetEntryStaysDisabledOnlyWhileInFlight() {
        assertFalse("后台事务在途期间入口必须禁用，避免重复提交",
                SettingsFragment.resetEntryEnabled(true));
        assertTrue("结果回调到达（含 view 已销毁早退）后入口必须恢复可用",
                SettingsFragment.resetEntryEnabled(false));
    }

    /**
     * 源码顺序护栏：恢复入口的 setEnabled 必须发生在「view 已销毁」的提前返回之前。
     * 纯判定测试固定不了这个顺序，而 F-009 的根因正是入口恢复被放在早退之后
     * （Preference 不随 view 重建、MainActivity 又保留 fragment 实例，于是永久置灰）。
     */
    @Test
    public void resetResultReenablesEntryBeforeViewDestroyedEarlyReturn() throws Exception {
        Path source = TestPaths.resolveFromCwd(
                "app/src/main/java/com/quietbox/app/SettingsFragment.java",
                "QuietBox/project/app/src/main/java/com/quietbox/app/SettingsFragment.java",
                "src/main/java/com/quietbox/app/SettingsFragment.java",
                "project/app/src/main/java/com/quietbox/app/SettingsFragment.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        int method = text.indexOf("private void onResetResult(");
        assertTrue("onResetResult 必须存在", method >= 0);
        int reenable = text.indexOf("setEnabled(resetEntryEnabled(mResetInFlight))",
                method);
        int earlyReturn = text.indexOf("if (!isAdded() || getView() == null) {", method);
        assertTrue("结果回调必须恢复入口可用态", reenable >= 0);
        assertTrue("结果回调必须保留 view 已销毁的提前返回", earlyReturn >= 0);
        assertTrue("恢复入口可用态必须早于 view 已销毁的提前返回，"
                + "否则入口会被永久置灰（F-009）", reenable < earlyReturn);
    }
}
