package com.quietbox.app.hooks;

import com.quietbox.app.Config;
import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import static org.junit.Assert.assertTrue;

/**
 * C03 / F011：FeedCardHooks.install 的两条回归。
 *
 * <p>1) 进程级 {@code sInstalled} 一次性守卫不可复位：QuietBoxModule 在安装异常后
 * 回滚自己的 sInstalled 并重试，本类若永久记住「已安装」，重试轮次三个卡过滤会
 * 整体静默缺失。这里连续调用 install 两次，断言第二轮仍真实尝试了挂载点。
 *
 * <p>2) installOne 把三个挂载点串在同一个 try：append 锚点解析/记账抛异常时，
 * refresh / pager 会被连带判死且无 skip 记录。这里让 append 的「锚点未找到」
 * WARN 抛异常，断言 refresh / pager 仍各自被尝试。
 *
 * <p>不依赖宿主类：anchors.json 里的 hgd.m0 / bgd.s 在单测环境不存在，锚点解析
 * fail-open 返回 null，正好稳定复现「逐个锚点独立记账」这条路径。
 */
public class FeedCardHooksRetryTest {

    private static final String NO_EXECUTABLE = "skip: no executable for feedCard";
    private static final String REFRESH_MISS = "anchor not found feed_card_refresh_filter";
    private static final String PAGER_MISS = "anchor not found feed_card_pager_filter";
    /** append 锚点的「未找到」WARN 文案；命中时让 module.log 抛异常，模拟记账失败。 */
    private static final String APPEND_MISS = "anchor not found feed_card_append_filter";

    private final List<String> logMessages = new ArrayList<>();

    private static final Feature[] CARD_FEATURES = {
            Feature.FEED_LIVE_CARD_BLOCK,
            Feature.FEED_GOODS_CARD_BLOCK,
            Feature.FEED_VOTE_CARD_BLOCK,
    };

    @Before
    public void clearHookState() {
        logMessages.clear();
        clearCardFailures();
    }

    @After
    public void restoreHookState() {
        // FeedCardHooks.install 会往进程级 HookState 写这三个 Feature 的 skip 原因；
        // ReportTest 共用它们并断言 skip 为空串，本类结束后必须归还账本，避免污染。
        clearCardFailures();
    }

    private static void clearCardFailures() {
        for (Feature f : CARD_FEATURES) {
            HookState.clearFailures(f);
        }
    }

    /** 第二轮 install 必须真的重新尝试挂载，不能被一次性守卫短路。 */
    @Test
    public void installIsRetryableAfterFailure() {
        XposedModule module = newModule(null);
        Config config = Config.load(module);

        FeedCardHooks.install(module, getClass().getClassLoader(), config);
        int firstRound = count(NO_EXECUTABLE);
        assertTrue("第一轮必须尝试三个卡过滤的挂载点", firstRound > 0);

        FeedCardHooks.install(module, getClass().getClassLoader(), config);
        int secondRound = count(NO_EXECUTABLE);

        assertTrue("第二轮 install 必须重新尝试挂载（可复位），first=" + firstRound
                        + " second=" + secondRound + " logs=" + logMessages,
                secondRound > firstRound);
    }

    /** append 记账抛异常不得连带判死同轮的 refresh / pager 两个挂载点。 */
    @Test
    public void appendFailureDoesNotSkipRefreshAndPager() {
        XposedModule module = newModule(APPEND_MISS);
        Config config = Config.load(module);

        FeedCardHooks.install(module, getClass().getClassLoader(), config);

        assertTrue("append 的记账失败必须被独立吞掉，refresh 仍要尝试，logs=" + logMessages,
                count(REFRESH_MISS) > 0);
        assertTrue("append 的记账失败必须被独立吞掉，pager 仍要尝试，logs=" + logMessages,
                count(PAGER_MISS) > 0);
    }

    private int count(String token) {
        int n = 0;
        for (String msg : logMessages) {
            if (msg != null && msg.contains(token)) {
                n++;
            }
        }
        return n;
    }

    /**
     * @param throwOnLogContains 非 null 时，日志文案包含该子串就让 module.log 抛异常；
     *                           用来模拟某个锚点记账链路抛出 Throwable 的场景。
     */
    private XposedModule newModule(String throwOnLogContains) {
        XposedInterface base = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class},
                (proxy, method, args) -> {
                    if ("log".equals(method.getName()) && args != null) {
                        String msg = args.length >= 3 ? String.valueOf(args[2]) : "";
                        logMessages.add(msg);
                        if (throwOnLogContains != null && msg.contains(throwOnLogContains)) {
                            throw new IllegalStateException("simulated log failure: " + msg);
                        }
                        return null;
                    }
                    if ("hook".equals(method.getName())) {
                        return null; // 锚点解析 fail-open，正常路径不会走到这里
                    }
                    return defaultValue(method.getReturnType());
                });
        XposedModule module = new XposedModule() {
        };
        module.attachFramework(base, () -> {
        });
        return module;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        return null;
    }
}
