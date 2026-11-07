package com.quietbox.app.hooks;

import com.quietbox.app.Feature;
import com.quietbox.app.anchor.AnchorKeys;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * PV-02 通讯录同步上传关闭。
 *
 * 通讯录上传是可选功能，不上传就不会有通讯录推荐/好友发现。挂两层：
 * <ul>
 *   <li>SPI 层（UploadContactServiceExtension）：start / 2 参 requestContactsSync /
 *       installLifecycleObserver 都是无回调契约的 void，直接 skip；带 z2.b/a3.b
 *       回调的 3 参 requestContactsSync 先调 onCompleted 再跳过业务体，同步仍然
 *       不执行，但回调链不断（见 completeThenSkip）。</li>
 *   <li>协调器层（ContactsSyncCoordinator，F-039）：宿主 SplashGrowthApplication
 *       冷启动不经过 SPI，直接调 e(Application)（onCreate$lambda$1$lambda$0）与
 *       i(Context, DAILY_LAUNCH, null, 4, null)（updateContact）；旧构建注册
 *       （SIGN_UP）也直连 i。9.53.0 已确认 SPI start 亦委托 i（spi_start_legacy）、
 *       SPI installLifecycleObserver 亦委托 e（delegate_to_coordinator），
 *       所以 i/e 是全部入口的收口：e 无回调直接 skip；i 的第 3 参 Function0 是
 *       完成续体，宿主自身 config_disabled 时也会调用它，故先调 invoke() 再跳过
 *       业务体（见 completeThenRunFunction0）。</li>
 * </ul>
 * 不碰登录与安全链路。
 */
final class ContactSyncHooks {

    private ContactSyncHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.CONTACT_SYNC_OFF;
        try {
            HookKit.install(module, f, "start",
                    HookKit.findAnchorMethod(module, f, cl,
                            AnchorKeys.CONTACT_SYNC_START, true),
                    HookKit.skipVoid(module, f, "start"));
            Method request = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.CONTACT_SYNC_REQUEST, true);
            HookKit.install(module, f, "requestContactsSync", request,
                    HookKit.skipVoid(module, f, "requestContactsSync"));
            // DEX 已确认的同名 3 参重载：requestContactsSync(Context, String, z2.b)。
            // 带 onCompleted 回调契约，不能整体 skipVoid（回调会随方法体一起消失）。
            // 64 项清单里该行写作 "2,3"，锚点钉住 2 参主入口；3 参重载按同类的
            // 名字+参数个数解析，作为附加挂载（不单独占一个锚点 key）。
            Class<?> cls = request == null ? null : request.getDeclaringClass();
            HookKit.install(module, f, "requestContactsSync#3",
                    HookKit.findByNameAndArity(module, f, cls, "requestContactsSync", 3),
                    completeThenSkip(module, f, "requestContactsSync#3"));
            HookKit.install(module, f, "installLifecycleObserver",
                    HookKit.findAnchorMethod(module, f, cl,
                            AnchorKeys.CONTACT_SYNC_LIFECYCLE, true),
                    HookKit.skipVoid(module, f, "installLifecycleObserver"));
            // F-039：协调器层收口。i/e 是 9.53.0 已确认的冷启动直连入口，签名钉在
            // anchors.json（contact_sync_coord_i/e）；SPI 两处委托路径随之一起被盖住。
            HookKit.install(module, f, "coordinator.i",
                    HookKit.findAnchorMethod(module, f, cl,
                            AnchorKeys.CONTACT_SYNC_COORD_I, true),
                    completeThenRunFunction0(module, f, "coordinator.i"));
            HookKit.install(module, f, "coordinator.e",
                    HookKit.findAnchorMethod(module, f, cl,
                            AnchorKeys.CONTACT_SYNC_COORD_E, true),
                    HookKit.skipVoid(module, f, "coordinator.e"));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    /**
     * 3 参 requestContactsSync 的原方法体在触发未知与同步结束两处都会回调
     * z2.b.onCompleted()（9.50.0 UploadContactServiceExtension.java:103-116）。
     * 整体 skipVoid 会让回调链断掉，依赖 onCompleted 收尾的调用方收不到完成通知。
     * 这里改为先调用回调再跳过业务体：同步上传仍然不执行（PV-02 的核心），
     * 回调契约保留。回调是混淆接口（z2.b），编译期拿不到 Class 字面量，
     * 按名字 + 0 参反射调用；回调失败只记日志，业务体依旧跳过
     * （异常时放行等于恢复上传，不可接受）。
     */
    static XposedInterface.Hooker completeThenSkip(XposedModule module, Feature f, String detail) {
        return completeThenInvoke(module, f, detail, "onCompleted");
    }

    /**
     * 协调器 i(Context, scene, Function0, mask, marker) 的跳过器（F-039）。
     *
     * <p>第 3 参 Function0 是同步流程结束后的完成续体：宿主自身在 config_disabled
     * 时也是经 g(Function0) 调用它再返回（9.53.0 j:53-73 fallback→label_01a8），
     * 所以「先 invoke() 再跳过业务体」与宿主自己的关闭态行为完全一致。已核实的
     * 直连入口（冷启动 DAILY_LAUNCH、注册 SIGN_UP）都传 null，此时纯跳过。
     * 回调失败只记日志，业务体依旧跳过（异常时放行等于恢复上传，不可接受）。
     */
    static XposedInterface.Hooker completeThenRunFunction0(XposedModule module, Feature f,
                                                           String detail) {
        return completeThenInvoke(module, f, detail, "invoke");
    }

    /**
     * 公共骨架：先反射调用第 3 参回调上的 0 参方法 callbackMethod（存在才调），
     * 再跳过业务体。intercept 体整段 try/catch(Throwable)，任何路径都不 proceed
     * ——proceed 意味着通讯录同步照常执行。
     */
    private static XposedInterface.Hooker completeThenInvoke(XposedModule module, Feature f,
                                                             String detail,
                                                             String callbackMethod) {
        return chain -> {
            try {
                Object callback = chain.getArg(2);
                if (callback != null) {
                    try {
                        invokeZeroArg(callback, callbackMethod);
                    } catch (Throwable t) {
                        HookKit.error(module, f.id + " " + callbackMethod
                                + " invoke failed " + detail, t);
                    }
                }
                HookKit.hit(module, f, detail);
                return null;
            } catch (Throwable t) {
                HookKit.error(module, f.id + " intercept error " + detail, t);
                return null;
            }
        };
    }

    /** 在回调对象上找 0 参方法 methodName（类层级与接口都查），找到即调用；找不到抛 NoSuchMethodException。 */
    private static void invokeZeroArg(Object callback, String methodName) throws Exception {
        Method m = findZeroArgMethod(callback.getClass(), methodName);
        if (m == null) {
            throw new NoSuchMethodException(
                    methodName + "() not found on " + callback.getClass().getName());
        }
        m.setAccessible(true);
        m.invoke(callback);
    }

    private static Method findZeroArgMethod(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name);
            } catch (Throwable ignored) {
                // 本层没有，继续查接口与父类。
            }
            for (Class<?> ifc : c.getInterfaces()) {
                Method m = findZeroArgMethod(ifc, name);
                if (m != null) {
                    return m;
                }
            }
        }
        return null;
    }
}
