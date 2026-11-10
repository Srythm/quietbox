package com.quietbox.app.hooks;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import com.quietbox.app.AbReportProvider;
import com.quietbox.app.AbTest;
import com.quietbox.app.AbTests;
import com.quietbox.app.AbOverrides;
import com.quietbox.app.Config;
import com.quietbox.app.Feature;
import com.quietbox.app.HookState;
import com.quietbox.app.anchor.AnchorKeys;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * AB 测试面板的运行时。
 *
 * 读取点只有三处，覆盖小红书 9.52.1 的全部 AB 读取：
 * 1) oq.h#e / #b —— ABTest SDK 的 getValueJustOnce / getValue
 *    （9.52.1 为 oq.h#e/#b；9.52.0 为 XYExperimentImpl#f/#b，9.51.0/9.50.0 为 tq.h；
 *    签名不变，仅 getValueJustOnce 的方法名 f → e）；
 * 2) vk6.p#l —— ConfigCenter 的字符串取值总入口
 *    （9.52.1 为 vk6.p；9.52.0 为 XYConfigCenterImpl，9.51.0 为 mb6.o，9.50.0 为 eb6.o；
 *    方法名与签名不变）。
 *
 * 强制值来自模块 App：remote prefs 作为启动兜底，运行中每 3 秒通过
 * AbReportProvider 拉取本地副本（remote prefs 在宿主里是启动快照，不会热更新）。
 * 启动兜底只在还没成功拉取过 provider 时生效；拉取过之后 provider 失败期间保持
 * 上一次状态，不能用启动快照把运行中改过的强开项回退。
 * 面板总开关关闭时覆盖表清空。
 * 探测到的 key/value 通过模块 App 的 ContentProvider 回传，供「其他」区展示。
 */
public final class AbTestHooks {

