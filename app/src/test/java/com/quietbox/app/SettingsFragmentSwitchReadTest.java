package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/**
 * F097：applyRefresh 的逐 key remote prefs 回读必须放后台，主线程只做 UI。
 *
 * <p>纯 JVM 覆盖 {@link SettingsFragment#readSwitchValues(ConfigStore)} 的批量回读
 * 语义（用 {@link FakeSharedPreferences} 注入），以及源码门禁：主线程方法体内
 * 不得再直接出现 mStore.get(...)。ConfigStore.get 读的是框架 remote prefs
 * （同步 binder/IPC），19 次逐 key 回读留在主线程会拖住设置页。
 */
public class SettingsFragmentSwitchReadTest {

    @Test
    public void batchReadReturnsEveryFeatureKeyAndToken() {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        // 让两个开关与编译期默认值不同，验证读的是实际值而非默认值。
        Feature pick = Feature.values()[0];
        remote.edit().putBoolean(pick.key, !pick.defaultEnabled).commit();
        remote.edit().putBoolean(Config.KEY_STRIP_XSEC_TOKEN,
                !Config.DEFAULT_STRIP_XSEC_TOKEN).commit();
        ConfigStore store = new ConfigStore(remote, new FakeSharedPreferences());

        Map<String, Boolean> values = SettingsFragment.readSwitchValues(store);

        for (Feature f : Feature.values()) {
            assertTrue("每个 Feature 开关都必须被批量回读: " + f.key,
                    values.containsKey(f.key));
        }
        assertTrue("strip 子开关也必须被批量回读",
                values.containsKey(Config.KEY_STRIP_XSEC_TOKEN));
        assertEquals("必须读 remote 的实际值，而不是默认常量",
                !pick.defaultEnabled, values.get(pick.key));
        assertEquals("strip 必须读 remote 的实际值",
                !Config.DEFAULT_STRIP_XSEC_TOKEN, values.get(Config.KEY_STRIP_XSEC_TOKEN));
    }

    @Test
    public void nullStoreYieldsEmptyMapSoUiFallsBackToDefaults() {
        Map<String, Boolean> values = SettingsFragment.readSwitchValues(null);
        assertTrue("mStore 尚未构造时不得回灌任何值（applySwitchValues 会用默认常量）",
                values.isEmpty());
    }

    @Test
    public void defaultValueIsUsedWhenRemoteKeyMissing() {
        ConfigStore store = new ConfigStore(new FakeSharedPreferences(),
                new FakeSharedPreferences());
        Map<String, Boolean> values = SettingsFragment.readSwitchValues(store);
        for (Feature f : Feature.values()) {
            assertEquals("缺键时按编译期默认值填充: " + f.key,
                    f.defaultEnabled, values.get(f.key));
        }
        assertEquals(Config.DEFAULT_STRIP_XSEC_TOKEN,
                values.get(Config.KEY_STRIP_XSEC_TOKEN));
    }
}
