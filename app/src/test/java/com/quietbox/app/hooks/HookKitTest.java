package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

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
        assertFalse("null 不得命中豁免", HookKit.isDiscoveryFrameworkClass(null));
    }

    @Test
    public void discoveryInstallStillRefusesOtherFrameworkClasses() throws Exception {
        Feature f = Feature.UPDATE_DIALOG_BLOCK;
        HookState.clearFailures(f);
        XposedModule module = newModule();

        boolean installed = HookKit.installForDiscovery(module, f, "framework-probe",
                Object.class.getMethod("toString"), new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        return chain.proceed();
                    }
                });

        assertFalse("发现入口不得把 java.lang.Object 也放进去", installed);
        assertTrue("必须继续记框架类黑名单 skip：" + HookState.skipReasons(f),
                HookState.skipReasons(f).stream()
                        .anyMatch(r -> r.contains("框架类黑名单")
                                && r.contains("java.lang.Object")));
    }

    @Test
    public void discoveryInstallAllowsContentResolverOnly() throws Exception {
        Feature f = Feature.SCREENSHOT_DETECT_OFF;
        HookState.clearFailures(f);
        XposedModule module = newModule();
        Class<?> resolver = Class.forName("android.content.ContentResolver");
        // 单测 classpath 的 android.jar 桩没有 ContentObserver，按名字+参数个数取重载。
        Method m = null;
        for (Method candidate : resolver.getDeclaredMethods()) {
            if ("registerContentObserver".equals(candidate.getName())
                    && candidate.getParameterTypes().length == 3) {
                m = candidate;
                break;
            }
        }
        assertNotNull("测试前提：必须取到 registerContentObserver 三参重载", m);

        boolean installed = HookKit.installForDiscovery(module, f, "registerContentObserver", m,
                new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        return chain.proceed();
                    }
                });

        // 单测下 module.hook 是空实现，落不到 installed；关键是「不被黑名单拒绝」。
        assertFalse(installed);
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
            if (HookKit.shouldLogHit(before + i)) {
                expected++;
            }
        }
        assertEquals("命中计数每次都累加", before + calls, HookState.hitCount(f));
        assertEquals("日志必须按采样窗口输出，不得逐次刷屏", expected, logs.size());
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
