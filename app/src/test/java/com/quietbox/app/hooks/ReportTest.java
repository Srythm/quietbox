package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;
import com.quietbox.app.ReportProtocol;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Report.snapshot 的键完整性：reg/skip 对每个 touched Feature 都必须存在（可为空串），
 * hit 只在 >0 时出现；crash0/1/2（0 最新）始终存在，无崩溃为空串。
 * 回归「旧会话 skip 残留导致横幅永久误报」的问题。
 *
 * HookState / 崩溃环都是进程级静态状态：每个用例使用不同 Feature，或（AB_TEST_PANEL
 * 与其它测试类共用时）在 {@code @Before}/{@code @After} 显式 clearFailures，
 * 避免测试之间互相污染。
 *
 * <p>崩溃环与 sCrashHandlerInstalled 单向闩锁由 {@code @Before}/{@code @After}
 * 调用 {@link Report#resetForTest()} 复位，并复原默认 UEH：否则安装了崩溃处理器
 * 的用例会让后续用例在同一 JVM 里拿不到包装链、或读到残留崩溃。
 */
public class ReportTest {

    private Thread.UncaughtExceptionHandler originalHandler;

    @Before
    public void setUp() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler();
        Report.resetForTest();
        // F325：AB_TEST_PANEL 与 AbTestHooksTest/AbTestHooksDiagnosticsTest 共用进程级
        // HookState，且清单里已没有完全独占的 Feature。用例前后显式清空该 Feature 的
        // 失败标记，保证 skip/crit 断言不受跨类残留影响，也不把 critical 留给后续用例。
        HookState.clearFailures(Feature.AB_TEST_PANEL);
    }

    @After
    public void tearDown() {
        HookState.clearFailures(Feature.AB_TEST_PANEL);
        Thread.setDefaultUncaughtExceptionHandler(originalHandler);
        Report.resetForTest();
    }

    @Test
    public void snapshotSendsEmptySkipForRegisteredFeatureWithoutSkip() {
        Feature f = Feature.FEED_LIVE_CARD_BLOCK;
        HookState.installed(f, "alpha");

        Map<String, String> snap = Report.snapshot();

        assertEquals("alpha", snap.get(ReportProtocol.PREFIX_REG + f.key));
        assertTrue("无 skip 也必须发送 skip 键", snap.containsKey(ReportProtocol.PREFIX_SKIP + f.key));
        assertEquals("空串才能覆盖旧 skip", "", snap.get(ReportProtocol.PREFIX_SKIP + f.key));
        assertFalse("hit 为 0 时不发送，避免多余写入", snap.containsKey(ReportProtocol.PREFIX_HIT + f.key));
    }

    @Test
    public void snapshotSendsEmptyRegForSkipOnlyFeature() {
        Feature f = Feature.FEED_VOTE_CARD_BLOCK;
        HookState.markNotFound(f, "类未找到 x");

        Map<String, String> snap = Report.snapshot();

        assertTrue("未注册也必须发送 reg 键", snap.containsKey(ReportProtocol.PREFIX_REG + f.key));
        assertEquals("空串才能覆盖旧注册标签", "", snap.get(ReportProtocol.PREFIX_REG + f.key));
        assertEquals("类未找到 x", snap.get(ReportProtocol.PREFIX_SKIP + f.key));
    }

    @Test
    public void snapshotIncludesEnabledButNotInstalled() {
        Feature f = Feature.SEARCH_AD_FILTER;
        HookState.enable(f);

        Map<String, String> snap = Report.snapshot();

        assertEquals("enable 后必须发送空 reg，清掉旧会话标签", "",
                snap.get(ReportProtocol.PREFIX_REG + f.key));
        assertEquals("enable 后必须发送空 skip，清掉旧会话 skip", "",
                snap.get(ReportProtocol.PREFIX_SKIP + f.key));
    }

    @Test
    public void snapshotKeepsPositiveHit() {
        Feature f = Feature.FEED_GOODS_CARD_BLOCK;
        HookState.installed(f, "beta");
        HookState.hit(f);
        HookState.hit(f);

        Map<String, String> snap = Report.snapshot();

        assertEquals("2", snap.get(ReportProtocol.PREFIX_HIT + f.key));
        assertTrue(snap.containsKey(ReportProtocol.PREFIX_SKIP + f.key));
        assertEquals("", snap.get(ReportProtocol.PREFIX_SKIP + f.key));
    }

    @Test
    public void snapshotCarriesStableSessionAndUpdatedAt() {
        Map<String, String> first = Report.snapshot();
        String session = first.get(ReportProtocol.KEY_META_SESSION);
        assertNotNull("必须回传 meta.session", session);
        assertTrue("session 必须是 s<millis>-<8位hex> 格式，带进程随机后缀",
                session.matches("s\\d+-[0-9a-f]{8}"));
        assertTrue("必须回传 meta.updated_at",
                Long.parseLong(first.get(ReportProtocol.KEY_META_UPDATED_AT)) > 0L);

        Map<String, String> second = Report.snapshot();
        assertEquals("同一进程会话标识必须稳定", session,
                second.get(ReportProtocol.KEY_META_SESSION));
    }

    @Test
    public void snapshotMarksCriticalClassSkip() {
        Feature f = Feature.NOTE_DETAIL_AD_BANNER;
        HookState.markNotFoundCritical(f, "类未找到 x");

        Map<String, String> snap = Report.snapshot();

        assertEquals("1", snap.get(ReportProtocol.PREFIX_CRIT + f.key));
        assertEquals("类未找到 x", snap.get(ReportProtocol.PREFIX_SKIP + f.key));
    }

    @Test
    public void snapshotKeepsOptionalSkipNonCritical() {
        Feature f = Feature.PUSH_GUIDE_BLOCK;
        HookState.installed(f, "alpha");
        HookState.markNotFound(f, "方法未找到 x");

        Map<String, String> snap = Report.snapshot();

        assertEquals("alpha", snap.get(ReportProtocol.PREFIX_REG + f.key));
        assertEquals("方法未找到 x", snap.get(ReportProtocol.PREFIX_SKIP + f.key));
        assertEquals("方法级 skip 不能标 critical", "",
                snap.get(ReportProtocol.PREFIX_CRIT + f.key));
    }

    /**
     * 多条 skip 原因时，回传值保留首条 critical + 最后一条 optional：
     * 主锚点缺失不会因为后面又记了一条可选 skip 而从诊断里消失。
     */
    @Test
    public void snapshotSkipSummaryKeepsFirstCriticalAndLastOptional() {
        Feature f = Feature.AB_TEST_PANEL;
        HookState.markNotFound(f, "optional-before");
        HookState.markNotFoundCritical(f, "critical");
        HookState.markNotFound(f, "optional-after");

        Map<String, String> snap = Report.snapshot();

        assertEquals("critical | optional-after",
                snap.get(ReportProtocol.PREFIX_SKIP + f.key));
        assertEquals("1", snap.get(ReportProtocol.PREFIX_CRIT + f.key));
    }

    /** crash0/1/2 必须始终出现在快照里：无崩溃时为空串（覆盖清掉旧会话残留）。 */
    @Test
    public void snapshotAlwaysCarriesThreeCrashKeys() {
        Map<String, String> snap = Report.snapshot();

        assertTrue("crash0 必须回传", snap.containsKey(ReportProtocol.KEY_CRASH_0));
        assertTrue("crash1 必须回传", snap.containsKey(ReportProtocol.KEY_CRASH_1));
        assertTrue("crash2 必须回传", snap.containsKey(ReportProtocol.KEY_CRASH_2));
        // 复用同一批常量，避免手写 "crash"+i 与常量改名后静默漂移（F326）。
        String[] crashKeys = {ReportProtocol.KEY_CRASH_0, ReportProtocol.KEY_CRASH_1,
                ReportProtocol.KEY_CRASH_2};
        for (String key : crashKeys) {
            String value = snap.get(key);
            assertNotNull(value);
            assertTrue("crash 值必须通过回传长度校验", ReportProtocol.isValidValue(value));
        }
    }

    /** 崩溃环：最新在前、最多 3 条，新的顶掉最旧的。 */
    @Test
    public void crashRingKeepsLatestThreeNewestFirst() {
        Report.recordCrash(new Thread("qb-crash-t1"), new RuntimeException("one"));
        Report.recordCrash(new Thread("qb-crash-t2"), new RuntimeException("two"));
        Report.recordCrash(new Thread("qb-crash-t3"), new RuntimeException("three"));
        Report.recordCrash(new Thread("qb-crash-t4"), new RuntimeException("four"));

        Map<String, String> snap = Report.snapshot();

        assertTrue("crash0 是最新一条",
                snap.get(ReportProtocol.KEY_CRASH_0).contains("|qb-crash-t4|"));
        assertTrue(snap.get(ReportProtocol.KEY_CRASH_1).contains("|qb-crash-t3|"));
        assertTrue("第 4 条新崩溃顶掉了第 1 条",
                !snap.get(ReportProtocol.KEY_CRASH_2).contains("|qb-crash-t1|"));
        assertTrue(snap.get(ReportProtocol.KEY_CRASH_2).contains("|qb-crash-t2|"));
    }

    /** 崩溃记录格式：时间（epoch 毫秒）|线程名|异常类名|栈前 2 帧。 */
    @Test
    public void crashEntryFormatCarriesTimeThreadClassAndTwoFrames() {
        RuntimeException boom = new RuntimeException("format");
        // 换成受控短栈帧：JUnit 反射栈太长会被 240 截断，无法逐段断言。
        boom.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.a.B", "d", "B.java", 1),
                new StackTraceElement("com.e.F", "h", "F.java", 2)});
        Report.recordCrash(new Thread("qb-crash-fmt"), boom);

        String crash0 = Report.snapshot().get(ReportProtocol.KEY_CRASH_0);
        String[] parts = crash0.split("\\|", -1);

        assertEquals("时间|线程|归属+异常类名+message|帧1|帧2 共 5 段", 5, parts.length);
        long ts = Long.parseLong(parts[0]);
        assertTrue("首段必须是当前毫秒时间",
                Math.abs(System.currentTimeMillis() - ts) < 60_000L);
        assertEquals("qb-crash-fmt", parts[1]);
        assertEquals("纯宿主栈必须标记 [host] 且带 message",
                "[host] java.lang.RuntimeException: format", parts[2]);
        assertEquals("栈前 2 帧按 getStackTrace() 顺序拼接",
                "com.a.B.d(B.java:1)|com.e.F.h(F.java:2)", parts[3] + "|" + parts[4]);
    }

    /** F181：栈帧命中模块包必须标记 [module]，message 取 cause 链首个非空文案。 */
    @Test
    public void crashEntryTagsModuleFramesAndCarriesCauseMessage() {
        RuntimeException boom = new RuntimeException();
        boom.initCause(new IllegalStateException("root|cause"));
        boom.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.quietbox.app.hooks.HookKit", "install",
                        "HookKit.java", 10),
                new StackTraceElement("com.xingin.Host", "run", "Host.java", 20)});
        Report.recordCrash(new Thread("qb-crash-mod"), boom);

        String crash0 = Report.snapshot().get(ReportProtocol.KEY_CRASH_0);
        String[] parts = crash0.split("\\|", -1);

        assertEquals("message 里的 | 不得破坏分段", 5, parts.length);
        assertEquals("[module] java.lang.RuntimeException: root cause", parts[2]);
    }

    /** 单条截断 ≤240（MAX_VALUE=256 之内）：超长线程名只保留前 240 个字符。 */
    @Test
    public void crashEntryTruncatedTo240Chars() {
        Report.recordCrash(new Thread("x".repeat(300)), new RuntimeException("trunc"));

        String crash0 = Report.snapshot().get(ReportProtocol.KEY_CRASH_0);

        assertEquals("单条崩溃必须截断到 240 字符", 240, crash0.length());
        assertTrue("截断结果必须通过回传值长度校验", ReportProtocol.isValidValue(crash0));
    }

    /** 防御：null 线程 / null 异常不得抛出（UEH 内任何异常都会破坏原有处理链）。 */
    @Test
    public void recordCrashToleratesNullThreadAndThrowable() {
        Report.recordCrash(null, null);

        String crash0 = Report.snapshot().get(ReportProtocol.KEY_CRASH_0);
        assertNotNull("null 输入也要落一条占位记录", crash0);
        assertTrue("线程名/异常类名缺失时以 ? 占位", crash0.endsWith("|?"));
    }

    /**
     * 崩溃路径的同步 flush 在没有 provider/Context 的 JVM 环境里必须静默返回：
     * UEH 调用它时不允许任何异常逃逸。
     */
    @Test
    public void flushNowIsFailSafeWithoutProviderContext() {
        Report.flushNow();
    }

    /**
     * UEH 包装：幂等（static boolean 只包一次）、先记录崩溃再透传 previous；
     * previous 的异常处理行为不被改变（透传后原样交回）。
     */
    @Test
    public void crashHandlerWrapsPreviousOnceAndForwards() {
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        try {
            List<Throwable> forwarded = new ArrayList<>();
            Thread.UncaughtExceptionHandler previous = (t, e) -> forwarded.add(e);
            Thread.setDefaultUncaughtExceptionHandler(previous);

            Report.installCrashHandler();
            Thread.UncaughtExceptionHandler wrapped = Thread.getDefaultUncaughtExceptionHandler();
            assertNotNull("必须替换默认 UEH", wrapped);
            assertNotSame("默认 UEH 必须被包装", previous, wrapped);

            Report.installCrashHandler();
            assertSame("静态 boolean 必须保证只包一次", wrapped,
                    Thread.getDefaultUncaughtExceptionHandler());

            RuntimeException boom = new RuntimeException("wrapped");
            wrapped.uncaughtException(Thread.currentThread(), boom);

            assertEquals("previous 必须被透传", 1, forwarded.size());
            assertSame("透传的必须是原始异常", boom, forwarded.get(0));
            assertTrue("崩溃必须先落进快照",
                    Report.snapshot().get(ReportProtocol.KEY_CRASH_0)
                            .contains("java.lang.RuntimeException"));
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original);
        }
    }
}
