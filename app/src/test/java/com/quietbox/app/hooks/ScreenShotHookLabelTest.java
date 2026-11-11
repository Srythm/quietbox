package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import com.quietbox.app.Feature;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * F374：PV-01 的 hook id label 必须是语义稳定名。检测器混淆类名每版都变
 * （9.49.1 idc.d1 → 9.50.0 xec.d1），只能作为 BOUND 去重键与诊断自由文本，
 * 不能进入 HookKit.install 的 label（= hook id / reg 账本键）。
 */
public class ScreenShotHookLabelTest {

    /** 结构上满足检测器特征的外层类：Context 字段 + 唯一 (Uri, boolean) -> void。 */
    private static final class FakeDetectorOuter {
        private android.content.Context context;

        void onChange(android.net.Uri uri, boolean auto) {
        }
    }

    @Before
    public void clearBound() throws Exception {
        Field field = ScreenShotHooks.class.getDeclaredField("BOUND");
        field.setAccessible(true);
        ((Set<String>) field.get(null)).clear();
    }

    @Test
    public void hookLabelIsStableInsteadOfObfuscatedClassName() throws Exception {
        AtomicReference<String> hookId = new AtomicReference<>();
        XposedModule module = newModule(hookId);

        Method bind = ScreenShotHooks.class.getDeclaredMethod("bindOuter",
                XposedModule.class, Feature.class, Class.class);
        bind.setAccessible(true);
        bind.invoke(null, module, Feature.SCREENSHOT_DETECT_OFF, FakeDetectorOuter.class);

        assertNotNull("检测器结构满足时必须安装 hook", hookId.get());
        assertEquals("hook id 必须是语义稳定名，不能随混淆类名漂移",
                Feature.SCREENSHOT_DETECT_OFF.id + "#screenshotDetector", hookId.get());
        assertFalse("混淆类名不得进入 hook id",
                hookId.get().contains(FakeDetectorOuter.class.getName()));
    }

    private static XposedModule newModule(final AtomicReference<String> hookId) throws Exception {
        XposedInterface base = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class},
                (proxy, method, args) -> {
                    if ("hook".equals(method.getName())) {
                        ClassLoader cl = XposedInterface.class.getClassLoader();
                        return Proxy.newProxyInstance(cl,
                                new Class<?>[]{XposedInterface.HookBuilder.class,
                                        XposedInterface.HookHandle.class},
                                (p, m, a) -> {
                                    if ("setId".equals(m.getName())) {
                                        hookId.set((String) a[0]);
                                        return p;
                                    }
                                    if ("setExceptionMode".equals(m.getName())
                                            || "intercept".equals(m.getName())) {
                                        return p;
                                    }
                                    return "toString".equals(m.getName()) ? "fake-handle" : null;
                                });
                    }
                    return "toString".equals(method.getName()) ? "fake-module" : null;
                });
        XposedModule module = new XposedModule() {
        };
        module.attachFramework(base, () -> {
        });
        return module;
    }
}
