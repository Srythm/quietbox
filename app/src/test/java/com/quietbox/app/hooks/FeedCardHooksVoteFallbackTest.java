package com.quietbox.app.hooks;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import io.github.yigenhuobah.core.HookStateCore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * F100：modelType 缺失路径下投票卡（vote）没有字段兜底，旧实现直接 return false，
 * 既不进 cached()/markNotFound，HookKit.hit 也只在 removed>0 触发，导致 AD-10
 * 字段迁移后永久静默失效、诊断页零线索。修复后该路径必须留一条可观测的诊断说明。
 *
 * <p>纯 JVM：用反射驱动私有 matches（vote + 无 modelType 的宿主 item），断言
 * 1) 行为不变（仍返回 false，不误删）；2) 诊断账本出现「无 modelType、无字段兜底」
 * 的说明；3) 同一 (feature, 类型) 只记一次，避免列表遍历刷屏。
 */
public class FeedCardHooksVoteFallbackTest {

    private final Feature f = Feature.FEED_VOTE_CARD_BLOCK;

    @Before
    public void resetState() throws Exception {
        HookState.clearFailures(f);
        clearWarnedSet();
        core().clearFailures(f.key);
    }

    @Test
    public void voteWithoutModelTypeRecordsDiagnosticAndStillReturnsFalse() throws Exception {
        Method matches = FeedCardHooks.class.getDeclaredMethod(
                "matches", io.github.libxposed.api.XposedModule.class,
                Feature.class, Object.class, String.class);
        matches.setAccessible(true);

        // 宿主 item：没有 modelType 字段，也没有 vote/goods/live 兜底字段。
        NoModelTypeNote note = new NoModelTypeNote();

        Object result = matches.invoke(null, null, f, note, "vote");
        assertFalse("vote 无 modelType 时行为不变：仍返回 false（不误删）",
                (Boolean) result);

        String lastFailure = core().lastFailure(f.key);
        assertNotNull("vote 无兜底路径必须留下诊断说明（note），不能零线索", lastFailure);
        assertTrue("诊断说明必须点明无 modelType/无字段兜底: " + lastFailure,
                lastFailure.contains("modelType") && lastFailure.contains("vote"));
    }

    @Test
    public void voteWithoutModelTypeWarnsOncePerFeatureAndType() throws Exception {
        Method matches = FeedCardHooks.class.getDeclaredMethod(
                "matches", io.github.libxposed.api.XposedModule.class,
                Feature.class, Object.class, String.class);
        matches.setAccessible(true);

        List<Object> notes = Arrays.asList(
                new NoModelTypeNote(), new NoModelTypeNote(), new NoModelTypeNote());
        for (Object note : notes) {
            matches.invoke(null, null, f, note, "vote");
        }

        assertEquals("同一 (feature, 类型) 只记一次，避免每个 item 刷一条",
                1, warnedSetSize());
    }

    @Test
    public void voteWithModelTypeDoesNotRecordNoFallbackDiagnostic() throws Exception {
        Method matches = FeedCardHooks.class.getDeclaredMethod(
                "matches", io.github.libxposed.api.XposedModule.class,
                Feature.class, Object.class, String.class);
        matches.setAccessible(true);

        // 有 modelType 时走类型判定，不该写「无兜底」说明。
        WithModelTypeNote note = new WithModelTypeNote("vote");
        Object result = matches.invoke(null, null, f, note, "vote");
        assertTrue("modelType=vote 应命中", (Boolean) result);
        assertEquals("有 modelType 时不应记无兜底说明", 0, warnedSetSize());
    }

    // ---------------------------------------------------------------- 工具

    /** 无 modelType、无任何兜底字段的宿主 item。 */
    public static class NoModelTypeNote {
        public String someOtherField;
    }

    /** 带 modelType 字段的宿主 item。 */
    public static class WithModelTypeNote {
        public String modelType;

        WithModelTypeNote(String modelType) {
            this.modelType = modelType;
        }
    }

    private static HookStateCore core() throws Exception {
        Field field = HookState.class.getDeclaredField("CORE");
        field.setAccessible(true);
        return (HookStateCore) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<String> warnedSet() throws Exception {
        Field field = FeedCardHooks.class.getDeclaredField("VOTE_NO_FALLBACK_WARNED");
        field.setAccessible(true);
        return (java.util.Set<String>) field.get(null);
    }

    private static void clearWarnedSet() throws Exception {
        warnedSet().clear();
    }

    private static int warnedSetSize() throws Exception {
        return warnedSet().size();
    }
}
