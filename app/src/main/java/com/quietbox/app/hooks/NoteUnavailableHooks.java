package com.quietbox.app.hooks;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;
import com.quietbox.app.anchor.AnchorKeys;
import com.quietbox.app.anchor.AnchorTable;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * EX-01 已删除/不可见笔记仍按本地缓存展示。
 *
 * 现象：笔记在信息流里还能看到（封面、标题、图片都在本地），点进去却立刻变成
 * 「当前内容无法展示」。断网时反而能正常浏览 —— 说明详情页在请求失败时会保留
 * 首屏已有的数据，只有服务端明确返回「错误页」模型时才把内容换掉。
 *
 * 服务端的错误页是 NoteFeed.modelType == "error"（资源字符串「当前内容无法展示」
 * 就是它的错误页）。NoteDetailRepository 的 map 阶段（9.52.1 混淆 lambda 为 rz9.o，
 * 9.52.0 为 yr7.o，9.51.0 为 sr9.o，9.50.0 为 dr9.o，由 NoteDetailRepository.k 直接构造）会把这个
 * 错误页包装成 DetailNoteFeedHolder 交给 UI。
 *
 * 9.52.0 起 NoteFeed 的混淆 getter 被 R8 重新分配（c1 变成返回 LiveShootingInfo，
 * u0 变成返回 boolean），但 modelType / id 字段名稳定。因此取值优先读字段，
 * 读不到才回退旧 getter；否则 EX-01 会静默失配。
 *
 * 处理方式：
 * 1. 仓库里还留着上一份正常 holder（同一 noteId）→ 直接返回它，页面内容不变；
 * 2. 没有可用的旧 holder → 抛 IOException，让 RxJava 走 onError 分支，
 *    也就是断网时那条已验证可用的路径。
 *
 * 注意：不修改 NoteFeed 字段、不伪造内容。没有本地数据时页面会是空的/保持首屏，
 * 但不会伪造出一条笔记。
 */
final class NoteUnavailableHooks {

    private static final String ERROR_MODEL = "error";

    /** 由 anchors.json 的 classes 段在 install 时解析；解析失败则按锚点缺失处理。 */
    private static volatile String sRepoClass;
    private static volatile String sHolderClass;

    /** 每个进程只打一次，确认 map 挂载点确实在链路上。 */
    private static volatile boolean sDiagnosed;

