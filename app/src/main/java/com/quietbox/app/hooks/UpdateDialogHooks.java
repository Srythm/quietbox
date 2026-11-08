package com.quietbox.app.hooks;

import com.quietbox.app.Feature;
import com.quietbox.app.anchor.AnchorKeys;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * UI-04 更新弹窗屏蔽。
 * onCreate 不能跳过：super.onCreate 只在静态 de() 内被调用一次，
 * 跳过会让 Activity.mCalled 校验抛 SuperNotCalledException。
 * 因此 onCreate 只 finish() 后照常 proceed；he() 是普通 void，可安全跳过。
 */
final class UpdateDialogHooks {

    private UpdateDialogHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.UPDATE_DIALOG_BLOCK;
        try {
            Method onCreate = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.UPDATE_DIALOG_ON_CREATE, true);
            HookKit.install(module, f, "onCreate", onCreate,
                    HookKit.finishThenProceed(module, f, "onCreate(finish)", null));
            Method he = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.UPDATE_DIALOG_HE, true);
            HookKit.install(module, f, "he", he, HookKit.skipVoid(module, f, "he"));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
