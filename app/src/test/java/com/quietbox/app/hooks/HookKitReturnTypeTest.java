package com.quietbox.app.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * F102：install() 在 module.hook 之前校验目标返回类型与固定返回形态的 hooker 是否相容，
 * 避免宿主版本漂移后把「返回 null/常量」的 hooker 装到 primitive 方法上，在宿主方法体内
 * 拆箱崩溃（报错点在宿主调用栈，PROTECTIVE 未必兜住）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@code skipVoid} 只接受 void 目标；{@code constBool} 只接受 boolean/Boolean；</li>
 *   <li>proceed 型匿名 hooker 不受返回类型限制（既有安装不受影响）；</li>
 *   <li>install 对不匹配的固定返回 hooker 在 module.hook 之前拒绝并记 skip；</li>
 *   <li>名字+参数个数入口（ContactSyncHooks#3 使用）拒绝 primitive 返回，
 *       但放行 void 与引用返回（null-return 对引用类型合法）。</li>
 * </ul>
 */
public class HookKitReturnTypeTest {

    private static final Feature FEATURE = Feature.VIDEO_DEFAULT_MUTE;

    /** 各返回形态的目标方法替身。 */
    public static class Fixture {
        public void voidMethod() {
        }

        public boolean primitiveBool() {
            return true;
        }

        public Boolean boxedBool() {
            return Boolean.TRUE;
        }

        public int primitiveInt() {
            return 1;
        }

        public String reference() {
            return "";
        }

        public void requestContactsSync(Object a, String b, Object c) {
        }

        public int requestContactsSyncPrimitive(Object a, String b, Object c) {
            return 0;
        }

        public Object requestContactsSyncReference(Object a, String b, Object c) {
            return null;
        }
    }

    @Before
    public void reset() {
        HookState.clearFailures(FEATURE);
    }

    @After
    public void cleanup() {
        HookState.clearFailures(FEATURE);
    }

    private static Method method(String name, Class<?>... params) throws Exception {
        return Fixture.class.getMethod(name, params);
    }

