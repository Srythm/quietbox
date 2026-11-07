package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * WatermarkHooks.applyWatermarkFlags 的 JVM 单测。
 *
 * dexsql 对 9.52.1 的结论（见 WatermarkHooks.applyWatermarkFlags 注释）：
 * bm8.i#i 共 11 参，index 5 = disableWaterMark，index 10 = disableAiWaterMark
 * （AND 上 bm8.i#b() 后进 bm8.j#a 第 6 参）；index 8 / 9 与水印绘制无关，不许置位。
 * 9.52.0 对应 jn6.i#i / jn6.j#a，9.51.0 对应 ce8.i#i / ce8.j#a，布尔位语义相同。
 */
public class WatermarkHooksTest {

    /** 按真实签名 (Activity, String, String, String, String, Z, F1, F1, Z, Z, Z) 造参数。 */
    private static Object[] elevenArgs() {
        return new Object[]{
                "activity", "noteId", "url", "path", "traceId",
                Boolean.FALSE, "fn1", "fn2",
                Boolean.FALSE, Boolean.FALSE, Boolean.FALSE,
        };
    }

    @Test
    public void elevenArgs_setsDisableWaterMarkAtIndex5() {
        Object[] out = WatermarkHooks.applyWatermarkFlags(elevenArgs());
        assertEquals(Boolean.TRUE, out[5]);
    }

    @Test
    public void elevenArgs_setsDisableAiWaterMarkAtIndex10() {
        Object[] out = WatermarkHooks.applyWatermarkFlags(elevenArgs());
        assertEquals(Boolean.TRUE, out[10]);
    }

    @Test
    public void elevenArgs_doesNotTouchOtherSlots() {
        Object[] in = elevenArgs();
        Object[] out = WatermarkHooks.applyWatermarkFlags(in);
        // 非 5/10 槽位原样保留：对象引用相同、无关布尔位不许被置 true。
        for (int i = 0; i < out.length; i++) {
            if (i == 5 || i == 10) {
                continue;
            }
            assertSame("slot " + i + " must keep original value", in[i], out[i]);
        }
        assertEquals(Boolean.FALSE, out[8]);
        assertEquals(Boolean.FALSE, out[9]);
    }

    /**
     * 置位必须与输入无关：输入为 FALSE（与目标值相反）时也要被强制置 TRUE，
     * 否则「只 clone 不置位」的退化实现不会被发现；输入已是 TRUE 时输出仍为
     * TRUE，锁住「无条件置位」语义而非按输入合并。
     */
    @Test
    public void elevenArgs_overwritesTrueInputs() {
        Object[] in = elevenArgs();
        in[5] = Boolean.FALSE;
        in[10] = Boolean.FALSE;
        Object[] out = WatermarkHooks.applyWatermarkFlags(in);
        assertEquals("输入 FALSE 必须被强制置为 TRUE", Boolean.TRUE, out[5]);
        assertEquals("输入 FALSE 必须被强制置为 TRUE", Boolean.TRUE, out[10]);

        Object[] inTrue = elevenArgs();
        inTrue[5] = Boolean.TRUE;
        inTrue[10] = Boolean.TRUE;
        Object[] outTrue = WatermarkHooks.applyWatermarkFlags(inTrue);
        assertEquals("输入 TRUE 时输出仍为 TRUE", Boolean.TRUE, outTrue[5]);
        assertEquals("输入 TRUE 时输出仍为 TRUE", Boolean.TRUE, outTrue[10]);
    }

    @Test
    public void elevenArgs_returnsCopy_notInputArray() {
        Object[] in = elevenArgs();
        Object[] out = WatermarkHooks.applyWatermarkFlags(in);
        assertNotSame(in, out);
        // 输入数组不被原地修改，调用方拿不到半改状态。
        assertEquals(Boolean.FALSE, in[5]);
        assertEquals(Boolean.FALSE, in[10]);
    }

