package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.quietbox.app.Config;
import com.quietbox.app.Feature;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Set;

/**
 * Hook 注册表完整性：所有非 blocked Feature 必须有 installer，blocked Feature 不得注册。
 *
 * Hooks 与 installerKeys() 均为 package-private，本测试放在同一包内直接读取，
 * 不为了测试放宽生产代码可见性。
 */
public class HookRegistryTest {

    @Test
    public void everyNonBlockedFeatureHasInstaller() {
        Set<Feature> registered = Hooks.installerKeys();
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                continue;
            }
            assertTrue("缺少 installer: " + f.id + " (" + f.key + ")",
                    registered.contains(f));
        }
    }

    @Test
    public void blockedFeaturesAreNotInstalled() {
        Set<Feature> registered = Hooks.installerKeys();
        int blocked = 0;
        for (Feature f : Feature.values()) {
            if (!f.blocked()) {
                continue;
            }
            blocked++;
            assertFalse("blocked Feature 不应注册 installer: " + f.id,
                    registered.contains(f));
        }
        assertTrue("应至少有一个 blocked Feature 用于校验该规则", blocked > 0);
    }

    @Test
    public void registryContainsExactlyTheNonBlockedFeatures() {
        Set<Feature> expected = EnumSet.noneOf(Feature.class);
        for (Feature f : Feature.values()) {
            if (!f.blocked()) {
                expected.add(f);
            }
        }
        Set<Feature> registered = Hooks.installerKeys();
        assertEquals("注册表必须恰好覆盖全部非 blocked Feature", expected, registered);
        assertFalse("installer 注册表为空", registered.isEmpty());
        assertFalse(registered.contains(null));
    }

    /**
     * F-064：Installer 不再带未使用的 Feature 形参。23 个 lambda 全部忽略过它，
     * 记账用的 Feature 由 installAll 循环变量持有，接口只剩 module/cl/config 三个参数。
     */
    @Test
    public void installerHasNoUnusedFeatureParameter() throws Exception {
        Method install = Hooks.Installer.class.getDeclaredMethod("install",
                io.github.libxposed.api.XposedModule.class, ClassLoader.class, Config.class);
        assertEquals("Installer.install 必须恰好三个参数", 3, install.getParameterCount());
        assertEquals("单一抽象方法仍须存在", 1, Hooks.Installer.class.getDeclaredMethods().length);
    }
}