    private static XposedModule newModule() {
        XposedInterface base = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class},
                (proxy, m, args) -> null);
        XposedModule module = new XposedModule() {
        };
        module.attachFramework(base, () -> {
        });
        return module;
    }

    @Test
    public void skipVoidOnlyAcceptsVoidReturns() throws Exception {
        XposedModule module = newModule();
        XposedInterface.Hooker skip = HookKit.skipVoid(module, FEATURE, "probe");

        assertTrue("skipVoid 装到 void 方法上应通过",
                HookKit.returnTypeMatches(method("voidMethod"), skip));
        assertFalse("skipVoid 不得装到 boolean 方法",
                HookKit.returnTypeMatches(method("primitiveBool"), skip));
        assertFalse("skipVoid 不得装到 int 方法",
                HookKit.returnTypeMatches(method("primitiveInt"), skip));
        assertFalse("skipVoid 不得装到引用返回方法（会丢弃宿主返回值）",
                HookKit.returnTypeMatches(method("reference"), skip));
    }

    @Test
    public void constBoolOnlyAcceptsBooleanReturns() throws Exception {
        XposedModule module = newModule();
        XposedInterface.Hooker bool = HookKit.constBool(module, FEATURE, "probe", false);

        assertTrue("constBool 装到 boolean 应通过",
                HookKit.returnTypeMatches(method("primitiveBool"), bool));
        assertTrue("constBool 装到 Boolean 应通过",
                HookKit.returnTypeMatches(method("boxedBool"), bool));
        assertFalse("constBool 不得装到 void 方法",
                HookKit.returnTypeMatches(method("voidMethod"), bool));
        assertFalse("constBool 不得装到 int 方法",
                HookKit.returnTypeMatches(method("primitiveInt"), bool));
    }

    @Test
    public void proceedHookersAreUnconstrained() throws Exception {
        XposedInterface.Hooker proceed = new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                return chain.proceed();
            }
        };

        assertTrue("普通 proceed hooker 不受返回类型限制（int）",
                HookKit.returnTypeMatches(method("primitiveInt"), proceed));
        assertTrue("普通 proceed hooker 不受返回类型限制（void）",
                HookKit.returnTypeMatches(method("voidMethod"), proceed));
    }

    @Test
    public void installRejectsMismatchedFixedReturnHookerBeforeHook() throws Exception {
        XposedModule module = newModule();

        boolean installed = HookKit.install(module, FEATURE, "probe-int",
                method("primitiveInt"), HookKit.constBool(module, FEATURE, "probe-int", false),
                XposedInterface.ExceptionMode.PROTECTIVE);

        assertFalse("constBool 装到 int 方法必须被拒绝", installed);
        assertTrue("必须留下返回类型不匹配的 skip：" + HookState.skipReasons(FEATURE),
                HookState.skipReasons(FEATURE).stream()
                        .anyMatch(r -> r.contains("返回类型不匹配") && r.contains("probe-int")));
        assertFalse("必须在 module.hook 之前拒绝，不能走到 hook 失败分支："
                        + HookState.skipReasons(FEATURE),
                HookState.skipReasons(FEATURE).stream().anyMatch(r -> r.contains("hook 失败")));
    }

    @Test
    public void installAcceptsMatchingFixedReturnHooker() throws Exception {
        XposedModule module = newModule();

        // 只关注「没有被返回类型校验拦下」；单测 module.hook 为空实现，安装结果可能为 false。
        HookKit.install(module, FEATURE, "probe-void",
                method("voidMethod"), HookKit.skipVoid(module, FEATURE, "probe-void"),
                XposedInterface.ExceptionMode.PROTECTIVE);

        assertFalse("匹配的返回类型不得被返回类型校验拦下：" + HookState.skipReasons(FEATURE),
                HookState.skipReasons(FEATURE).stream()
                        .anyMatch(r -> r.contains("返回类型不匹配")));
    }

    @Test
    public void nameOnlyLookupRejectsPrimitiveReturnButAllowsVoidAndReference() throws Exception {
        XposedModule module = newModule();

        Method primitive = HookKit.findByNameAndArity(module, FEATURE, Fixture.class,
                "requestContactsSyncPrimitive", 3);
        assertNull("名字+参数个数入口不得返回 primitive 方法（null-return hooker 会拆箱崩溃）",
                primitive);
        assertTrue("primitive 返回必须记返回类型不匹配：" + HookState.skipReasons(FEATURE),
                HookState.skipReasons(FEATURE).stream()
                        .anyMatch(r -> r.contains("返回类型不匹配")
                                && r.contains("requestContactsSyncPrimitive")));

        HookState.clearFailures(FEATURE);
        assertNotNull("void 方法仍应放行",
                HookKit.findByNameAndArity(module, FEATURE, Fixture.class,
                        "requestContactsSync", 3));
        assertNotNull("引用返回方法仍应放行（null-return 对引用类型合法）",
                HookKit.findByNameAndArity(module, FEATURE, Fixture.class,
                        "requestContactsSyncReference", 3));
    }

    @Test
    public void helperHookersRemainAnonymousNonSyntheticClasses() {
        XposedModule module = newModule();
        XposedInterface.Hooker skip = HookKit.skipVoid(module, FEATURE, "probe");
        XposedInterface.Hooker bool = HookKit.constBool(module, FEATURE, "probe", true);

        assertFalse("skipVoid 不得返回 lambda（合成类）", skip.getClass().isSynthetic());
        assertFalse("constBool 不得返回 lambda（合成类）", bool.getClass().isSynthetic());
        assertTrue("skipVoid 必须是 HookKit 内的匿名内部类",
                skip.getClass().getName().startsWith(HookKit.class.getName() + "$"));
        assertTrue("constBool 必须是 HookKit 内的匿名内部类",
                bool.getClass().getName().startsWith(HookKit.class.getName() + "$"));
        assertTrue("skipVoid 必须带 void 返回标记", skip instanceof HookKit.VoidReturnHooker);
        assertTrue("constBool 必须带 boolean 返回标记", bool instanceof HookKit.BoolReturnHooker);
    }
}
