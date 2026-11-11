package com.quietbox.app.hooks;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 框架类黑名单（DESIGN §14 第 6 条）的 JVM 单测（F-049）：
 *
 * 唯一安装入口 HookKit.install 必须在 module.hook 之前拒绝
 * android./java./dalvik./androidx./kotlin. 开头的类，并留下可诊断的 skip；
 * 否则 hook bootclasspath 类会把目标进程挂掉。PV-01 的
 * ContentResolver 运行时发现是唯一显式例外（installForDiscovery）。
 */
public class HookKitTest {

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

    /**
     * 记录 module.hook() 调用次数；hook() 返回 null，等价 HookKit.install 的失败分支。
     * 用来证明豁免获批后确实发起了挂载，而不只是「没被黑名单拒绝」。
     */
    private static XposedModule hookCountingModule(AtomicInteger hookCalls) {
        XposedInterface base = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class},
                (proxy, method, args) -> {
                    if ("hook".equals(method.getName())) {
                        hookCalls.incrementAndGet();
                    }
                    return null;
                });
        XposedModule module = new XposedModule() {
        };
        module.attachFramework(base, () -> {
        });
        return module;
    }

    @Test
    public void frameworkPrefixesAreRejected() {
        for (String name : new String[]{
                "android.app.Application", "android.content.ContentResolver",
                "java.lang.Object", "dalvik.system.DexFile",
                "androidx.preference.Preference", "kotlin.Unit"}) {
            assertTrue("必须拒绝框架类: " + name, HookKit.isFrameworkClass(name));
        }
        assertTrue("类名为空按框架类处理", HookKit.isFrameworkClass(null));
    }

    @Test
    public void hostClassesAreAllowed() {
        assertFalse(HookKit.isFrameworkClass(
                "com.xingin.xywebview.activity.ExternalWebViewActivityV2"));
        assertFalse(HookKit.isFrameworkClass("com.xingin.xhs.app.XhsApplication"));
        assertFalse(HookKit.isFrameworkClass(""));
    }

    @Test
    public void discoveryExemptionIsNarrowedToContentResolverOnly() throws Exception {
        assertTrue("只有 ContentResolver 在运行时发现豁免名单内",
                HookKit.isDiscoveryFrameworkClass("android.content.ContentResolver"));
        // 录制/结构类都不行：把 Application 之类也走豁免入口必须仍被拒绝。
        assertFalse("android.app.Application 不在豁免名单内",
                HookKit.isDiscoveryFrameworkClass("android.app.Application"));
        assertFalse("java.lang.Object 不在豁免名单内",
                HookKit.isDiscoveryFrameworkClass("java.lang.Object"));
        assertFalse("dalvik.* 不在豁免名单内",
                HookKit.isDiscoveryFrameworkClass("dalvik.system.DexFile"));
        assertFalse("androidx.* 不在豁免名单内",
                HookKit.isDiscoveryFrameworkClass("androidx.preference.Preference"));
        assertFalse("kotlin.* 不在豁免名单内",
                HookKit.isDiscoveryFrameworkClass("kotlin.Unit"));
        assertFalse("null 不得命中豁免", HookKit.isDiscoveryFrameworkClass(null));
    }

    @Test
    public void discoveryInstallStillRefusesOtherFrameworkClasses() throws Exception {
        Feature f = Feature.UPDATE_DIALOG_BLOCK;
        HookState.clearFailures(f);
        AtomicInteger hookCalls = new AtomicInteger();
        XposedModule module = hookCountingModule(hookCalls);

        boolean installed = HookKit.installForDiscovery(module, f, "framework-probe",
                Object.class.getMethod("toString"), new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        return chain.proceed();
                    }
                });

        assertFalse("发现入口不得把 java.lang.Object 也放进去", installed);
        assertEquals("被拒框架类不得发起 module.hook()", 0, hookCalls.get());
        assertTrue("必须继续记框架类黑名单 skip：" + HookState.skipReasons(f),
                HookState.skipReasons(f).stream()
                        .anyMatch(r -> r.contains("框架类黑名单")
                                && r.contains("java.lang.Object")));
    }

    @Test
    public void discoveryInstallAllowsContentResolverOnly() throws Exception {
        Feature f = Feature.SCREENSHOT_DETECT_OFF;
        HookState.clearFailures(f);
        AtomicInteger hookCalls = new AtomicInteger();
        XposedModule module = hookCountingModule(hookCalls);
        Class<?> resolver = Class.forName("android.content.ContentResolver");
        // android-37.0 的 android.jar 桩包含 ContentObserver，按精确签名取重载。
        Method m = resolver.getMethod("registerContentObserver",
                Uri.class, boolean.class, ContentObserver.class);
        assertArrayEquals("必须与 ScreenShotHooks 挂载的 3 参重载一致",
                new Class<?>[]{Uri.class, boolean.class, ContentObserver.class},
                m.getParameterTypes());

        boolean installed = HookKit.installForDiscovery(module, f, "registerContentObserver", m,
                new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        return chain.proceed();
                    }
                });

        // 单测下 module.hook 是空实现，落不到 installed；关键是「不被黑名单拒绝」。
        assertFalse(installed);
        assertEquals("豁免获批后必须真的发起一次 module.hook()", 1, hookCalls.get());
        assertFalse("ContentResolver 必须走豁免，不得记框架类黑名单",
                HookState.skipReasons(f).stream()
                        .anyMatch(r -> r.contains("框架类黑名单")));
    }

    @Test
    public void installRefusesFrameworkMethodAndRecordsSkip() throws Exception {
        Feature f = Feature.UPDATE_DIALOG_BLOCK;
        HookState.clearFailures(f);
        XposedModule module = newModule();

        boolean installed = HookKit.install(module, f, "framework-probe",
                Object.class.getMethod("toString"), new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        return chain.proceed();
                    }
                });

        assertFalse("install 必须在 module.hook 之前拒绝 java.lang.Object", installed);
        assertTrue("必须留下可诊断的 skip 原因：" + HookState.skipReasons(f),
                HookState.skipReasons(f).stream()
                        .anyMatch(r -> r.contains("框架类黑名单") && r.contains("java.lang.Object")));
    }

    // ------------------------------------------------------------ 命中日志采样（F-117）

    /**
     * F-131：HookKit 的 skipVoid/constBool 必须返回匿名内部类 Hooker，不得是 lambda
     * （lambda 会在 bootclasspath desugar 下失效，DESIGN §14 第 1 条）。
     * 匿名类的名字形如 HookKit$1，lambda 则是 HookKit$$Lambda$xx；用 isSynthetic 区分。
     */
    @Test
    public void hookKitHelpersReturnAnonymousHookersNotLambdas() {
        XposedModule module = newModule();
        Feature f = Feature.VIDEO_DEFAULT_MUTE;

        XposedInterface.Hooker skip = HookKit.skipVoid(module, f, "probe");
        XposedInterface.Hooker bool = HookKit.constBool(module, f, "probe", true);

        assertFalse("skipVoid 不得返回 lambda（合成类）",
                skip.getClass().isSynthetic());
        assertFalse("constBool 不得返回 lambda（合成类）",
                bool.getClass().isSynthetic());
        assertTrue("skipVoid 必须是 HookKit 内的匿名内部类",
                skip.getClass().getName().startsWith(HookKit.class.getName() + "$"));
        assertTrue("constBool 必须是 HookKit 内的匿名内部类",
                bool.getClass().getName().startsWith(HookKit.class.getName() + "$"));
    }

    @Test
    public void hitLogSamplingWindowMatchesSiblings() {
        for (int i = 1; i <= 5; i++) {
            assertTrue("前 5 次必须打日志: " + i, HookKit.shouldLogHit(i));
        }
        for (int i = 6; i < 100; i++) {
            assertFalse("第 6~99 次必须采样掉: " + i, HookKit.shouldLogHit(i));
        }
        assertTrue("第 100 次必须打", HookKit.shouldLogHit(100));
        assertFalse("第 101 次采样掉", HookKit.shouldLogHit(101));
        assertTrue("第 200 次必须打", HookKit.shouldLogHit(200));
        assertTrue("第 1000 次必须打", HookKit.shouldLogHit(1000));
    }

    @Test
    public void hitOnlyLogsSampledLines() {
        Feature f = Feature.VIDEO_DEFAULT_MUTE;
        java.util.List<String> logs = new java.util.ArrayList<>();
        XposedModule module = recordingModule(logs);
        long before = HookState.hitCount(f);
        int calls = 205;
        for (int i = 0; i < calls; i++) {
            HookKit.hit(module, f, "probe");
        }

        int expected = 0;
        for (int i = 1; i <= calls; i++) {
            // 在测试内联采样窗口，期望值不依赖被测谓词，谓词回归才能被检出。
            long count = before + i;
            if (count <= 5 || count % 100 == 0) {
                expected++;
            }
        }
        assertEquals("命中计数每次都累加", before + calls, HookState.hitCount(f));
        assertEquals("日志必须按采样窗口输出，不得逐次刷屏", expected, logs.size());
    }

    /** finish()+额外动作+hit，且 proceed 恰好一次（F-066 共用模板）。 */
    @Test
    public void finishThenProceedRunsActionOnceAndProceedsExactlyOnce() throws Throwable {
        Feature f = Feature.VIDEO_DEFAULT_MUTE;
        FakeFinishingActivity activity = new FakeFinishingActivity();
        AtomicInteger actionCalls = new AtomicInteger();
        AtomicInteger proceedCalls = new AtomicInteger();
        XposedModule module = newModule();

        XposedInterface.Hooker hooker = HookKit.finishThenProceed(module, f, "onCreate(finish)",
                new HookKit.ActivityAction() {
                    @Override
                    public void run(Activity act) {
                        actionCalls.incrementAndGet();
                    }
                });
        Object result = hooker.intercept(chainFake(activity, proceedCalls).proxy());

        assertEquals("Activity 必须被 finish() 一次", 1, activity.finishCalls);
        assertEquals("额外动作必须恰好执行一次", 1, actionCalls.get());
        assertEquals("proceed 必须恰好一次（super.onCreate 仍由宿主执行）",
                1, proceedCalls.get());
        assertEquals("proceed 结果必须原样返回", "proceeded", result);
    }

    /** 无额外动作时模板同样 finish()+proceed 一次；非 Activity 也照样 proceed。 */
    @Test
    public void finishThenProceedWithoutActionStillProceedsOnce() throws Throwable {
        Feature f = Feature.VIDEO_DEFAULT_MUTE;
        AtomicInteger proceedCalls = new AtomicInteger();
        XposedInterface.Hooker hooker =
                HookKit.finishThenProceed(newModule(), f, "onCreate(finish)", null);

        Object result = hooker.intercept(chainFake(new Object(), proceedCalls).proxy());

        assertEquals("proceed 结果必须原样返回", "proceeded", result);
        assertEquals("thisObject 非 Activity 时不得破坏原调用", 1, proceedCalls.get());
    }

    /** 额外动作抛异常时仍必须 proceed 恰好一次（异常 fail-open，DESIGN §14 第 2/3 条）。 */
    @Test
    public void finishThenProceedStillProceedsWhenActionThrows() throws Throwable {
        Feature f = Feature.VIDEO_DEFAULT_MUTE;
        AtomicInteger proceedCalls = new AtomicInteger();
        XposedInterface.Hooker hooker = HookKit.finishThenProceed(newModule(), f, "onCreate(finish)",
                new HookKit.ActivityAction() {
                    @Override
                    public void run(Activity act) {
                        throw new IllegalStateException("action boom");
                    }
                });

        Object result = hooker.intercept(
                chainFake(new FakeFinishingActivity(), proceedCalls).proxy());

        assertEquals("动作异常不得吞掉 proceed 结果", "proceeded", result);
        assertEquals("动作异常时 proceed 仍必须恰好一次", 1, proceedCalls.get());
    }

    /** 记录 finish() 调用次数的 Activity 替身。 */
    static class FakeFinishingActivity extends Activity {
        int finishCalls;

        @Override
        public void finish() {
            finishCalls++;
        }

        @Override
        public Intent getIntent() {
            return new Intent();
        }
    }

    /** Chain 替身：getThisObject / proceed 计数。 */
    private static ChainFake chainFake(Object self, AtomicInteger proceedCalls) {
        return new ChainFake(self, proceedCalls);
    }

    private static final class ChainFake {
        private final Object self;
        private final AtomicInteger proceedCalls;

        ChainFake(Object self, AtomicInteger proceedCalls) {
            this.self = self;
            this.proceedCalls = proceedCalls;
        }

        XposedInterface.Chain proxy() {
            return (XposedInterface.Chain) Proxy.newProxyInstance(
                    XposedInterface.class.getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class},
                    (p, method, args) -> {
                        if ("getThisObject".equals(method.getName())) {
                            return self;
                        }
                        if ("proceed".equals(method.getName())) {
                            proceedCalls.incrementAndGet();
                            return "proceeded";
                        }
                        return null;
                    });
        }
    }

    private static XposedModule recordingModule(java.util.List<String> logs) {
        XposedInterface base = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class},
                (proxy, method, args) -> {
                    if ("log".equals(method.getName()) && args != null && args.length >= 3
                            && args[2] instanceof String) {
                        logs.add((String) args[2]);
                    }
                    return null;
                });
        XposedModule module = new XposedModule() {
        };
        module.attachFramework(base, () -> {
        });
        return module;
    }
}
