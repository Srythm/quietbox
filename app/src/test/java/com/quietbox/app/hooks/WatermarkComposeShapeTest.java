package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * F108：画布合成钩子只接受与锚点一致的 6 参形状
 * {@code (Bitmap, Bitmap, Bitmap, Bitmap, boolean, boolean)}，其余（尤其同版本 12 参
 * 重载 bm8.i#g）一律原参 proceed 且不记 hit，避免把 Bitmap 引用位写成 Boolean.TRUE
 * 后仍报命中。shape 判定抽成纯函数 applyComposeFlags 供 JVM 直测。
 */
public class WatermarkComposeShapeTest {

    private static Object[] sixArgs() {
        return new Object[]{"b0", "b1", "b2", "b3", Boolean.FALSE, Boolean.FALSE};
    }

    @Test
    public void sixArgs_setsBothDisableFlagsAtIndex4And5() {
        Object[] in = sixArgs();
        Object[] out = WatermarkHooks.applyComposeFlags(in);
        assertEquals(Boolean.TRUE, out[4]);
        assertEquals(Boolean.TRUE, out[5]);
        assertNotSame("必须返回副本，不能原地改宿主编排的入参", in, out);
        assertEquals(Boolean.FALSE, in[4]);
        assertEquals(Boolean.FALSE, in[5]);
        for (int i = 0; i < 4; i++) {
            assertTrue("前 4 位 Bitmap 槽位必须原样保留", in[i] == out[i]);
        }
    }

    @Test
    public void nonSixArgs_returnsNull_noRewrite() {
        assertNull(WatermarkHooks.applyComposeFlags(null));
        assertNull(WatermarkHooks.applyComposeFlags(new Object[0]));
        assertNull(WatermarkHooks.applyComposeFlags(new Object[5]));
        assertNull("9.50.0 起的 12 参重载 bm8.i#g 不得命中",
                WatermarkHooks.applyComposeFlags(new Object[12]));
        assertNull(WatermarkHooks.applyComposeFlags(new Object[7]));
    }

    /** 12 参重载（且 slot 4/5 是 Bitmap 引用而非布尔）时必须原参 proceed、零 hit。 */
    @Test
    public void twelveArgOverload_proceedsOriginalArgsWithoutHit() throws Throwable {
        Feature f = Feature.WATERMARK_POLICY;
        long before = HookState.hitCount(f);
        ChainFake chain = new ChainFake(
                "b0", "b1", "b2", "b3", new Object(), new Object(),
                "a6", "a7", "a8", "a9", "a10", "a11");

        WatermarkHooks.composeHooker(newModule(), f).intercept(chain.proxy());

        assertEquals("12 参重载不得被当成锚点改写并报命中",
                before, HookState.hitCount(f));
        assertEquals("形状不符必须原参 proceed 恰好一次", 1, chain.proceedCalls.get());
        assertNull("形状不符时不得传入改写的入参数组", chain.proceededArgs.get());
    }

    @Test
    public void sixArgCompose_reportsHitAfterFlagsApplied() throws Throwable {
        Feature f = Feature.WATERMARK_POLICY;
        long before = HookState.hitCount(f);
        ChainFake chain = new ChainFake(sixArgs());

        WatermarkHooks.composeHooker(newModule(), f).intercept(chain.proxy());

        assertEquals("6 参形状真正置位后才允许记一次命中",
                before + 1, HookState.hitCount(f));
        assertEquals(1, chain.proceedCalls.get());
        assertEquals(Boolean.TRUE, chain.proceededArgs.get()[4]);
        assertEquals(Boolean.TRUE, chain.proceededArgs.get()[5]);
    }

    /** 记录 proceed 次数与实际入参的 Chain 替身。 */
    private static final class ChainFake {
        final List<Object> args;
        final AtomicInteger proceedCalls = new AtomicInteger();
        final AtomicReference<Object[]> proceededArgs = new AtomicReference<>();

        ChainFake(Object... args) {
            this.args = new ArrayList<>(Arrays.asList(args));
        }

        XposedInterface.Chain proxy() {
            return (XposedInterface.Chain) Proxy.newProxyInstance(
                    XposedInterface.class.getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class},
                    (p, method, methodArgs) -> {
                        if ("getArgs".equals(method.getName())) {
                            return args;
                        }
                        if ("proceed".equals(method.getName())) {
                            proceedCalls.incrementAndGet();
                            if (methodArgs != null && methodArgs.length == 1
                                    && methodArgs[0] instanceof Object[]) {
                                proceededArgs.set((Object[]) methodArgs[0]);
                            }
                            return "proceeded";
                        }
                        return null;
                    });
        }
    }

    private static XposedModule newModule() {
        XposedInterface base = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class},
                (proxy, method, args) -> null);
        XposedModule module = new XposedModule() {
        };
        module.attachFramework(base, () -> {
        });
        return module;
    }
}
