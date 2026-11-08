package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.quietbox.app.AbTest;
import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;

/**
 * AB 强开的类型兜底与状态回退规则（纯 JVM，不构造 Xposed 链）。
 *
 * 覆盖两处会静默失效/崩溃的路径：
 * - coerce 在运行期类型与声明类型不一致时若硬返回声明类型，调用方按自己的 Type
 *   checkcast 会在宿主进程抛 ClassCastException（#f/#b 的第 2 个 Type 参数不参与校验）；
 * - guessType 原先把所有非整数字面量当 BOOL，字符串/JSON 读点会被塞 Boolean；
 * - provider 拉取失败后不能用启动快照覆盖运行中改过的覆盖表。
 */
public class AbTestHooksTest {

    private static AbTest of(String forceValue, AbTest.ValueType type) {
        return AbTest.forKey("andr_some_experiment", forceValue, type);
    }

    @Test
    public void coerce_replacesOriginalWhenRuntimeTypeMatchesDeclaration() {
        assertEquals(Boolean.TRUE,
                AbTestHooks.coerce(of("true", AbTest.ValueType.BOOL), Boolean.FALSE));
        assertEquals(Integer.valueOf(1),
                AbTestHooks.coerce(of("1", AbTest.ValueType.INT), Integer.valueOf(0)));
    }

    @Test
    public void coerce_nullOriginalUsesDeclaredType() {
        // key 尚未下发时 original 为 null，不能静默丢弃强开。
        assertEquals(Boolean.TRUE,
                AbTestHooks.coerce(of("true", AbTest.ValueType.BOOL), null));
        assertEquals(Integer.valueOf(2),
                AbTestHooks.coerce(of("2", AbTest.ValueType.INT), null));
    }

    @Test
    public void coerce_keepsOriginalWhenRuntimeTypeDiffers() {
        Object number = Integer.valueOf(0);
        assertSame("声明 BOOL 但运行期是 Integer：返回 Integer 安全，返回 Boolean 会 CCE",
                number, AbTestHooks.coerce(of("true", AbTest.ValueType.BOOL), number));

        Object text = "raw";
        assertSame("声明 BOOL 但运行期是 String",
                text, AbTestHooks.coerce(of("true", AbTest.ValueType.BOOL), text));

        Object flag = Boolean.FALSE;
        assertSame("声明 INT 但运行期是 Boolean",
                flag, AbTestHooks.coerce(of("1", AbTest.ValueType.INT), flag));

        Object wide = Long.valueOf(1L);
        assertSame("声明 INT 也不改写 Long，避免调用方按 Long 强转时 CCE",
                wide, AbTestHooks.coerce(of("1", AbTest.ValueType.INT), wide));
    }

    @Test
    public void coerce_unparseableForceValueKeepsOriginal() {
        Object original = Integer.valueOf(7);
        assertSame(original,
                AbTestHooks.coerce(of("true", AbTest.ValueType.INT), original));
        assertSame(original,
                AbTestHooks.coerce(of("1", AbTest.ValueType.BOOL), original));
    }

    @Test
    public void guessType_numericLiteralIsInt() {
        AbTest test = AbTestHooks.AbRuntime.guessType("andr_unknown", "2");
        assertNotNull(test);
        assertEquals(AbTest.ValueType.INT, test.valueType);
        assertEquals("2", test.forceValue);
    }

    @Test
    public void guessType_booleanLiteralsAreBool() {
        AbTest upper = AbTestHooks.AbRuntime.guessType("andr_unknown", "TRUE");
        assertNotNull(upper);
        assertEquals(AbTest.ValueType.BOOL, upper.valueType);
        AbTest lower = AbTestHooks.AbRuntime.guessType("andr_unknown", "FALSE");
        assertNotNull(lower);
        assertEquals(AbTest.ValueType.BOOL, lower.valueType);
        assertEquals("FALSE", lower.forceValue);
    }

