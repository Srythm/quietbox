package com.quietbox.app.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.quietbox.app.TestPaths;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * F372/F373：Report 的一次性入口「动作成功后才置位」顺序，以及失败退避只约束
 * 内容未变化的心跳、内容变化时立即重试。
 *
 * <p>纯 JVM：退避行为只调用包内可见的 shouldFlush/noteSuccess/noteFailure；
 * 置位顺序用源码护栏锁定（同 HookKitSourceScanTest 的做法），因为
 * Executors/Thread.setDefaultUncaughtExceptionHandler 的失败路径无法在 JVM 内注入。
 */
public class ReportInitAndBackoffTest {

    @Before
    public void setUp() {
        Report.resetStateForTest();
    }

    @After
    public void tearDown() {
        Report.resetStateForTest();
    }

    private static Map<String, String> content(String value) {
        Map<String, String> map = new TreeMap<>();
        map.put("hit.pair", value);
        return map;
    }

    /** 内容变化时必须立即重试，不能被一次失败残留的 60 秒退避压住。 */
    @Test
    public void changedContentRetriesDuringBackoff() {
        long now = System.currentTimeMillis();
        Report.noteSuccess(content("old"), now);
        Report.noteFailure();

        assertTrue("内容变化必须立即重试，不受失败退避限制",
                Report.shouldFlush(content("new"), now));
    }

    /** 内容未变化的心跳仍受退避约束：退避到期前不发，到期后放行。 */
    @Test
    public void unchangedHeartbeatStillWaitsForBackoff() {
        long now = System.currentTimeMillis();
        Report.noteSuccess(content("same"), now - 120_000L);
        Report.noteFailure();

        assertFalse("内容未变化时，心跳窗口到期也必须等退避结束",
                Report.shouldFlush(content("same"), now));
        assertTrue("退避到期后心跳可放行",
                Report.shouldFlush(content("same"), Report.nextTryAt()));
    }

    /** 内容未变化且未到心跳窗口：不发。 */
    @Test
    public void unchangedContentWithinHeartbeatWindowIsSuppressed() {
        long now = System.currentTimeMillis();
        Report.noteSuccess(content("same"), now);

        assertFalse(Report.shouldFlush(content("same"), now + 1_000L));
    }

    /**
     * 源码护栏：sStarted / sCrashHandlerInstalled 只能在对应副作用成功之后置位，
     * 否则一次瞬时失败会把该进程（含 installAll 重入）的重试路径永久关掉。
     */
    @Test
    public void oneTimeFlagsAreSetOnlyAfterSideEffectsSucceed() throws Exception {
        String source = read(reportSource());

        int started = source.indexOf("sStarted = true;");
        int scheduled = source.indexOf("scheduleWithFixedDelay");
        assertTrue("start() 必须先创建并调度线程", scheduled > 0 && started > 0);
        assertTrue("sStarted 必须在 scheduleWithFixedDelay 之后置位",
                scheduled < started);

        int installed = source.indexOf("sCrashHandlerInstalled = true;");
        int ueh = source.indexOf("Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {");
        assertTrue("installCrashHandler() 必须先换好 UEH", ueh > 0 && installed > 0);
        assertTrue("sCrashHandlerInstalled 必须在 setDefaultUncaughtExceptionHandler 之后置位",
                ueh < installed);
    }

    private static Path reportSource() {
        return TestPaths.resolveFromCwd(
                "src/main/java/com/quietbox/app/hooks/Report.java",
                "app/src/main/java/com/quietbox/app/hooks/Report.java",
                "project/app/src/main/java/com/quietbox/app/hooks/Report.java",
                "QuietBox/project/app/src/main/java/com/quietbox/app/hooks/Report.java");
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
