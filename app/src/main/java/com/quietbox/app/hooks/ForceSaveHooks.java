package com.quietbox.app.hooks;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;
import com.quietbox.app.anchor.AnchorKeys;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * EN-04 强制允许保存。
 *
 * <p>「作者关闭下载」有六道门（9.52.0 dexsql 核实，9.53.0 真机复核）：
 * <ol>
 *   <li>旧门：{@code com.xingin.entities.MediaSaveConfig.b()} = disable_save。
 *       强制返回 false 即解除。</li>
 *   <li>新模型门（9.53.0）：{@code com.xingin.arch.model.note.config.MediaSaveConfig#getDisableSave()}。
 *       旧实体只是 {@code xq6.i} 从新模型拷贝构造出的兼容结构（{@code <init>(ZZZ)V}）。</li>
 *   <li>服务端灰度门：{@code com.xingin.entities.notedetail.FunctionSwitch.a()} = enable，
 *       配合 {@code c()} = type。只对 {@code image_download}/{@code video_download} 强制 true。</li>
 *   <li>字段改写门：{@code entities.MediaSaveConfig#<init>(ZZZ)V} 构造完成后把
 *       {@code disableSaveMedia} 置 false，覆盖直接读字段（iget）的调用方。</li>
 *   <li>分享实体门：{@code ShareInfoDetail$Operate.a()} = disable，配合 {@code h()} = type。
 *       9.53.0 上该方法在长按/分享链路里命中 0 次（JSON 直接落字段，不调 getter）。</li>
 *   <li>条目门（9.53.0 真机抓栈定位，实际主门）：{@code j69.l#d()} = disable，
 *       配合 {@code k()} = type。点「保存」时取值探针实测为 {@code TRUE}，随后由
 *       {@code ItemTopButtonPresenter} 的 lambda 弹出条目自带的 {@code disableToast}，
 *       下载链路一步都没进；改成 false 后立即落盘
 *       （2026-11-14 10:32 {@code /sdcard/DCIM/Camera_XHS_*.jpg}）。
 *       缺了它，前面五道门全都够不着。</li>
 * </ol>
 *
 * <p>六道门都只改「是否允许保存」的判定，不改保存执行链本身；水印仍由 EN-01/EN-03 负责。
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

    /**
     * 旧实体的「禁止保存」字段名。9.53.0 未混淆：dexdump 可见
     * {@code <init>(ZZZ)V} 依次 iput 到 disableSaveMedia / disableWaterMark /
     * disableCoverWeibo，且 {@code b()Z} 就是 {@code iget disableSaveMedia}。
     */
    static final String FIELD_DISABLE_SAVE_MEDIA = "disableSaveMedia";


    /** 沿父类链找字段并 setAccessible；找不到返回 null，由调用方 fail-open。 */
    private static Field declaredField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field fld = c.getDeclaredField(name);
                fld.setAccessible(true);
                return fld;
            } catch (Throwable ignored) {
                // 继续向父类找
            }
        }
        return null;
    }

    /**
     * 构造完成后把 disableSaveMedia 改写成 false。
     *
     * <p>为什么不用 getter hook：调用方是 {@code iget} 直读字段，方法级 hook 拦不到；
     * 而字段读取在 Xposed 里无法 hook，只能在「对象被造出来的那一刻」把值改掉。
     * 只改这一个字段，不动 disableWaterMark / disableCoverWeibo（前者归 EN-01/EN-03，
     * 后者是微博封面，与保存无关）。
     *
     * <p>原始值记进 hit detail，便于诊断页确认「宿主真的下发过 true」而不是白挂。
     */
    private static XposedInterface.Hooker forceFieldHooker(XposedModule module, Feature f,
                                                           Field flag) {
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self != null) {
                        boolean before = flag.getBoolean(self);
                        if (before) {
                            flag.setBoolean(self, false);
                            HookKit.hit(module, f,
                                    "MediaSaveConfig.disableSaveMedia true->false");
                        }
                    }
                } catch (Throwable t) {
                    // 改写失败只影响本功能，绝不向上抛：构造器异常会炸宿主。
                    HookKit.error(module, f.id + " disableSaveMedia rewrite failed", t);
                }
                return result;
            }
        };
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.FORCE_SAVE;
        int installed = 0;

        // 门 1：MediaSaveConfig.b() -> false（主门，缺失即功能不可用）。
        try {
            // 2026-11-06 起 b() 已收编为方法锚点，check-mounts 会逐项校验；
            // 宿主改名时走 critical skip，不再出现「灰度门成功、旧门静默缺失」。
            Method m = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.FORCE_SAVE_MEDIA_CONFIG, true);
            if (m != null && HookKit.install(module, f, "MediaSaveConfig#b(allow-save)", m,
                    HookKit.constBool(module, f, "MediaSaveConfig#b", false))) {
                installed++;
            }
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " skip: MediaSaveConfig " + t);
        }

        // 门 2：arch 模型 getDisableSave() -> false（9.53.0 的实际判定，缺失即功能不可用）。
        // 旧实体由 xq6.i 从新模型拷贝构造，新模型才是上游真源；只挂旧门会出现
        // 「有命中、保存仍被拦」（2026-11-14 真机复核：EN-04 hit 4 次、DCIM 无新增）。
        try {
            Method m = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.FORCE_SAVE_MEDIA_CONFIG_V2, true);
            if (m != null && HookKit.install(module, f,
                    "MediaSaveConfig#getDisableSave(allow-save)", m,
                    HookKit.constBool(module, f, "MediaSaveConfig#getDisableSave", false))) {
                installed++;
            }
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " skip: arch MediaSaveConfig " + t);
        }

        // 门 3：构造时直接把 disableSaveMedia 字段置 false（9.53.0 真机定位后的主门）。
        // 调用方读的是字段本身（iget）而不是 b()，getter hook 拿不到采纳——真机上
        // b() 有 hit、返回 false，按钮仍置灰 + toast，就是这条原因。改字段后 iget 与
        // b() 两条读法都拿到「允许保存」，不再依赖调用方走哪个入口。
        try {
            Class<?> cls = HookKit.findAnchorClass(module, f, cl,
                    AnchorKeys.MEDIA_SAVE_CONFIG, false);
            // 构造器不能用 getDeclaredMethod 解析（Java 反射不认 "<init>" 这个名前缀），
            // 这里直接用 getDeclaredConstructor；HookKit.install 接受 Executable。
            Constructor<?> ctor = null;
            if (cls != null) {
                try {
                    ctor = cls.getDeclaredConstructor(
                            boolean.class, boolean.class, boolean.class);
                    ctor.setAccessible(true);
                } catch (Throwable ignored) {
                    ctor = null;
                }
            }
            Field flag = cls == null ? null : declaredField(cls, FIELD_DISABLE_SAVE_MEDIA);
            if (ctor != null && flag != null && HookKit.install(module, f,
                    "MediaSaveConfig#<init>(ZZZ) disableSaveMedia=false", ctor,
                    forceFieldHooker(module, f, flag))) {
                installed++;
            } else if (cls != null) {
                HookKit.warn(module, f.id + " skip: MediaSaveConfig ctor/field 未找到");
            }
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " skip: MediaSaveConfig 字段改写 " + t);
        }

        // 门 4：FunctionSwitch.a() -> true，仅 image_download / video_download。
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

        // 门 5：ShareInfoDetail$Operate.a()（disable）-> false，仅 image/video_download。
        // 9.53.0 真机抓栈证实：点「保存」后既不进 MediaSaveConfig 也不进 FunctionSwitch，
        // 提示文案由条目模型自带的 disableToast 弹出；条目能否执行由 Operate#disable 决定。
        // 这是分享面板/长按菜单这一层的总闸，缺了它前面四道门都够不着。
        try {
            Method disable = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.FORCE_SAVE_OPERATE_DISABLE, true);
            Method type = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.FORCE_SAVE_OPERATE_TYPE, true);
            if (disable != null && type != null) {
                final Method typeGetter = type;
                boolean ok = HookKit.install(module, f, "ShareInfoDetail$Operate#a(allow-click)",
                        disable, new XposedInterface.Hooker() {
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
                                                    "Operate#a:" + typeValue);
                                            return Boolean.FALSE;
                                        }
                                    }
                                } catch (Throwable t) {
                                    HookKit.error(module, f.id + " Operate#a error",
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
            HookKit.warn(module, f.id + " skip: ShareInfoDetail$Operate " + t);
        }

        if (installed == 0) {
            HookState.markNotFound(f, "强制保存挂载点未找到");
        }

        installItemDisableGate(module, f, cl);
    }

    /**
     * 门 6（9.53.0 真机抓栈定位的主门）：长按/分享面板条目的 {@code disable} -> false。
     *
     * <p>取值探针（2026-11-14）实测：点击「保存」那一刻条目模型的 disable 为
     * {@code Boolean.TRUE}，随后由 {@code ItemTopButtonPresenter} 的 lambda 弹出条目自带
     * 的 {@code disableToast}，下载链路一步都没进；改成 false 后立即落盘
     * （{@code /sdcard/DCIM/Camera_XHS_*.jpg}）。同一字段也参与渲染（TRUE 画成灰色），
     * 所以这一处同时解决「按钮是灰的」与「点了只弹提示」。
     *
     * <p>只放行下载/保存类条目，其余（Dislike / Report / 空类型等）保留宿主原值，
     * 避免把真正不可用的项点亮。
     */
    private static void installItemDisableGate(XposedModule module, Feature f, ClassLoader cl) {
        try {
            Method disable = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.FORCE_SAVE_ITEM_DISABLE, true);
            Method type = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.FORCE_SAVE_ITEM_TYPE, true);
            if (disable == null || type == null) {
                return;
            }
            final Method typeGetter = type;
            HookKit.install(module, f, "LongpressItem#d(allow-click)", disable,
                    new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object original = chain.proceed();
                            try {
                                Object self = chain.getThisObject();
                                Object typeValue = self == null ? null : typeGetter.invoke(self);
                                if (typeValue instanceof String
                                        && isDownloadItem((String) typeValue)
                                        && !Boolean.FALSE.equals(original)) {
                                    HookKit.hit(module, f, "Item#d:" + typeValue);
                                    return Boolean.FALSE;
                                }
                            } catch (Throwable t) {
                                HookKit.error(module, f.id + " item disable error",
                                        unwrapInvocation(t));
                            }
                            return original;
                        }
                    });
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " skip: item disable " + t);
        }
    }

    /**
     * 条目类型是否属于「下载/保存」。
     *
     * <p>9.53.0 实测值是 {@code Download}（首字母大写），与 FunctionSwitch 的
     * {@code image_download} 命名不同，所以这里用大小写无关的包含匹配，
     * 两种命名都能覆盖。
     */
    static boolean isDownloadItem(String type) {
        if (type == null) {
            return false;
        }
        return type.toLowerCase(java.util.Locale.ROOT).contains("download");
    }
}
