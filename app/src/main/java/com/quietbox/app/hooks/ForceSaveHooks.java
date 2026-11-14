package com.quietbox.app.hooks;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;
import com.quietbox.app.anchor.AnchorKeys;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * EN-04 强制允许保存。
 *
 * <p>「作者关闭下载」有三道门（9.52.0 dexsql 核实，9.53.0 真机复核）：
 * <ol>
 *   <li>旧门：{@code com.xingin.entities.MediaSaveConfig.b()} = disable_save。
 *       长按/反馈面板的保存项、分享面板、下载入口都读它（调用方包括
 *       {@code DetailFeedItemFeedbackController#t2}、{@code VideoItemFeedBackController#ub}、
 *       {@code AsyncImageGalleryController#S3} 等）。强制返回 false 即解除。</li>
 *   <li>新门（服务端灰度）：{@code com.xingin.entities.notedetail.FunctionSwitch.a()} = enable，
 *       配合 {@code c()} = type。分享面板列表构造 {@code sq9/b#a} 与下载入口
 *       {@code matrix/notedetail/feedback/download/d#invoke} 按 type 过滤。只对
 *       {@code image_download}/{@code video_download} 强制 true，避免放开无关的 function switch。</li>
 *   <li>条目门（9.53.0 真机定位）：长按/分享面板的条目模型 {@code j69.l} 自带
 *       {@code disable}（{@code d()}）与 {@code disableToast}（{@code e()}）。
 *       9.53.0 上点「保存」时前两道门一次都不命中——{@code ItemTopButtonPresenter}
 *       的 lambda 直接弹条目自带的 {@code disableToast}，下载链路一步都没进；
 *       真机取值探针实测该次 {@code disable} 为 {@code true}。这里只把下载类条目的
 *       {@code true} 翻成 {@code false}，其它条目（Dislike / Report 等）保留宿主原值。</li>
 * </ol>
 *
 * <p>三道门都只改「是否允许保存」的判定，不改保存执行链本身；水印仍由 EN-01/EN-03 负责。
 */
final class ForceSaveHooks {

    static final String TYPE_IMAGE_DOWNLOAD = "image_download";
    static final String TYPE_VIDEO_DOWNLOAD = "video_download";

    private ForceSaveHooks() {
    }

    /**
     * 纯函数：只有下载类权限才强制 enable（供 JVM 单测）。
     * 其它 type（如评论、AI 配音）保持宿主原值，避免副作用。
     */
    static boolean shouldForceEnable(String type) {
        return TYPE_IMAGE_DOWNLOAD.equals(type) || TYPE_VIDEO_DOWNLOAD.equals(type);
    }

    /**
     * 纯函数：条目是否属于「下载/保存」类（供 JVM 单测）。
     *
     * <p>条目模型的 type 取值是 {@code Download}（首字母大写），与 FunctionSwitch 的
     * {@code image_download} 命名不同，所以用大小写无关的包含匹配覆盖两种写法。
     */
    static boolean isDownloadItem(String type) {
        if (type == null) {
            return false;
        }
        return type.toLowerCase(java.util.Locale.ROOT).contains("download");
    }

    /**
     * 门 2 返回值收口：宿主 a() 声明为 boolean，调用方直接拆箱；proceed 给回
     * null/非 Boolean 时绝不能原样 return，否则宿主 {@code !functionSwitch.a()}
     * 拆箱 NPE。非 Boolean 一律 fail-open 到 false（保留宿主的原判定）。
     */
    static boolean originalOrFalse(Object original) {
        return original instanceof Boolean ? (Boolean) original : Boolean.FALSE;
    }

    /** 反射 invoke 的失败被 InvocationTargetException 包了一层，取真实原因再记日志。 */
    static Throwable unwrapInvocation(Throwable t) {
        if (t instanceof InvocationTargetException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.FORCE_SAVE;
        int installed = 0;

        // 门 1：MediaSaveConfig.b() -> false（主门，缺失即功能不可用）。
        try {
            // 2026-11-06 起 b() 已收编为方法锚点，check-mounts 会逐项校验；
            // 宿主改名时走 critical skip，不再出现「门 3 成功、门 1 静默缺失」。
            Method m = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.FORCE_SAVE_MEDIA_CONFIG, true);
            if (m != null && HookKit.install(module, f, "MediaSaveConfig#b(allow-save)", m,
                    HookKit.constBool(module, f, "MediaSaveConfig#b", false))) {
                installed++;
            }
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " skip: MediaSaveConfig " + t);
        }

        // 门 2：FunctionSwitch.a() -> true，仅 image_download / video_download。
        try {
            Method enable = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.FORCE_SAVE_FUNCTION_ENABLE, true);
            Method type = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.FORCE_SAVE_FUNCTION_TYPE, true);
            if (enable != null && type != null) {
                final Method typeGetter = type;
                boolean ok = HookKit.install(module, f, "FunctionSwitch#a(enable-download)",
                        enable, new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object original = chain.proceed();
                                try {
                                    Object self = chain.getThisObject();
                                    if (self != null) {
                                        Object typeValue = typeGetter.invoke(self);
                                        if (typeValue instanceof String
                                                && shouldForceEnable((String) typeValue)) {
                                            HookKit.hit(module, f,
                                                    "FunctionSwitch#a:" + typeValue);
                                            return Boolean.TRUE;
                                        }
                                    }
                                } catch (Throwable t) {
                                    HookKit.error(module, f.id + " FunctionSwitch#a error",
                                            unwrapInvocation(t));
                                }
                                return originalOrFalse(original);
                            }
                        });
                if (ok) {
                    installed++;
                }
            }
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " skip: FunctionSwitch " + t);
        }

        // 门 3：条目 disable -> false，仅下载/保存类条目（9.53.0 上真正拦人的一道）。
        // 只翻 true -> false：宿主给 null 表示「未表态」，那是别的判定环节的事，
        // 这里不替它做决定，保持最小改动面。
        try {
            Method disable = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.FORCE_SAVE_ITEM_DISABLE, true);
            Method type = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.FORCE_SAVE_ITEM_TYPE, true);
            if (disable != null && type != null) {
                final Method typeGetter = type;
                boolean ok = HookKit.install(module, f, "LongpressItem#d(allow-save)",
                        disable, new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object original = chain.proceed();
                                try {
                                    Object self = chain.getThisObject();
                                    if (self != null && Boolean.TRUE.equals(original)) {
                                        Object typeValue = typeGetter.invoke(self);
                                        if (typeValue instanceof String
                                                && isDownloadItem((String) typeValue)) {
                                            HookKit.hit(module, f,
                                                    "LongpressItem#d:" + typeValue);
                                            return Boolean.FALSE;
                                        }
                                    }
                                } catch (Throwable t) {
                                    HookKit.error(module, f.id + " LongpressItem#d error",
                                            unwrapInvocation(t));
                                }
                                return original;
                            }
                        });
                if (ok) {
                    installed++;
                }
            }
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " skip: LongpressItem " + t);
        }

        if (installed == 0) {
            HookState.markNotFound(f, "强制保存挂载点未找到");
        }
    }
}
