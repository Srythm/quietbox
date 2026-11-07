package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.quietbox.app.Feature;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * ContactSyncHooks 回调保持式跳过器的 JVM 单测：
 *
 * completeThenSkip（发现 #60 回归）：3 参 requestContactsSync(Context, String, z2.b)
 * 带完成回调契约（原方法体在触发未知与同步结束两处都会回调 onCompleted，见
 * 9.50.0 UploadContactServiceExtension.java:103-116）。旧实现整体 skipVoid，
 * 回调随方法体一起消失。新实现必须：
 * <ul>
 *   <li>先调用回调（onCompleted 恰好一次），再跳过业务体——返回 null 且从不 proceed；</li>
 *   <li>回调缺失（null）与回调抛错都不放行业务体（放行等于恢复通讯录上传）；</li>
 *   <li>反射在实现类 / 接口上都能找到 onCompleted()。</li>
 * </ul>
 *
 * completeThenRunFunction0（F-039 回归）：协调器
 * i(Context, scene, Function0, mask, marker) 的第 3 参 Function0 是完成续体，
 * 宿主自身 config_disabled 时也会调用它（9.53.0 j fallback→g(Function0)）。
 * 同样的契约：先 invoke() 恰好一次再跳过业务体；null / 抛错都不得放行。
 *
 * libxposed 的 XposedModule 是抽象壳：用 Proxy 实现 XposedInterface 后 attachFramework，
 * 即可在 JVM 单测里跑真实 Hooker（与 HookDiagnosisTest 同构）。
 *
 * HookState 是进程级静态状态：PV-02 被 HookStateTest.snapshotAggregatesFeatureLevelState
 * 声明为「未被其他用例触碰」（精确增量断言依赖），因此本文件用 blocked Feature
 * VIDEO_DEFAULT_MUTE 做替身（FeatureTest 只断言其 blocked 位，不写 HookState）。
 */
public class ContactSyncHooksTest {

    /** z2.b 的替身：公开 onCompleted()，实现可直接落在实现类上。 */
    static class CompletedOnSelf {
        int completed;

        public void onCompleted() {
            completed++;
        }
    }

    /** onCompleted 只声明在接口上的替身（对应宿主里回调可能由接口提供）。 */
    interface OnCompletedInterface {
        void onCompleted();
    }

    static class CompletedOnInterface implements OnCompletedInterface {
        int completed;

        @Override
        public void onCompleted() {
            completed++;
        }
    }

    /** onCompleted 抛错的替身：回调失败也不得放行业务体。 */
    static class ThrowingCallback {
        public void onCompleted() {
            throw new IllegalStateException("callback boom");
        }
    }

    /** kotlin Function0 的替身：invoke() 落在实现类上（Kotlin lambda 编译后的形状）。 */
    static class Function0OnSelf {
        int invoked;

        public Object invoke() {
            invoked++;
            return this;
        }
    }

    /** invoke() 只声明在接口上的替身。 */
    interface InvokeInterface {
        Object invoke();
    }

    static class Function0OnInterface implements InvokeInterface {
        int invoked;

        @Override
        public Object invoke() {
            invoked++;
            return this;
        }
    }

    /** invoke() 抛错的替身：续体失败也不得放行业务体。 */
    static class ThrowingFunction0 {
        public Object invoke() {
            throw new IllegalStateException("continuation boom");
        }
    }

    /** Chain 替身：记录 proceed 调用次数（业务体是否被执行）。 */
    private static final class ChainFake {
        final List<Object> args;
        final AtomicInteger proceedCalls = new AtomicInteger();

        ChainFake(Object... args) {
            this.args = Arrays.asList(args);
        }

