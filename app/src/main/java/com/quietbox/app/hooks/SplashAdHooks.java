package com.quietbox.app.hooks;

import android.view.LayoutInflater;
import android.view.View;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;
import com.quietbox.app.anchor.AnchorKeys;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * AD-01 开屏广告跳过。
 *
 * 开屏广告有两条互不覆盖的展示路径（2026-10-16 DexSQL 复核，内部记录）：
 *
 * Path A｜首页 Controller 内嵌 Fragment（已覆盖）
 *   AdsIndexSpiImp 的 open 系与 can 系方法全部只有一个调用方 Ljcd/t;->onAttach，
 *   是一个互斥 if/else 分发。展示入口按广告类型择一调用，必须全部拦。
 *   所有方法体首行是 Robust PatchProxy 插桩，hook 在方法入口、早于 PatchProxy。
 *
 * Path B｜独立全屏开屏 Activity（本次补上）
 *   com.xingin.advert.intersitial.ui.a（AdsActivityLifecycleCallback，由
 *   AdvertApplication.onCreate 注册到 registerActivityLifecycleCallbacks）在
 *   onActivityResumed 时把开屏广告拉起为独立全屏 Activity。四个 Activity
 *   （SplashAdActivity / SplashDynamicActivity / SplashCNYActivity /
 *   InterstitialAdsActivity）的唯一非 debug 启动方就是 ui.a#d，
 *   完全不经过 AdsIndexSpiImp，旧挂载点拦不到。
 *   - 首选挂 ui.a#d（Path B 唯一出口，void，跳过即全拦）。
 *   - 再挂四个 $a#a(Context,SplashAd) 作兜底（防 ui.a#d 被 Robust 热修绕过）。
 */
final class SplashAdHooks {

    /** Path A：首页 Controller 内嵌开屏的展示入口（全部必须拦）。 */
    private static final String[] VOID_KEYS = {
            AnchorKeys.SPLASH_OPEN_LCB_V2,          // index 页 V2 / RN 开屏
            AnchorKeys.SPLASH_OPEN_LCB_RED_V2,      // 红 / CNY 开屏
            AnchorKeys.SPLASH_OPEN_LCB_BRAND_MAX,   // 品牌 Max
            AnchorKeys.SPLASH_OPEN_BRAND_MAX,
            AnchorKeys.SPLASH_OPEN_LAZY_HF,         // 懒加载 HF 开屏
    };

    /** 类型 / UI 变体判定，不是展示总闸，置 false 只是辅助。 */
    private static final String[] DECISION_KEYS = {
            AnchorKeys.SPLASH_CAN_LAZY_OPEN,
            AnchorKeys.SPLASH_CAN_NEED_NEW_CLOSER,
    };

    /** Path B：四个全屏开屏 Activity 的 launcher（$a 内部类）。 */
    private static final String[] FULLSCREEN_KEYS = {
            AnchorKeys.SPLASH_ACTIVITY_LAUNCHER,
            AnchorKeys.SPLASH_DYNAMIC_LAUNCHER,
            AnchorKeys.SPLASH_CNY_LAUNCHER,
            AnchorKeys.SPLASH_INTERSTITIAL_LAUNCHER,
    };

    /** 与 FULLSCREEN_KEYS 一一对应的诊断 label，保持旧日志里的类名。 */
    private static final String[] FULLSCREEN_LABELS = {
            "splashActivityLauncher",
            "splashDynamicLauncher",
            "splashCnyLauncher",
            "splashInterstitialLauncher",
    };

    private SplashAdHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.SPLASH_AD_SKIP;
        try {
            installEmbedded(module, cl, f);
            installFullScreen(module, cl, f);
            installPlaceholder(module, cl, f);
            HookState.note(f, "内嵌 Fragment（AdsIndexSpiImp）+ 全屏 Activity（ui.a#d 及四个 $a#a）+ 冷启动占位（SplashAdPlaceHolderView）");
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
            HookState.note(f, "安装异常");
        }
    }

    /** Path A：首页 Controller 内嵌 Fragment 开屏。 */
    private static void installEmbedded(XposedModule module, ClassLoader cl, Feature f) {
        for (String key : VOID_KEYS) {
            Method m = HookKit.findAnchorMethod(module, f, cl, key, true);
            HookKit.install(module, f, key, m, HookKit.skipVoid(module, f, key));
        }
        for (String key : DECISION_KEYS) {
            Method m = HookKit.findAnchorMethod(module, f, cl, key, true);
            HookKit.install(module, f, key, m, HookKit.constBool(module, f, key, false));
        }
    }

    /** Path B：独立全屏开屏 Activity。 */
    private static void installFullScreen(XposedModule module, ClassLoader cl, Feature f) {
        // ui.a#d 是 Path B 唯一出口，签名中的 SplashAd 由 anchors.json 精确描述。
        Method d = HookKit.findAnchorMethod(module, f, cl, AnchorKeys.SPLASH_FULLSCREEN_ENTRY, true);
        HookKit.install(module, f, "splashFullscreenEntry", d, HookKit.skipVoid(module, f, "splashFullscreenEntry"));

        for (int i = 0; i < FULLSCREEN_KEYS.length; i++) {
            Method a = HookKit.findAnchorMethod(module, f, cl, FULLSCREEN_KEYS[i], false);
            if (a == null) {
                continue;
            }
            String label = FULLSCREEN_LABELS[i];
            HookKit.install(module, f, label, a, HookKit.skipVoid(module, f, label));
        }
    }

    /**
     * Path C：冷启动占位 Fragment。
     *
     * 不 hook 工厂（createSplashAdPlaceHolderView）的原因是它要返回
     * androidx.fragment.app.Fragment 实例，而实测在目标进程里
     * Class.forName("androidx.fragment.app.Fragment", false, cl) 拿不到（XHS 的类加载器
     * 对 androidx 走的是另一层），所以改成 hook 占位 Fragment 自身的 onCreateView，
     * 直接返回一个空 View。类名是非混淆的语义名。
     */
    static void installPlaceholder(XposedModule module, ClassLoader cl, Feature f) {
        // F106：接入 findAnchorMethod(critical=true) 的记账通道，锚点缺失时诊断页可见。
        Method m = HookKit.findAnchorMethod(module, f, cl, AnchorKeys.SPLASH_PLACEHOLDER_VIEW, true);
        if (m == null) {
            return;
        }
        final Class<?> anchorClass = m.getDeclaringClass();
        HookKit.install(module, f, "splashPlaceholderView", m,
                new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        return interceptPlaceholder(chain, module, f, anchorClass);
                    }
                });
    }

    /**
     * 冷启动占位拦截（F106）：只有锚点类自身声明的 onCreateView 才返回空 View；
     * 继承/同名方法族（其它 Fragment 的 onCreateView）一律放行原逻辑，避免把
     * 整个冷启动占位误吞成空白页。
     *
     * <p>包级可见以便纯 JVM 单测直接驱动判定，不必构造真实 View/LayoutInflater。
     */
    static Object interceptPlaceholder(XposedInterface.Chain chain, XposedModule module,
                                       Feature f, Class<?> anchorClass) throws Throwable {
        java.lang.reflect.Executable executable = chain.getExecutable();
        if (executable == null || anchorClass == null
                || !anchorClass.equals(executable.getDeclaringClass())) {
            return chain.proceed();
        }
        try {
            Object inflater = chain.getArg(0);
            if (inflater instanceof LayoutInflater) {
                HookKit.hit(module, f, "placeholder suppressed");
                return new View(((LayoutInflater) inflater).getContext());
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " intercept error placeholder", t);
        }
        return chain.proceed();
    }
}
