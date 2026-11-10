package com.quietbox.app.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

/**
 * F-098 / F-099：EX-02 的周期任务异常与 provider 通道故障必须留下可见痕迹。
 *
 * <p>纯 JVM：只驱动 {@link AbTestHooks.AbRuntime} 的包级 helper、限流计数与诊断记账，
 * 不构造 XposedModule / ContentResolver / 定时线程池。HookState 是进程级静态账本，
 * AB_TEST_PANEL 与 ReportTest 共用，用例前后 clearFailures 避免跨类污染。
 */
public class AbTestHooksDiagnosticsTest {

    private static final Class<?> RUNTIME = runtime();

    @Before
    public void resetCountersAndState() throws Exception {
        setInt("sRefreshErrors", 0);
        setInt("sPullErrors", 0);
        HookState.clearFailures(Feature.AB_TEST_PANEL);
    }

    @After
    public void clearDiagnosticsAndCounters() throws Exception {
        // 本类写入的 skip 不能污染其它测试类对同一 Feature 的断言。
        HookState.clearFailures(Feature.AB_TEST_PANEL);
        setInt("sRefreshErrors", 0);
        setInt("sPullErrors", 0);
    }

    /** 周期任务未预料异常的 WARN 前 3 条后静默，避免每 3 秒刷屏（F-098）。 */
    @Test
    public void refreshFailureWarnIsRateLimitedToFirstThree() {
        assertTrue(AbTestHooks.AbRuntime.warnRefreshFailure());
        assertTrue(AbTestHooks.AbRuntime.warnRefreshFailure());
        assertTrue(AbTestHooks.AbRuntime.warnRefreshFailure());
        assertFalse("第 4 条起必须静默", AbTestHooks.AbRuntime.warnRefreshFailure());
    }

    /** provider 拉取失败的 WARN 与 sPullErrors 同口径限流（F-099）。 */
    @Test
    public void pullFailureWarnIsRateLimitedToFirstThree() {
        assertTrue(AbTestHooks.AbRuntime.warnPullFailure());
        assertTrue(AbTestHooks.AbRuntime.warnPullFailure());
        assertTrue(AbTestHooks.AbRuntime.warnPullFailure());
        assertFalse("第 4 条起必须静默", AbTestHooks.AbRuntime.warnPullFailure());
    }

    /** 失败原因随 null 一起返回：三种失败（拒绝/超时/异常）不再一律静默（F-099）。 */
    @Test
    public void pullResultCarriesFailureReasonAlongsideNull() {
        AbTestHooks.AbRuntime.PullResult success =
                new AbTestHooks.AbRuntime.PullResult(new android.os.Bundle(), null);
        assertTrue(success.ok());

        AbTestHooks.AbRuntime.PullResult rejected =
                new AbTestHooks.AbRuntime.PullResult(null, "provider 调用被拒绝（上一次调用未完成）");
        assertFalse(rejected.ok());
        assertTrue(rejected.reason.contains("被拒绝"));

        AbTestHooks.AbRuntime.PullResult timedOut =
                new AbTestHooks.AbRuntime.PullResult(null, "provider 调用超时（2 秒）");
        assertFalse(timedOut.ok());
        assertTrue(timedOut.reason.contains("超时"));
    }

    /** 活通道故障必须进诊断页（非 critical），否则覆盖表静默停在上次快照（F-099）。 */
    @Test
    public void providerPullFailureIsRecordedInDiagnosticsWithoutCritical() {
        assertFalse(HookState.hasCriticalSkip(Feature.AB_TEST_PANEL));

        AbTestHooks.AbRuntime.onProviderPullFailed("provider 调用超时（2 秒）");

        String summary = HookState.skipSummary(Feature.AB_TEST_PANEL);
        assertTrue("provider 通道故障必须记进 skipSummary: " + summary,
                summary != null && summary.contains("provider 通道故障"));
        assertFalse("provider 通道故障不是主锚点缺失，不能误标 critical 触发失败横幅",
                HookState.hasCriticalSkip(Feature.AB_TEST_PANEL));
    }

    private static Class<?> runtime() {
        try {
            return Class.forName("com.quietbox.app.hooks.AbTestHooks$AbRuntime");
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static void setInt(String name, int value) throws Exception {
        Field f = RUNTIME.getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(null, value);
    }
}
