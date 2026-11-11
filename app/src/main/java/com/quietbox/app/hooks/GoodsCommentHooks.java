package com.quietbox.app.hooks;

import com.quietbox.app.Feature;
import com.quietbox.app.anchor.AnchorKeys;

import io.github.libxposed.api.XposedModule;

/**
 * AD-07 评论区带货插入屏蔽。
 *
 * 评论区顶部插商品卡/带货弹层的 SPI 实现，类名非混淆。startAddIn 是 void，直接 skip，
 * 宿主方法体（含其内部提示分支）整段不执行。
 *
 * <p>副作用：评论里「提及商品」插入也会一起失效；宿主在话题数已满等分支里的 toast /
 * Popup 提示，以及 pendingOpen 打开 UI 的流程与回调生命周期同样不再发生。
 * 本轮无自然场景，未做真机验证（与 CHANGELOG AD-07 的覆盖声明同口径）。
 */
final class GoodsCommentHooks {

    private GoodsCommentHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.GOODS_COMMENT_BLOCK;
        try {
            HookKit.install(module, f, "startAddIn",
                    HookKit.findAnchorMethod(module, f, cl,
                            AnchorKeys.GOODS_COMMENT_START_ADD_IN, true),
                    HookKit.skipVoid(module, f, "startAddIn"));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
