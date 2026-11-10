package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Collections;

/**
 * F-113：读异常注入开关必须对 SharedPreferences 的全部读方法一致生效。
 *
 * <p>旧实现只在 getString / getBoolean 抛异常，getAll / getStringSet / getInt /
 * getLong / getFloat / contains 静默忽略 mThrowOnRead，生产侧被 try/catch 容错的读
 * 路径（DiagnosticsFragment.readLong、StatusProvider.reportHit、AbConfigStore.read）
 * 在 fake 上永远造不出异常。本用例逐个断言全部读方法都能触发「读抛异常」。
 */
public class FakeSharedPreferencesReadFailureTest {

    @Test
    public void throwOnReadAppliesToAllReadMethods() {
        FakeSharedPreferences sp = new FakeSharedPreferences();
        sp.edit().putString("s", "v").putInt("i", 1).putLong("l", 2L)
                .putFloat("f", 3f).putBoolean("b", true)
                .putStringSet("set", Collections.singleton("x")).commit();

        sp.setThrowOnRead(true);

        assertThrowsOnRead(() -> sp.getAll());
        assertThrowsOnRead(() -> sp.getString("s", "d"));
        assertThrowsOnRead(() -> sp.getStringSet("set", Collections.<String>emptySet()));
        assertThrowsOnRead(() -> sp.getInt("i", 0));
        assertThrowsOnRead(() -> sp.getLong("l", 0L));
        assertThrowsOnRead(() -> sp.getFloat("f", 0f));
        assertThrowsOnRead(() -> sp.getBoolean("b", false));
        assertThrowsOnRead(() -> sp.contains("s"));
    }

    @Test
    public void throwOnReadOffReturnsStoredValues() {
        FakeSharedPreferences sp = new FakeSharedPreferences();
        sp.edit().putString("s", "v").putInt("i", 1).putLong("l", 2L)
                .putFloat("f", 3f).putBoolean("b", true)
                .putStringSet("set", Collections.singleton("x")).commit();

        sp.setThrowOnRead(false);

        assertEquals("v", sp.getString("s", "d"));
        assertEquals(1, sp.getInt("i", 0));
        assertEquals(2L, sp.getLong("l", 0L));
        assertEquals(3f, sp.getFloat("f", 0f), 0f);
        assertEquals(true, sp.getBoolean("b", false));
        assertEquals(true, sp.contains("s"));
        assertEquals(6, sp.getAll().size());
    }

    private static void assertThrowsOnRead(Runnable read) {
        try {
            read.run();
            fail("mThrowOnRead=true 时读方法必须抛 IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals("read boom", expected.getMessage());
        }
    }
}
