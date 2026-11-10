package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import org.junit.Test;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.yigenhuobah.core.test.FakeChain;
import io.github.yigenhuobah.core.test.FakeXposedInterface;

/**
 * F106 回归：冷启动占位拦截只对锚点类自身的 onCreateView 生效。
 *
 * <p>旧实现只判 {@code inflater instanceof LayoutInflater}，继承/同名方法族
 * （其它 Fragment 的 onCreateView）也会被吞成空 View。这里直接驱动提取出来的
 * {@link SplashAdHooks#interceptPlaceholder} 判定，断言声明类不匹配时走
 * {@code chain.proceed()}；同时锁定锚点缺失时按 critical 记账。
 */
public class SplashAdPlaceholderGuardTest {

    /** 锚点类夹具：只有它声明的方法才应被抑制。 */
    static final class PlaceholderFragment {
        void onCreateView() {
        }
    }

    /** 继承/同名方法族夹具：不得被抑制。 */
    static final class OtherFragment {
        void onCreateView() {
        }
    }

    @Test
    public void foreignDeclaringClassIsNotSuppressed() throws Throwable {
        Method foreign = OtherFragment.class.getDeclaredMethod("onCreateView");
        FakeChain chain = new FakeChain(foreign, null, (Object) null);
        chain.returnValue("original");

        Object out = SplashAdHooks.interceptPlaceholder(chain, module(), Feature.SPLASH_AD_SKIP,
                PlaceholderFragment.class);

        assertEquals("其它 Fragment 的 onCreateView 必须放行原逻辑", "original", out);
        assertEquals("必须只 proceed 一次", 1, chain.proceedCount());
    }

    @Test
    public void nullExecutableIsNotSuppressed() throws Throwable {
        FakeChain chain = new FakeChain();
        chain.returnValue("original");

        Object out = SplashAdHooks.interceptPlaceholder(chain, module(), Feature.SPLASH_AD_SKIP,
                PlaceholderFragment.class);

        assertEquals("拿不到可执行对象时必须放行原逻辑", "original", out);
        assertEquals(1, chain.proceedCount());
    }

    @Test
    public void anchorClassCallWithoutInflaterStillProceeds() throws Throwable {
        Method anchor = PlaceholderFragment.class.getDeclaredMethod("onCreateView");
        FakeChain chain = new FakeChain(anchor, null, "not-a-layout-inflater");
        chain.returnValue("original");

        Object out = SplashAdHooks.interceptPlaceholder(chain, module(), Feature.SPLASH_AD_SKIP,
                PlaceholderFragment.class);

        assertEquals("首参不是 LayoutInflater 时不得抑制", "original", out);
        assertEquals(1, chain.proceedCount());
    }

    @Test
    public void missingPlaceholderAnchorIsRecordedAsCritical() {
        Feature f = Feature.SPLASH_AD_SKIP;
        HookState.clearFailures(f);

        // 一个加载不了宿主类的 ClassLoader：splash_placeholder_view 解析失败。
        SplashAdHooks.installPlaceholder(module(), new ClassLoader(null) {
        }, f);

        assertTrue("占位锚点缺失必须按 critical 记账，诊断页/失败横幅才可见",
                HookState.hasCriticalSkip(f));
        String reason = HookState.skipReason(f);
        assertTrue("skip 原因必须指出 splash_placeholder_view: " + reason,
                reason != null && reason.contains("splash_placeholder_view"));
    }

    private static XposedModule module() {
        return new FakeXposedInterface().createAttachedModule();
    }
}
