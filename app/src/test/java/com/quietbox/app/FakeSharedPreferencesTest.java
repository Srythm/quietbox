package com.quietbox.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * F385：FakeEditor 必须与真实 SharedPreferences.Editor 一样按调用顺序生效
 * （同一 key 的最后一次 put/remove 决定结果），而不是固定「先删后加」。
 */
public class FakeSharedPreferencesTest {

    @Test
    public void removeAfterPutInSameEditorWins() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit().putString("k", "v").remove("k").apply();

        assertFalse("同一 editor 内 put 后再 remove 必须清键", prefs.contains("k"));
    }

    @Test
    public void putAfterRemoveInSameEditorWins() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit().putString("k", "old").apply();

        prefs.edit().remove("k").putString("k", "new").apply();

        assertEquals("remove 后再 put 必须留下新值", "new", prefs.getString("k", null));
    }

    @Test
    public void dropRemovalsStillAppliesLaterPut() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.setDropRemovals(true);

        prefs.edit().remove("k").putString("k", "kept").apply();

        assertEquals("模拟删除未落盘时，后续 put 仍必须生效",
                "kept", prefs.getString("k", null));
    }
}
