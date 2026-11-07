package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link ServiceBridge#schedulePoke} 的 JVM 单测（F-045）：
 *
 * bind() 由 MainActivity.onCreate 在主线程直接调用，而 poke 是同步
 * ContentResolver.call → XposedProvider 的 binder/Provider 调用。补投任务必须
 * 只入队、不在调用线程执行；执行器拒绝时也不能抛给主线程。
 */
public class ServiceBridgeTest {

    @Test
    public void pokeIsQueuedInsteadOfRunningInline() {
        AtomicInteger runs = new AtomicInteger();
        List<Runnable> queued = new ArrayList<>();
        Executor executor = queued::add;

        assertTrue(ServiceBridge.schedulePoke(executor, runs::incrementAndGet));
        assertEquals("bind() 返回前不得同步执行 poke（会阻塞主线程）", 0, runs.get());
        assertEquals(1, queued.size());

        queued.get(0).run();
        assertEquals("入队后由后台线程执行一次", 1, runs.get());
    }

    @Test
    public void rejectedPokeDoesNotThrowOrRun() {
        AtomicInteger runs = new AtomicInteger();
        Executor rejecting = task -> {
            throw new RejectedExecutionException("pool closed");
        };

        assertFalse("执行器拒绝时返回 false，不向主线程抛异常",
                ServiceBridge.schedulePoke(rejecting, runs::incrementAndGet));
        assertEquals(0, runs.get());
    }
}