        XposedInterface.Chain proxy() {
            return (XposedInterface.Chain) Proxy.newProxyInstance(
                    XposedInterface.class.getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class},
                    (proxy, method, methodArgs) -> {
                        switch (method.getName()) {
                            case "getArg":
                                return args.get((Integer) methodArgs[0]);
                            case "getArgs":
                                return args;
                            case "proceed":
                            case "proceedWith":
                                proceedCalls.incrementAndGet();
                                return "proceeded";
                            default:
                                return null;
                        }
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
    public void invokesCallbackOnceThenSkipsBody() throws Throwable {
        XposedModule module = newModule();
        CompletedOnSelf callback = new CompletedOnSelf();
        ChainFake chain = new ChainFake(new Object(), "trigger", callback);

        XposedInterface.Hooker hooker =
                ContactSyncHooks.completeThenSkip(module, Feature.VIDEO_DEFAULT_MUTE, "requestContactsSync#3");

        Object result = hooker.intercept(chain.proxy());

        assertNull("跳过业务体必须返回 null（void 方法）", result);
        assertEquals("onCompleted 必须恰好回调一次", 1, callback.completed);
        assertEquals("业务体不得执行（proceed 即恢复上传）", 0, chain.proceedCalls.get());
    }

    @Test
    public void findsOnCompletedDeclaredOnInterface() throws Throwable {
        XposedModule module = newModule();
        CompletedOnInterface callback = new CompletedOnInterface();
        ChainFake chain = new ChainFake(new Object(), "trigger", callback);

        XposedInterface.Hooker hooker =
                ContactSyncHooks.completeThenSkip(module, Feature.VIDEO_DEFAULT_MUTE, "requestContactsSync#3");

        hooker.intercept(chain.proxy());

        assertEquals("接口上的 onCompleted 也必须被调到", 1, callback.completed);
        assertEquals(0, chain.proceedCalls.get());
    }

    @Test
    public void nullCallbackStillSkipsBody() throws Throwable {
        XposedModule module = newModule();
        ChainFake chain = new ChainFake(new Object(), "trigger", null);

        XposedInterface.Hooker hooker =
                ContactSyncHooks.completeThenSkip(module, Feature.VIDEO_DEFAULT_MUTE, "requestContactsSync#3");

        Object result = hooker.intercept(chain.proxy());

        assertNull(result);
        assertEquals("无回调时同样跳过业务体", 0, chain.proceedCalls.get());
    }

    @Test
    public void throwingCallbackIsLoggedButBodyStillSkipped() throws Throwable {
        XposedModule module = newModule();
        ChainFake chain = new ChainFake(new Object(), "trigger", new ThrowingCallback());

        XposedInterface.Hooker hooker =
                ContactSyncHooks.completeThenSkip(module, Feature.VIDEO_DEFAULT_MUTE, "requestContactsSync#3");

        Object result = hooker.intercept(chain.proxy());

        assertNull("回调抛错不得向外传播", result);
        assertEquals("回调抛错也不得放行业务体", 0, chain.proceedCalls.get());
    }

    @Test
    public void missingThirdArgStillSkipsBody() throws Throwable {
        XposedModule module = newModule();
        // 宿主若以少于 3 参调用（防御场景），getArg(2) 抛界外也必须吞掉并跳过业务体。
        ChainFake chain = new ChainFake(new Object(), "trigger");

        XposedInterface.Hooker hooker =
                ContactSyncHooks.completeThenSkip(module, Feature.VIDEO_DEFAULT_MUTE, "requestContactsSync#3");

        Object result = hooker.intercept(chain.proxy());

        assertNull(result);
        assertEquals(0, chain.proceedCalls.get());
    }

    // ---- F-039：协调器 i(Context, scene, Function0, mask, marker) 的续体保持 ----

    @Test
    public void invokesFunction0OnceThenSkipsBody() throws Throwable {
        XposedModule module = newModule();
        Function0OnSelf continuation = new Function0OnSelf();
        // 协调器 i 的第 3 参（index 2）是 Function0 续体；冷启动 DAILY_LAUNCH 传 null，
        // 这里锁非 null 契约：先 invoke() 恰好一次，再跳过业务体。
        ChainFake chain = new ChainFake(new Object(), "scene", continuation, 4, null);

        XposedInterface.Hooker hooker =
                ContactSyncHooks.completeThenRunFunction0(module, Feature.VIDEO_DEFAULT_MUTE, "coordinator.i");

        Object result = hooker.intercept(chain.proxy());

        assertNull("跳过业务体必须返回 null（void 方法）", result);
        assertEquals("invoke() 必须恰好调用一次（宿主 config_disabled 同款行为）", 1, continuation.invoked);
        assertEquals("业务体不得执行（proceed 即恢复通讯录同步）", 0, chain.proceedCalls.get());
    }

    @Test
    public void findsInvokeDeclaredOnInterface() throws Throwable {
        XposedModule module = newModule();
        Function0OnInterface continuation = new Function0OnInterface();
        ChainFake chain = new ChainFake(new Object(), "scene", continuation, 4, null);

        XposedInterface.Hooker hooker =
                ContactSyncHooks.completeThenRunFunction0(module, Feature.VIDEO_DEFAULT_MUTE, "coordinator.i");

        hooker.intercept(chain.proxy());

        assertEquals("接口上的 invoke() 也必须被调到", 1, continuation.invoked);
        assertEquals(0, chain.proceedCalls.get());
    }

    @Test
    public void nullFunction0StillSkipsBody() throws Throwable {
        XposedModule module = newModule();
        // 冷启动 DAILY_LAUNCH / 注册 SIGN_UP 直连时续体为 null（mask 0x4）：纯跳过。
        ChainFake chain = new ChainFake(new Object(), "scene", null, 4, null);

        XposedInterface.Hooker hooker =
                ContactSyncHooks.completeThenRunFunction0(module, Feature.VIDEO_DEFAULT_MUTE, "coordinator.i");

        Object result = hooker.intercept(chain.proxy());

        assertNull(result);
        assertEquals(0, chain.proceedCalls.get());
    }

    @Test
    public void throwingFunction0StillSkipsBody() throws Throwable {
        XposedModule module = newModule();
        ChainFake chain = new ChainFake(new Object(), "scene", new ThrowingFunction0(), 4, null);

        XposedInterface.Hooker hooker =
                ContactSyncHooks.completeThenRunFunction0(module, Feature.VIDEO_DEFAULT_MUTE, "coordinator.i");

        Object result = hooker.intercept(chain.proxy());

        assertNull("续体抛错不得向外传播", result);
        assertEquals("续体抛错也不得放行业务体", 0, chain.proceedCalls.get());
    }
}
