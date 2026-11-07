package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * LivePhotoWatermarkHooks 纯函数的 JVM 单测。
 *
 * dexsql 对 9.52.1 的结论（见 LivePhotoWatermarkHooks 类注释）：
 * ss9.j#c(ny9.m, cl9.i, Z)V 是实况保存入口；cl9.i 的字段 e=disableWaterMark、
 * h=disableAiWaterMark（公开 boolean 字段）；wk2.h#a(I)Z 的 code=1 是实况保存
 * 链路开关（ss9.j#c 内 const 1 传入），true=新链路（服务端渲染，可能带烧录水印）、
 * false=旧链路（直接下载 cl9.i.g 预览同源 URL）。9.52.0 对应 zm7.j / dg7.h / p32.h。
 */
public class LivePhotoWatermarkHooksTest {

    /** 字段形状与 9.52.1 cl9.i 相同的替身：a~d=String、e=Z、f=String、g=String、h=Z。 */
    static class Feedback {
        public String a = "a";
        public String b = "b";
        public String c = "c";
        public String d = "d";
        public boolean e = false;
        public String f = "fileId";
        public String g = "videoPath";
        public boolean h = false;
    }

    @Test
    public void feedback_setsDisableWaterMarkAndAiWaterMark() {
        Feedback fb = new Feedback();
        assertEquals(2, LivePhotoWatermarkHooks.applyFeedbackFlags(fb));
        assertTrue("e (disableWaterMark) 必须置 true", fb.e);
        assertTrue("h (disableAiWaterMark) 必须置 true", fb.h);
    }

    @Test
    public void feedback_overwritesTrueInputs() {
        Feedback fb = new Feedback();
        fb.e = true;
        fb.h = true;
        assertEquals(2, LivePhotoWatermarkHooks.applyFeedbackFlags(fb));
        assertTrue(fb.e);
        assertTrue(fb.h);
    }

    @Test
    public void feedback_keepsStringFieldsUntouched() {
        Feedback fb = new Feedback();
        LivePhotoWatermarkHooks.applyFeedbackFlags(fb);
        // f=livePhotoFileId、g=livePhotoVideoPath 与 a~d 原样保留。
        assertEquals("fileId", fb.f);
        assertEquals("videoPath", fb.g);
        assertEquals("a", fb.a);
        assertEquals("d", fb.d);
    }

    @Test
    public void nullTarget_returnsMinusOneAndDoesNotThrow() {
        assertEquals(-1, LivePhotoWatermarkHooks.applyFeedbackFlags(null));
    }

    @Test
    public void targetWithoutFields_returnsZeroAndDoesNotThrow() {
        // 没有 e/h 字段的对象（例如混淆换名后的宿主类）不置位、不抛出。
        assertEquals(0, LivePhotoWatermarkHooks.applyFeedbackFlags(new Object()));
        assertEquals(0, LivePhotoWatermarkHooks.applyFeedbackFlags("string"));
    }

    @Test
    public void switchCode_onlyCodeOneMatches() {
        // 只对目标 code 生效：1 命中，其余（含 0、2、负数）不干预。
        assertTrue(LivePhotoWatermarkHooks.isLivePhotoSwitchCode(1));
        assertFalse(LivePhotoWatermarkHooks.isLivePhotoSwitchCode(0));
        assertFalse(LivePhotoWatermarkHooks.isLivePhotoSwitchCode(2));
        assertFalse(LivePhotoWatermarkHooks.isLivePhotoSwitchCode(-1));
        assertFalse(LivePhotoWatermarkHooks.isLivePhotoSwitchCode(Integer.MIN_VALUE));
    }

    @Test
    public void switchConstantMatchesDexsqlFinding() {
        // dexsql 反汇编 zm7.j#c：const v5, 0x1 传入 p32.h#a(I)Z。code 改动必须显式过测试。
        assertEquals(1, LivePhotoWatermarkHooks.LIVE_PHOTO_SAVE_SWITCH_CODE);
    }

    /** 防止重构成实例方法/改可见性时测试静默失效。 */
    @Test
    public void helpersArePackagePrivateStatic() throws Exception {
        Method flags = LivePhotoWatermarkHooks.class
                .getDeclaredMethod("applyFeedbackFlags", Object.class);
        int mods = flags.getModifiers();
        assertTrue(Modifier.isStatic(mods));
        assertTrue("应保持 package-private，供同包测试直接调用",
                (mods & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE)) == 0);

        Method code = LivePhotoWatermarkHooks.class
                .getDeclaredMethod("isLivePhotoSwitchCode", int.class);
        int codeMods = code.getModifiers();
        assertTrue(Modifier.isStatic(codeMods));
        assertTrue((codeMods & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE)) == 0);
    }

    // -------------------------------------------------- 失效不能报成命中（F-051）

    private static final class ChainFake {
        final List<Object> args;
        final AtomicInteger proceedCalls = new AtomicInteger();

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
    public void entryHooker_doesNotReportHitWhenFieldsMissing() throws Throwable {
        Feature f = Feature.LIVE_PHOTO_WATERMARK;
        long before = HookState.hitCount(f);
        ChainFake chain = new ChainFake("listener", new Object());

        Object result = LivePhotoWatermarkHooks.entryHooker(newModule(), f)
                .intercept(chain.proxy());

        assertEquals("字段改名/缺失时 set=0，功能未生效不能报命中",
                before, HookState.hitCount(f));
        assertEquals("原参 proceed 一次", 1, chain.proceedCalls.get());
        assertEquals("proceeded", result);
    }

    @Test
    public void entryHooker_reportsHitOnlyWhenFlagsSet() throws Throwable {
        Feature f = Feature.LIVE_PHOTO_WATERMARK;
        long before = HookState.hitCount(f);
        ChainFake chain = new ChainFake("listener", new Feedback());

        LivePhotoWatermarkHooks.entryHooker(newModule(), f).intercept(chain.proxy());

        assertEquals("两个字段都置位后记一次命中", before + 1, HookState.hitCount(f));
        assertEquals(1, chain.proceedCalls.get());
    }
}
