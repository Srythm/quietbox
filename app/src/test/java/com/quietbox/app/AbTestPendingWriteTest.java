package com.quietbox.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * F-092 / F-093：AB 实验页写回在途保护与 store 异步构造的源码契约（纯 JVM）。
 *
 * <p>{@link WriteGate} 的 isPending / cancel / onResult 语义本身由 WriteGateTest /
 * WriteGateCancelTest 覆盖；这里只固定 AbTestFragment 是否真的按该语义接线：
 * refresh() 的回填必须被 isPending 门住、写入拒绝必须 cancel、commit 结果必须走
 * onResult，以及 store 构造只发生在后台单线程、不在 onCreatePreferences/主线程。
 */
public class AbTestPendingWriteTest {

    private static String fragmentSource() throws Exception {
        Path source = TestPaths.resolveFromCwd(
                "app/src/main/java/com/quietbox/app/AbTestFragment.java",
                "QuietBox/project/app/src/main/java/com/quietbox/app/AbTestFragment.java",
                "src/main/java/com/quietbox/app/AbTestFragment.java",
                "project/app/src/main/java/com/quietbox/app/AbTestFragment.java");
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    /** 在途写入的 key 不得被回读值覆盖总开关（F-092 的核心）。 */
    @Test
    public void refreshSkipsMasterCheckedValueWhileWriteIsPending() throws Exception {
        String src = fragmentSource();
        int apply = src.indexOf("private void applyRefresh(");
        assertTrue("applyRefresh 必须存在", apply >= 0);
        int guard = src.indexOf("if (!mWriteGate.isPending(Feature.AB_TEST_PANEL.key)) {", apply);
        int setChecked = src.indexOf("mMaster.setCheckedValue(master);", apply);
        assertTrue("总开关回填前必须检查 isPending", guard >= 0);
        assertTrue("isPending 门必须包住 setCheckedValue", guard >= 0 && setChecked > guard);
    }

    /** 在途写入的 key 不得被回读值覆盖各 item 的勾选与摘要。 */
    @Test
    public void refreshSkipsItemCheckedAndSummaryWhileWriteIsPending() throws Exception {
        String src = fragmentSource();
        int guard = src.indexOf("if (!mWriteGate.isPending(test.key)) {");
        assertTrue("item 回填前必须检查 isPending(test.key)", guard >= 0);
        int end = src.indexOf("refreshOther(", guard);
        String block = src.substring(guard, end > guard ? end : src.length());
        assertTrue("在途保护必须覆盖 setCheckedValue", block.contains("item.setCheckedValue("));
        assertTrue("在途保护必须覆盖 updateItem（乐观摘要）", block.contains("updateItem("));
    }

    /** 写入未排进后台队列时不会有回调：必须 cancel 释放在途标记，否则开关被冻结。 */
    @Test
    public void rejectedWriteCancelsPendingGate() throws Exception {
        String src = fragmentSource();
        assertTrue("master 写入拒绝路径必须 cancel",
                src.contains("mWriteGate.cancel(key, seq);"));
        assertTrue("item 写入拒绝路径必须 cancel",
                src.contains("mWriteGate.cancel(test.key, seq);"));
    }

    /** commit 结果必须经 onResult 判最新序号并释放在途，成功也要补刷新纠正 UI。 */
    @Test
    public void writeResultGoesThroughGateOnResultAndRefreshes() throws Exception {
        String src = fragmentSource();
        int onResult = src.indexOf("private void onWriteResult(");
        assertTrue("onWriteResult 必须存在", onResult >= 0);
        int gate = src.indexOf("mWriteGate.onResult(key, seq, committed, requested)", onResult);
        assertTrue("commit 结果必须交给 WriteGate.onResult", gate >= 0);
        // 成功分支（keepUiState==true）后必须再有 refresh()：修掉「成功不补刷新」。
        int keep = src.indexOf("if (ConfigWritePolicy.keepUiState(true, committed)) {", gate);
        assertTrue("成功判定必须存在", keep >= 0);
        int successRefresh = src.indexOf("refresh();", keep);
        int successReturn = src.indexOf("return;", keep);
        assertTrue("成功后必须补一次 refresh() 纠正已被跳过的乐观状态",
                successRefresh >= 0 && successReturn >= 0 && successRefresh < successReturn);
    }

    /** store 构造不得出现在主线程入口：onCreatePreferences 里不能再 new ConfigStore/AbConfigStore。 */
    @Test
    public void storesAreNotConstructedOnMainThread() throws Exception {
        String src = fragmentSource();
        int create = src.indexOf("public void onCreatePreferences(");
        assertTrue("onCreatePreferences 必须存在", create >= 0);
        int resume = src.indexOf("public void onResume()", create);
        String onCreate = src.substring(create, resume > create ? resume : src.length());
        assertFalse("onCreatePreferences 不得在主线程构造 ConfigStore",
                onCreate.contains("new ConfigStore("));
        assertFalse("onCreatePreferences 不得在主线程构造 AbConfigStore",
                onCreate.contains("new AbConfigStore("));
    }

    /** 构造必须在后台单线程执行，且结果经 host.runOnUiThread 回主线程套用。 */
    @Test
    public void storeConstructionRunsOnBackgroundExecutorAndAppliesOnUiThread() throws Exception {
        String src = fragmentSource();
        int build = src.indexOf("private void buildStoresAsync(");
        assertTrue("buildStoresAsync 必须存在", build >= 0);
        int end = src.indexOf("private void onWriteResult(", build);
        String block = src.substring(build, end > build ? end : src.length());
        assertTrue("store 必须排到后台单线程 executor",
                block.contains("STORE_BUILD_EXECUTOR.execute("));
        assertTrue("构造必须真的发生在后台闭包内", block.contains("new ConfigStore(appCtx)")
                && block.contains("new AbConfigStore(appCtx)"));
        assertTrue("结果必须回主线程", block.contains("host.runOnUiThread("));
        assertTrue("回主线程后必须套用刷新", block.contains("applyRefresh(null)"));
        assertTrue("service 换绑的旧结果必须丢弃", block.contains("ServiceBridge.get() != service"));
    }

    /** 后台构造完成后仍需复用同一 service，refresh 才能立即套用而不重复构造。 */
    @Test
    public void refreshReturnsToApplyAfterAsyncBuild() throws Exception {
        String src = fragmentSource();
        int refreshB = src.indexOf("void refresh(Boolean masterOverride)");
        assertTrue("refresh(Boolean) 必须存在", refreshB >= 0);
        int apply = src.indexOf("applyRefresh(masterOverride);", refreshB);
        assertTrue("store 就绪后 refresh 必须立即 applyRefresh", apply >= 0);
        int ensure = src.indexOf("if (!ensureStores(ctx)) {", refreshB);
        assertTrue("未就绪时 refresh 必须早退等待后台构造", ensure >= 0 && ensure < apply);
    }
}