    private NoteUnavailableHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.NOTE_UNAVAILABLE_VIEW;
        try {
            // 目标类名唯一来源是 anchors.json 的 classes 段：适配改锚点表即可同步，
            // 不再在本文件散落硬编码（CONTRIBUTING「类名/方法名走锚点表」）。
            AnchorTable anchors = AnchorTable.get(cl);
            sRepoClass = anchorClassName(anchors, AnchorKeys.NOTE_DETAIL_REPOSITORY);
            sHolderClass = anchorClassName(anchors, AnchorKeys.NOTE_DETAIL_HOLDER);
            Method invoke = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.NOTE_UNAVAILABLE_MAP_INVOKE, true);
            HookKit.install(module, f, "NoteDetailRepository#map", invoke, hookFactory(module, f),
                    XposedInterface.ExceptionMode.PASSTHROUGH);
            HookState.note(f, "服务端返回 error 页时改用本地数据/离线路径");
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
            HookState.note(f, "安装异常");
        }
    }

    /** 取 classes 段的 FQN 字符串（不触发类加载）；表缺失/键缺失返回空串。 */
    static String anchorClassName(AnchorTable anchors, String key) {
        if (anchors == null) {
            return "";
        }
        return anchors.className(key);
    }

    /** 包内可见：供 JVM 单测直接驱动 hook 体（定位失败 fail-open / 真实离线路径）。 */
    static XposedInterface.Hooker hookFactory(XposedModule module, Feature f) {
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                try {
                    if (!sDiagnosed) {
                        sDiagnosed = true;
                        HookKit.hit(module, f, "map observed result=" + className(result)
                                + " modelType=" + modelType(feedOf(result)));
                    }
                    if (!isErrorHolder(result)) {
                        return result;
                    }
                    Object repo = valueOfType(chain.getThisObject(), sRepoClass);
                    if (repo == null) {
                        // repo 定位失败（R8 静态化让 getThisObject() 为 null、lambda 不再捕获
                        // repo、字段类型改变、反射异常被 valueOfType 吞掉）不等于「确实没有
                        // 本地缓存」：按模块 fail-open 约定原样返回 result，只记 skip 供诊断，
                        // 绝不抛 IOException 把详情页直接打不开。文案与离线路径区分，便于定位。
                        HookKit.hit(module, f, "error feed -> repo lookup failed (fail-open)");
                        HookState.markNotFound(f, "锚点缺失 note repository 定位失败");
                        return result;
                    }
                    Object previous = valueOfType(repo, sHolderClass);
                    if (isUsableHolder(previous) && sameNote(previous, result)) {
                        HookKit.hit(module, f, "restore cached holder");
                        return previous;
                    }
                    HookKit.hit(module, f, "error feed -> offline path (no usable cached holder)");
                    // repo 已定位、只是没有可用旧 holder：走与断网相同的离线分支。
                    throw new IOException("quietbox: note unavailable");
                } catch (IOException io) {
                    throw io;
                } catch (Throwable t) {
                    try {
                        HookKit.error(module, f.id + " intercept error", t);
                    } catch (Throwable ignored) {
                        // PASSTHROUGH 模式下 module.log 抛错会直接透给宿主，必须兜住。
                    }
                    return result;
                }
            }
        };
    }

    /** 结果是 DetailNoteFeedHolder 且主 NoteFeed 的 modelType 为 "error"。 */
    private static boolean isErrorHolder(Object holder) {
        Object feed = feedOf(holder);
        return feed != null && ERROR_MODEL.equals(modelType(feed));
    }

    /** 旧 holder 可用：非空、有 NoteFeed、且本身不是错误页。 */
    private static boolean isUsableHolder(Object holder) {
        Object feed = feedOf(holder);
        return feed != null && !ERROR_MODEL.equals(modelType(feed));
    }

    /** 同一篇笔记才允许回填，避免把上一篇的内容显示到当前页。 */
    private static boolean sameNote(Object a, Object b) {
        Object fa = feedOf(a);
        Object fb = feedOf(b);
        if (fa == null || fb == null) {
            return false;
        }
        String ida = noteId(fa);
        String idb = noteId(fb);
        return ida != null && ida.length() > 0 && ida.equals(idb);
    }

    private static Object feedOf(Object holder) {
        String holderClass = sHolderClass;
        if (holder == null || holderClass == null || holderClass.isEmpty()
                || !holderClass.equals(holder.getClass().getName())) {
            return null;
        }
        return callNoArg(holder, "b");
    }

    private static String modelType(Object feed) {
        return readString(feed, "modelType", "c1");
    }

    private static String noteId(Object feed) {
        return readString(feed, "id", "u0");
    }

    /**
     * 先读同名字段，再回退旧 getter（9.52.0 的 NoteFeed 混淆 getter c1/u0 已被
     * R8 分配给别的返回值类型，字段名 modelType/id 反而是稳定的）。
     * 只接受 String：字段存在但类型不符时返回 null，不做 String.valueOf 猜测。
     */
    static String readString(Object target, String fieldName, String getterName) {
        if (target == null) {
            return null;
        }
        try {
            for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                Field fld;
                try {
                    fld = c.getDeclaredField(fieldName);
                } catch (NoSuchFieldException ignored) {
                    continue;
                }
                fld.setAccessible(true);
                Object v = fld.get(target);
                if (v instanceof String) {
                    return (String) v;
                }
                if (v != null) {
                    return null;
                }
                break; // 字段为 null：回退 getter
            }
        } catch (Throwable ignored) {
            // 反射失败：回退 getter
        }
        Object v = callNoArg(target, getterName);
        return v instanceof String ? (String) v : null;
    }

    private static String className(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    private static Object callNoArg(Object target, String name) {
        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 在对象（含父类）的字段里按运行时类型找一个值。混淆 lambda 的字段都声明成 Object。 */
    private static Object valueOfType(Object target, String typeName) {
        if (target == null || typeName == null || typeName.isEmpty()) {
            return null;
        }
        try {
            for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(target);
                        if (value != null && typeName.equals(value.getClass().getName())) {
                            return value;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