    @Test
    public void guessType_otherLiteralsAreNotGuessed() {
        // 旧实现把非整数字面量一律当 BOOL：字符串/JSON 读点会被塞 Boolean 或静默失效。
        assertNull(AbTestHooks.AbRuntime.guessType("andr_unknown", "hello"));
        assertNull(AbTestHooks.AbRuntime.guessType("andr_unknown", "{\"a\":1}"));
        assertNull(AbTestHooks.AbRuntime.guessType("andr_unknown", "1.5"));
        assertNull(AbTestHooks.AbRuntime.guessType("andr_unknown", null));
    }

    @Test
    public void startupSnapshotIsOnlyAllowedBeforeFirstProviderPull() {
        assertTrue("还没成功拉取过 provider：启动快照是唯一来源",
                AbTestHooks.AbRuntime.mayUseStartupSnapshot(false));
        assertFalse("provider 成功拉取过之后失败：必须保持上一次状态，不能用启动快照回退",
                AbTestHooks.AbRuntime.mayUseStartupSnapshot(true));
    }

    @Test
    public void providerCallPoolIsBoundedAndRejectsOverflow() throws Exception {
        java.util.concurrent.ExecutorService pool = AbTestHooks.AbRuntime.newCallPool();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try {
            pool.execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
            // 第二个任务进入容量 1 的队列。
            pool.execute(() -> {
            });
            try {
                pool.execute(() -> {
                });
                org.junit.Assert.fail("队列满后必须拒绝新任务，不能无限堆积 binder 调用");
            } catch (java.util.concurrent.RejectedExecutionException expected) {
                // 预期：最多 1 个运行 + 1 个排队。
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------ 命中上报（F-061）

    private static XposedModule newModule() {
        io.github.libxposed.api.XposedInterface base =
                (io.github.libxposed.api.XposedInterface) Proxy.newProxyInstance(
                        io.github.libxposed.api.XposedInterface.class.getClassLoader(),
                        new Class<?>[]{io.github.libxposed.api.XposedInterface.class},
                        (proxy, method, args) -> null);
        XposedModule module = new XposedModule() {
        };
        module.attachFramework(base, () -> {
        });
        return module;
    }

    /** Chain 替身：getArg(0) 为实验 key，proceed 返回原始结果并计数。 */
    private static final class AbChainFake {
        private final Object arg;
        private final AtomicInteger proceedCalls = new AtomicInteger();

        AbChainFake(Object arg) {
            this.arg = arg;
        }

        io.github.libxposed.api.XposedInterface.Chain proxy() {
            return (io.github.libxposed.api.XposedInterface.Chain) Proxy.newProxyInstance(
                    io.github.libxposed.api.XposedInterface.class.getClassLoader(),
                    new Class<?>[]{io.github.libxposed.api.XposedInterface.Chain.class},
                    (p, method, args) -> {
                        if ("getArg".equals(method.getName())) {
                            return arg;
                        }
                        if ("proceed".equals(method.getName())) {
                            proceedCalls.incrementAndGet();
                            return "orig";
                        }
                        return null;
                    });
        }
    }

    @Test
    public void valueHookReportsHitAndProceedsOnce() throws Throwable {
        Feature f = Feature.AB_TEST_PANEL;
        long before = HookState.hitCount(f);
        AbChainFake chain = new AbChainFake("andr_some_experiment");

        Object result = AbTestHooks.valueHook(newModule(), f).intercept(chain.proxy());

        assertEquals("原结果必须原样返回", "orig", result);
        assertEquals("proceed 必须恰好一次", 1, chain.proceedCalls.get());
        assertTrue("AB 读点命中必须接 HookKit.hit（EX-02 不再恒为 0）",
                HookState.hitCount(f) > before);
    }

    @Test
    public void stringHookReportsHitAndProceedsOnce() throws Throwable {
        Feature f = Feature.AB_TEST_PANEL;
        long before = HookState.hitCount(f);
        AbChainFake chain = new AbChainFake("andr_some_experiment");

        Object result = AbTestHooks.stringHook(newModule(), f).intercept(chain.proxy());

        assertEquals("原结果必须原样返回", "orig", result);
        assertEquals("proceed 必须恰好一次", 1, chain.proceedCalls.get());
        assertTrue("ConfigCenter 读点命中必须接 HookKit.hit（EX-02 不再恒为 0）",
                HookState.hitCount(f) > before);
    }
}
