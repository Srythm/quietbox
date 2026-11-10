package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * F104/F105：回传状态机（sLast/sLastOk/sNextTry/sErrors）必须在同一把锁内串行化，
 * 且成功路径要清掉失败残留的 60 秒退避。定时 flush 与崩溃 flushNow 分处两条线程，
 * 旧实现并发读写会丢 sErrors 更新、撕裂「内容+时间」对，并且一次瞬时失败会把
 * 之后最多 60 秒的内容变化全部压掉。与 TiebaQuiet/QuietMap 已修复版本口径对齐。
 *
 * 纯 JVM：只调用包内可见的 noteSuccess/noteFailure/状态读帮忙方法，不碰 Android/Provider。
 */
public class ReportStateMachineTest {

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

    /** 成功回传必须清掉失败残留的退避，否则后续正常快照被压制最多一分钟。 */
    @Test
    public void successClearsFailureBackoff() {
        Report.noteFailure();
        long backoff = Report.nextTryAt();
        assertTrue("provider 失败后必须置退避", backoff > System.currentTimeMillis());
        assertEquals("失败必须计数（日志采样用）", 1, Report.failureCount());

        long sentAt = System.currentTimeMillis();
        Report.noteSuccess(content("1"), sentAt);

        assertEquals("成功后必须清掉失败残留的退避", 0L, Report.nextTryAt());
        assertEquals("成功后必须刷新已回传时间", sentAt,
                Long.parseLong(Report.lastSuccessForTest().split("@")[1]));
    }

    /** 崩溃路径 flushNow 与定时 flush 并发失败记账：sErrors++ 不得丢更新。 */
    @Test
    public void concurrentFailuresDoNotLoseUpdates() throws Exception {
        final int threads = 8;
        final int iterations = 500;
        final CountDownLatch ready = new CountDownLatch(threads);
        final CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < iterations; i++) {
                    Report.noteFailure();
                }
            }, "qb-report-fail-" + t);
            workers.add(worker);
            worker.start();
        }
        assertTrue("worker 未启动", ready.await(5L, TimeUnit.SECONDS));
        start.countDown();
        for (Thread worker : workers) {
            worker.join(10_000L);
        }
        assertEquals("并发失败记账一次都不能丢（旧实现的 sErrors++ 会丢更新）",
                threads * iterations, Report.failureCount());
    }

    /** 成对赋值（内容+时间）必须在同一把锁内：读侧不得出现「内容 w1 + 时间 w2」撕裂。 */
    @Test
    public void concurrentSuccessWritesAreNotTorn() throws Exception {
        final int writers = 4;
        final CountDownLatch ready = new CountDownLatch(writers);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicBoolean torn = new AtomicBoolean(false);
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            final int id = w;
            Thread worker = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int round = 0; round < 2_000 && !torn.get(); round++) {
                    Report.noteSuccess(content(String.valueOf(id)), id);
                }
            }, "qb-report-send-" + w);
            workers.add(worker);
            worker.start();
        }
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 20_000 && !torn.get(); i++) {
                String success = Report.lastSuccessForTest();
                int id = success.indexOf("hit.pair=");
                int at = success.lastIndexOf('@');
                if (id < 0 || at < 0) {
                    continue;
                }
                String value = success.substring(id + "hit.pair=".length(),
                        id + "hit.pair=".length() + 1);
                int parsed;
                long sentAt;
                try {
                    parsed = Integer.parseInt(value);
                    sentAt = Long.parseLong(success.substring(at + 1));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (parsed != sentAt) {
                    torn.set(true);
                }
            }
        }, "qb-report-read");
        reader.start();
        assertTrue("worker 未启动", ready.await(5L, TimeUnit.SECONDS));
        start.countDown();
        for (Thread worker : workers) {
            worker.join(10_000L);
        }
        reader.join(10_000L);
        assertFalse("已回传内容与时间必须同源，不得出现撕裂（内容 w1 + 时间 w2）",
                torn.get());
    }
}