    private static final String TAG = "QuietBox";
    private AbTestHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.AB_TEST_PANEL;
        try {
            for (String key : new String[]{
                    AnchorKeys.AB_GET_VALUE_ONCE, AnchorKeys.AB_GET_VALUE}) {
                Method m = HookKit.findAnchorMethod(module, f, cl, key, true);
                HookKit.install(module, f, "ABStore#" + key, m, valueHook(module, f));
            }

            HookKit.install(module, f, "ConfigCenter#l",
                    HookKit.findAnchorMethod(module, f, cl,
                            AnchorKeys.CONFIG_CENTER_GET_VALUE, true),
                    stringHook(module, f));

            // 不再 hook android.app.Application#onCreate（框架类黑名单）：
            // AbRuntime.context() 通过 ActivityThread.currentApplication() 反射取
            // Application Context，refresh 每 3 秒重试，无需依赖框架类挂载点。
            AbRuntime.start(module);
            HookState.note(f, "AB 面板已挂载");
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
            HookState.note(f, "安装异常");
        }
    }

    /** 包级可见：由 AbTestHooksTest 验证命中分支会接诊断上报（F-061）。 */
    static XposedInterface.Hooker valueHook(XposedModule module, Feature f) {
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                try {
                    Object arg = chain.getArg(0);
                    if (arg instanceof String) {
                        String key = (String) arg;
                        AbRuntime.observe(key, result);
                        // 命中上报：EX-02 以前全程 0 hit，强开是否生效无从观测
                        // （CONTRIBUTING「每个 hook 必须接诊断上报」）。
                        HookKit.hit(module, f, "ABStore#getValue");
                        AbTest forced = AbRuntime.override(key);
                        if (forced != null) {
                            HookKit.hit(module, f, "ABStore#override");
                            return coerce(forced, result);
                        }
                    }
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " ab intercept error", t);
                }
                return result;
            }
        };
    }

    /**
     * vk6.p#l 直接返回字符串，调用方自己用 Gson 按 TypeToken 解析。
     * 因此这里喂回去的也必须是 Gson 能按目标类型解析的字面量，否则解析失败
     * 会静默回落到默认值。
     */
    /** 包级可见：由 AbTestHooksTest 验证命中分支会接诊断上报（F-061）。 */
    static XposedInterface.Hooker stringHook(XposedModule module, Feature f) {
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                try {
                    Object arg = chain.getArg(0);
                    if (arg instanceof String) {
                        String key = (String) arg;
                        AbRuntime.observe(key, result);
                        HookKit.hit(module, f, "ConfigCenter#getValue");
                        AbTest forced = AbRuntime.override(key);
                        if (forced != null) {
                            String literal = forced.forcedLiteral();
                            if (literal != null) {
                                HookKit.hit(module, f, "ConfigCenter#override");
                                return literal;
                            }
                        }
                    }
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " config intercept error", t);
                }
                return result;
            }
        };
    }

    /**
     * 按声明的 valueType 产出原语值；运行期 original 的类型用来校验改写是否安全。
     *
     * 三种失效形态都要防住：
     * - original 是 null（key 尚未下发）：按声明类型直接给值，不能静默丢弃；
     * - original 与声明不是同一类：原样返回并告警。第 2 个 Type 参数不参与校验，
     *   返回值由调用方按自己的 Type checkcast（如 {@code ((Number) f(...)).intValue()}），
     *   这里硬给声明类型会在宿主进程抛 ClassCastException；
     * - 声明值解析不出来：返回 original 并告警，绝不猜。
     *
     * 包级可见：由 AbTestHooksTest 固定类型兜底行为。
     */
    static Object coerce(AbTest test, Object original) {
        Object target = test.targetPrimitive();
        if (target == null) {
            Log.w(TAG, "EX-02 coerce fallback: " + test.key + " forceValue="
                    + test.forceValue + " 无法解析为 " + test.valueType);
            return original;
        }
        if (original != null && target.getClass() != original.getClass()) {
            Log.w(TAG, "EX-02 coerce fallback: " + test.key + " 运行期类型 "
                    + original.getClass().getName() + " 与声明 " + test.valueType + " 不符");
            return original;
        }
        return target;
    }

    /** 运行时状态与后台刷新/回传。 */
    static final class AbRuntime {

        private static volatile Map<String, String> sOverrides = Collections.emptyMap();
        private static final Map<String, String> sObserved = new ConcurrentHashMap<>();
        private static final Map<String, String> sSentValues = new ConcurrentHashMap<>();
        private static final Set<String> sLogged = ConcurrentHashMap.newKeySet();
        private static final Set<String> sSkipped = ConcurrentHashMap.newKeySet();
        private static final int MAX_KEYS = 800;
        private static final int MAX_BATCH = 200;

        /**
         * provider 调用放在独立线程池：模块 App 卡住时最多阻塞 2 秒，不拖死 3 秒刷新线程。
         *
         * <p>与 {@link Report} 相同：单线程 + 容量 1 的有界队列 + AbortPolicy。
         * binder 调用不响应 cancel(true)，用无界缓存池会在模块 App 卡死时每 3 秒新建
         * 一个永久阻塞的线程；有界池最多占住 1 个线程、排队 1 个任务，后续提交直接拒绝。
         */
        private static final ExecutorService CALL_POOL = newCallPool();

        /** 包级可见：由 AbTestHooksTest 锁定池形状与拒绝行为。 */
        static ExecutorService newCallPool() {
            return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(1),
                    r -> {
                        Thread t = new Thread(r, "QuietBox-AB-call");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
        }

        private static volatile boolean sEnabled;
        private static volatile boolean sStarted;
        private static volatile long sNextReportTry;
        private static volatile Context sContext;
        private static volatile SharedPreferences sPrefs;
        private static volatile String sLastRaw = "";
        private static volatile int sPullErrors;
        /** 周期任务 refresh/flush 未预料异常的告警计数，与 sPullErrors 同口径前 3 条后静默。 */
        private static volatile int sRefreshErrors;
        /** provider 是否成功拉取过：成功过之后它就是运行期的权威值，失败期间必须保持上一次状态。 */
        private static volatile boolean sProviderPulled;

        private AbRuntime() {
        }

        static void start(XposedModule module) {
            if (sStarted) {
                return;
            }
            sStarted = true;
            bindRemotePrefs(module);
            refresh();
            ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "QuietBox-AB");
                t.setDaemon(true);
                return t;
            });
            ex.scheduleWithFixedDelay(() -> tick(), 2, 3, TimeUnit.SECONDS);
        }

        /**
         * 只在这里碰一次框架 API；后台线程只读 SharedPreferences（线程安全）。
         * 绑定失败必须记一次 WARN：否则 sPrefs 长期为 null，每次 refresh 的启动快照
         * 兜底都静默 return，日志与诊断页毫无线索（F-098）。
         */
        static void bindRemotePrefs(XposedModule module) {
            try {
                sPrefs = module.getRemotePreferences(Config.GROUP);
            } catch (Throwable t) {
                Log.w(TAG, "EX-02 remote prefs unavailable", t);
            }
        }

        /** 每 3 秒的刷新/回传收口：未预料异常按次数限流记 WARN，不再整段静默（F-098）。 */
        static void tick() {
            try {
                refresh();
                flush();
            } catch (Throwable t) {
                if (warnRefreshFailure()) {
                    Log.w(TAG, "ex-02 refresh failed", t);
                }
            }
        }

        /** 周期任务异常告警限流：前 3 条打 WARN，之后静默，避免每 3 秒刷屏（F-098）。 */
        static boolean warnRefreshFailure() {
            return sRefreshErrors++ < 3;
        }

        /** provider 通道故障告警限流：前 3 条打 WARN，之后静默，避免每 3 秒刷屏（F-099）。 */
        static boolean warnPullFailure() {
            return sPullErrors++ < 3;
        }

        static AbTest override(String key) {
            if (!sEnabled || key == null) {
                return null;
            }
            String value = sOverrides.get(key);
            if (value == null) {
                return null;
            }
            AbTest test = AbTests.byKey(key);
            if (test == null) {
                // 用户从「其他」列表手填的未收录 key：按值的字面形状推断类型。
                test = guessType(key, value);
            }
            if (test == null) {
                // 推断不出读点类型就不强开：硬塞一个类型会让调用方 checkcast 崩或静默失效。
                if (sSkipped.add(key)) {
                    Log.w(TAG, "EX-02 override ignored " + key + "=" + value
                            + "：无法推断读点类型");
                }
                return null;
            }
            if (sLogged.add(key)) {
                Log.i(TAG, "EX-02 override " + key + "=" + value
                        + " type=" + test.valueType);
            }
            return test;
        }

        /**
         * 未收录 key 的类型推断：纯数字按组号（INT），"true"/"false" 按布尔（BOOL）。
         * 其他字面量（字符串、JSON 等）判断不出口径，返回 null 由调用方跳过强开；
         * 一律当 BOOL 会把字符串/JSON 读点塞成 Boolean。
         *
         * 包级可见：由 AbTestHooksTest 固定这套推断规则。
         */
        static AbTest guessType(String key, String value) {
            if (value == null) {
                return null;
            }
            if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                return AbTest.forKey(key, value, AbTest.ValueType.BOOL);
            }
            try {
                Integer.valueOf(value);
            } catch (NumberFormatException e) {
                return null;
            }
            return AbTest.forKey(key, value, AbTest.ValueType.INT);
        }
        static void observe(String key, Object value) {
            if (!sEnabled || key == null || value == null || key.length() > 128) {
                return;
            }
            if (!looksLikeAb(key)) {
                return;
            }
            String text = String.valueOf(value);
            if (text.length() > 200) {
                text = text.substring(0, 200);
            }
            if (!sObserved.containsKey(key) && sObserved.size() >= MAX_KEYS) {
                return;
            }
            sObserved.put(key, text);
        }

        private static boolean looksLikeAb(String key) {
            return key.startsWith("andr_") || key.startsWith("ANDR_")
                    || key.startsWith("Andr_") || key.startsWith("caption_")
                    || key.startsWith("local_exp_");
        }

        /**
         * provider 调用最多等 2 秒；超时/异常一律返回 {@link PullResult}，由调用方走兜底。
         * 失败原因随 null 一起带回：队列拒绝/2 秒超时/其它异常分别记 reason，否则这三种
         * 失败会一律静默 return null，refresh 的告警与 sPullErrors 计数永远不可达（F-099）。
         */
        static final class PullResult {
            final Bundle bundle;
            final String reason;

            PullResult(Bundle bundle, String reason) {
                this.bundle = bundle;
                this.reason = reason;
            }

            boolean ok() {
                return bundle != null;
            }
        }

        private static PullResult callProvider(Context ctx, String method, Bundle extras) {
            Future<Bundle> f;
            try {
                f = CALL_POOL.submit(() -> ctx.getContentResolver().call(
                        android.net.Uri.parse("content://" + AbReportProvider.AUTHORITY),
                        method, null, extras));
            } catch (RejectedExecutionException e) {
                // 上一次 provider 调用还卡在 binder 里：直接按失败处理，不再堆积任务。
                return new PullResult(null, "provider 调用被拒绝（上一次调用未完成）");
            }
            try {
                return new PullResult(f.get(2, TimeUnit.SECONDS), null);
            } catch (TimeoutException e) {
                f.cancel(true);
                return new PullResult(null, "provider 调用超时（2 秒）");
            } catch (Throwable t) {
                return new PullResult(null, "provider 调用异常: " + t.getClass().getName());
            }
        }

        private static void refresh() {
            // 运行中优先向模块 App 拉取：remote prefs 在宿主里是启动快照，
            // 改开关后不会变，必须靠 provider 这条活通道。
            String failure = null;
            Context context = context();
            if (context == null) {
                failure = "provider context 不可用";
            } else {
                PullResult pull = callProvider(context, "overrides", null);
                if (pull.ok()) {
                    Bundle b = pull.bundle;
                    sPullErrors = 0;
                    sProviderPulled = true;
                    applyFromBundle(b);
                    return;
                }
                failure = pull.reason;
            }
            // provider 失败（模块 App 被系统杀掉、2 秒超时、调用被拒、无 Context）：
            // 活通道坏了从日志和诊断页都必须看得出来，否则覆盖表静默停在上次快照（F-099）。
            onProviderPullFailed(failure);
            // provider 失败（模块 App 被系统杀掉、2 秒超时、调用被拒）：成功拉取过就不能
            // 回落到启动快照，否则会把运行中改过的强开项整体覆盖、开关被静默翻转。
            if (!mayUseStartupSnapshot(sProviderPulled)) {
                return;
            }
            SharedPreferences sp = sPrefs;
            if (sp == null) {
                return;
            }
            try {
                boolean enabled = sp.getBoolean(Feature.AB_TEST_PANEL.key, false);
                apply(enabled, enabled
                        ? AbOverrides.parse(sp.getString(Config.KEY_AB_OVERRIDES, ""))
                        : Collections.emptyMap());
            } catch (Throwable ignored) {
                // 读取失败时保持上一次状态，不打断宿主。
            }
        }

        /** 解析 provider 返回的覆盖表并应用（成功路径）。 */
        private static void applyFromBundle(Bundle b) {
            try {
                boolean enabled = b.getBoolean(AbReportProvider.KEY_PANEL_ENABLED, false);
                String raw = b.getString(AbReportProvider.KEY_OVERRIDES, "");
                if (!raw.equals(sLastRaw)) {
                    sLastRaw = raw;
                    Log.i(TAG, "EX-02 provider pull enabled=" + enabled + " raw=" + raw);
                }
                apply(enabled, enabled ? AbOverrides.parse(raw) : Collections.emptyMap());
            } catch (Throwable t) {
                if (warnPullFailure()) {
                    Log.w(TAG, "EX-02 provider pull apply failed", t);
                }
            }
        }

        /**
         * provider 拉取失败统一记账：按 sPullErrors 采样打 WARN，并用非 critical 的
         * markNotFound 把失败原因带进诊断页（EX-02 强开整体停摆不再全无痕迹，F-099）。
         * 原因文本去重（core.addReasonLocked），不会每 3 秒堆一条重复 skip。
         */
        static void onProviderPullFailed(String failure) {
            if (warnPullFailure()) {
                Log.w(TAG, "EX-02 provider pull failed: " + failure);
            }
            HookState.markNotFound(Feature.AB_TEST_PANEL,
                    "provider 通道故障: " + failure);
        }

        /**
         * provider 这条活通道失败时，能否用启动快照兜底。
         * 只有本进程还没成功拉取过才允许：成功过说明活通道可用，此时回落到启动
         * 快照会把运行中改过的强开项整体回退，与「读取失败保持上一次状态」自相矛盾。
         *
         * 包级可见：由 AbTestHooksTest 固定这条判定。
         */
        static boolean mayUseStartupSnapshot(boolean providerPulled) {
            return !providerPulled;
        }

        private static void apply(boolean enabled, Map<String, String> next) {
            boolean was = sEnabled;
            Map<String, String> prev = sOverrides;
            sEnabled = enabled;
            sOverrides = next;
            if (was != enabled) {
                // 开关翻转后旧观测值/上报记录都过期，避免把上一次会话的值继续回传。
                sObserved.clear();
                sSentValues.clear();
            }
            if (!next.equals(prev)) {
                Log.i(TAG, "EX-02 overrides loaded: " + next);
            }
        }

        private static void flush() {
            if (!sEnabled || sObserved.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now < sNextReportTry) {
                return;
            }
            Context context = context();
            if (context == null) {
                return;
            }
            List<String> keys = new ArrayList<>();
            List<String> values = new ArrayList<>();
            for (Map.Entry<String, String> e : sObserved.entrySet()) {
                if (e.getValue().equals(sSentValues.get(e.getKey()))) {
                    continue;
                }
                keys.add(e.getKey());
                values.add(e.getValue());
                if (keys.size() >= MAX_BATCH) {
                    break;
                }
            }
            if (keys.isEmpty()) {
                return;
            }
            Bundle extras = new Bundle();
            extras.putStringArray("keys", keys.toArray(new String[0]));
            extras.putStringArray("values", values.toArray(new String[0]));
            try {
                PullResult pull = callProvider(context, "report", extras);
                if (!pull.ok()) {
                    // 超时或模块 App 不可用：一分钟后再试，别每 3 秒刷日志。
                    sNextReportTry = System.currentTimeMillis() + 60_000L;
                    Log.w(TAG, "ab report provider unavailable: " + pull.reason);
                    return;
                }
                for (int i = 0; i < keys.size(); i++) {
                    sSentValues.put(keys.get(i), values.get(i));
                }
            } catch (Throwable t) {
                // 模块 App 未启动、被 MIUI 拦截等情况：一分钟后再试，别每 3 秒刷日志。
                sNextReportTry = System.currentTimeMillis() + 60_000L;
                Log.w(TAG, "ab report provider unavailable", t);
            }
        }

        private static Context context() {
            Context c = sContext;
            if (c != null) {
                return c;
            }
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object app = at.getMethod("currentApplication").invoke(null);
                if (app instanceof Context) {
                    sContext = (Context) app;
                    return sContext;
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    }
}