    @Test
    public void nonElevenArgs_returnsNull() {
        assertNull(WatermarkHooks.applyWatermarkFlags(null));
        assertNull(WatermarkHooks.applyWatermarkFlags(new Object[0]));
        assertNull(WatermarkHooks.applyWatermarkFlags(new Object[6]));
        // 9.52.1 的 12 参重载 bm8.i#g（9.52.0 为 jn6.i#g，9.51.0 为 ce8.i#g）与 9.50.0 的 nd8.i#g 都不匹配。
        assertNull(WatermarkHooks.applyWatermarkFlags(new Object[12]));
        assertNull(WatermarkHooks.applyWatermarkFlags(new Object[13]));
    }

    /** 防止重构成实例方法/丢失 package-private 可见性时测试静默失效。 */
    @Test
    public void applyWatermarkFlags_isPackagePrivateStatic() throws Exception {
        Method m = WatermarkHooks.class.getDeclaredMethod("applyWatermarkFlags", Object[].class);
        int mods = m.getModifiers();
        assertTrue(Modifier.isStatic(mods));
        assertTrue("应保持 package-private，供同包测试直接调用",
                (mods & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE)) == 0);
    }

    // -------------------------------------------------- 命中上报必须反映真实结果（F-051）

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

    @Test
    public void composeHooker_doesNotReportHitWhenShapeMismatch() throws Throwable {
        Feature f = Feature.WATERMARK_POLICY;
        long before = HookState.hitCount(f);
        ChainFake chain = new ChainFake("a", "b", "c", "d", "e");

        Object result = WatermarkHooks.composeHooker(newModule(), f).intercept(chain.proxy());

        assertEquals("参数形状不符时水印仍会绘制，不能报命中",
                before, HookState.hitCount(f));
        assertEquals("形状不符必须原参 proceed 一次", 1, chain.proceedCalls.get());
        assertEquals("proceeded", result);
    }

    @Test
    public void composeHooker_reportsHitOnlyAfterFlagsApplied() throws Throwable {
        Feature f = Feature.WATERMARK_POLICY;
        long before = HookState.hitCount(f);
        ChainFake chain = new ChainFake("a", "b", "c", "d", "e", Boolean.FALSE);

        WatermarkHooks.composeHooker(newModule(), f).intercept(chain.proxy());

        assertEquals("真正置位后才允许记一次命中", before + 1, HookState.hitCount(f));
        assertEquals(1, chain.proceedCalls.get());
        assertEquals(Boolean.TRUE, chain.proceededArgs.get()[4]);
        assertEquals(Boolean.TRUE, chain.proceededArgs.get()[5]);
    }

    @Test
    public void saveEntryHooker_doesNotReportHitWhenArityMismatch() throws Throwable {
        Feature f = Feature.WATERMARK_POLICY;
        long before = HookState.hitCount(f);
        ChainFake chain = new ChainFake("a", "b", "c", "d", "e", Boolean.FALSE);

        WatermarkHooks.saveEntryHooker(newModule(), f).intercept(chain.proxy());

        assertEquals("非 11 参重载走原参、水印照绘，不能报命中",
                before, HookState.hitCount(f));
        assertEquals(1, chain.proceedCalls.get());
        assertNull("形状不符时不得传改写入参", chain.proceededArgs.get());
    }

    @Test
    public void saveEntryHooker_reportsHitAfterFlagsApplied() throws Throwable {
        Feature f = Feature.WATERMARK_POLICY;
        long before = HookState.hitCount(f);
        ChainFake chain = new ChainFake(elevenArgs());

        WatermarkHooks.saveEntryHooker(newModule(), f).intercept(chain.proxy());

        assertEquals("11 参形状真正置位后才允许记一次命中",
                before + 1, HookState.hitCount(f));
        assertEquals(1, chain.proceedCalls.get());
        assertEquals(Boolean.TRUE, chain.proceededArgs.get()[5]);
        assertEquals(Boolean.TRUE, chain.proceededArgs.get()[10]);
    }
}
