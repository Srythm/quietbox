package com.quietbox.app.hooks;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;
import com.quietbox.app.ReportProtocol;
import com.quietbox.app.StatusProvider;

import io.github.yigenhuobah.core.CrashFlush;
import io.github.yigenhuobah.core.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 目标进程 → 模块 App 的诊断回传（registered / hit N / skip / critical）。
 *
 * 方向与 AbReportProvider 相同：hook 侧只能主动 call 进模块的 ContentProvider
 * （remote prefs 在目标进程只读）。Context 用 ActivityThread 反射拿——Report.start
 * 在 installAll 开头就执行，早于 AbTestHooks 的运行时 Context 捕获，
 * 不能依赖那个回调先拿到 Context。
 *
 * 只由主进程启动：子进程的 hook 注册状态不完整，回传会让诊断页误报。
 * 节流：后台单线程每 3 秒把 HookState 快照打平上报一次，内容没变不发；
 * 每分钟至少发一次心跳刷新 meta.updated_at，避免长期无变化被判为过期。
 * provider 不可达时退避 60 秒，不刷日志。
 */
final class Report {

    private static final String TAG = "QuietBox";
    private static final int MAX_VALUE = 256;

    /**
     * 崩溃 flush 的 core 日志适配器：core 的等级常量与 android.util.Log 一致，
     * 直接把等级/标签/正文转给 Log.println；有异常时附上堆栈文本。
     * 日志自身抛出的异常由 core 的 CrashFlush 吞掉，不能影响崩溃路径。
     */
    private static final Logger CRASH_LOGGER = (level, tag, message, t) -> {
        if (t == null) {
            Log.println(level, tag, message);
        } else {
            Log.println(level, tag, message + '\n' + Log.getStackTraceString(t));
        }
    };

    /**
     * 本次目标进程会话标识："s<millis>-<8位hex>"。毫秒只作参考，唯一性由进程随机
     * 后缀保证，时钟回拨不会让新会话“变小”；接收端按身份比较（不同即新会话）。
     */
    private static final String SESSION = newSessionId();

    /** 会话标识的生成点（每模块一处）：启动毫秒 + 随机 8 位十六进制后缀。 */
    private static String newSessionId() {
        return "s" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 内容无变化时的心跳间隔；必须明显小于 StatusProvider 的 10 分钟 TTL。 */
    private static final long HEARTBEAT_MS = 60_000L;
    /** provider 失败后的退避时间。 */
    private static final long RETRY_MS = 60_000L;
    /** 单次 provider 调用最多等 2 秒，避免诊断线程被卡死的模块 App 永久阻塞。 */
    private static final long CALL_TIMEOUT_MS = 2_000L;

    /**
     * provider 调用池：单线程 + 容量 1 的有界队列。
     * provider 卡住时最多占住一个线程、排队一个任务，后续提交直接拒绝，
     * 不会像每次 new 线程池那样泄漏线程，也不会无限堆积任务。
     */
    private static final ExecutorService CALL_POOL = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            r -> {
                Thread t = new Thread(r, "QuietBox-Report-call");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    private static volatile boolean sStarted;
    private static volatile Context sContext;
    private static volatile Map<String, String> sLast = new TreeMap<>();
    private static volatile long sLastOk;
    private static volatile long sNextTry;
    private static volatile int sErrors;

    /**
     * 回传状态机锁：定时 {@link #flush()} 跑在单线程 executor、崩溃路径
     * {@link #flushNow()} 跑在 CrashFlush 的 daemon 线程，两条线程会并发读写
     * sLast / sLastOk / sNextTry / sErrors。{@code sErrors++} 与「内容 + 时间」的
     * 成对赋值都不是原子的：没有这把锁，失败计数会丢更新、已回传内容与时间可能
     * 来自两次不同回传（心跳窗口错位、同一快照重复发送）。与 {@link #start()} /
     * {@link #installCrashHandler()} 的类锁分开，避免同步 provider 调用期间堵住
     * 这两个一次性入口。与 TiebaQuiet / QuietMap 已修复版本口径一致。
     */
    private static final Object FLUSH_LOCK = new Object();

    /** 单条崩溃文本上限：在 MAX_VALUE=256 内留键名与通道开销余量。 */
    private static final int MAX_CRASH_CHARS = 240;

    /**
     * 最近崩溃环（最新在前，最多 3 条）：UEH 在崩溃线程写入，snapshot 打平为
     * crash0/crash1/crash2 键。只存进程内静态数据，目标进程重启后自然清零。
     */
    private static final List<String> CRASHES = new ArrayList<>(3);
    private static final Object CRASH_LOCK = new Object();
    /** UEH 只包一次：防 installAll 重入（模块热重载）把调用链再包一层。 */
    private static volatile boolean sCrashHandlerInstalled;

    private Report() {
    }

    static synchronized void start() {
        if (sStarted) {
            return;
        }
        sStarted = true;
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "QuietBox-Report");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(() -> {
            try {
                flush();
            } catch (Throwable ignored) {
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    /**
     * 包装 Thread.setDefaultUncaughtExceptionHandler 记录目标进程最近崩溃。
     *
     * 幂等（{@link #sCrashHandlerInstalled} 保证只包一次）；previous 先保存，自定义
     * handler 内先写崩溃、再用 {@link CrashFlush} 同步 flush（最多 200ms），最后透传
     * previous（非空时）；全程 try/catch：绝不因我们抛异常、绝不改变目标 App 原有的
     * 异常处理行为。仅主进程调用；调用点在 Hooks.installAll 读取配置之后、功能开关
     * 判断之前——全部功能关闭时也要捕获崩溃。
     */
    static synchronized void installCrashHandler() {
        if (sCrashHandlerInstalled) {
            return;
        }
        sCrashHandlerInstalled = true;
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                recordCrash(thread, throwable);
            } catch (Throwable ignored) {
            }
            // 崩溃路径：同步 flush 一次，最多等 200ms；runBestEffort 内部吞掉任务异常，
            // 超时/异常以 WARN 写入 CRASH_LOGGER，外层再兜一层，确保绝不影响 previous
            // 的异常处理链。
            try {
                CrashFlush.runBestEffort(() -> Report.flushNow(), 200, CRASH_LOGGER, TAG);
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    /**
     * 记录一条崩溃：时间（epoch 毫秒）| 线程名 | 异常类名 | 栈前 2 帧，
     * 单条截断 ≤ {@link #MAX_CRASH_CHARS}。新的顶掉最旧的；记录本身抛出的任何
     * 异常都不得上抛（由调用方 UEH 的 try/catch 兜底），避免干扰原有处理链。
     */
    static void recordCrash(Thread thread, Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        sb.append(System.currentTimeMillis()).append('|');
        sb.append(thread != null ? thread.getName() : "?").append('|');
        if (throwable == null) {
            sb.append('?');
        } else {
            sb.append(throwable.getClass().getName());
            StackTraceElement[] frames = throwable.getStackTrace();
            for (int i = 0; i < 2 && i < frames.length; i++) {
                sb.append('|').append(frames[i]);
            }
        }
        String entry = truncateCrash(sb.toString());
        synchronized (CRASH_LOCK) {
            CRASHES.add(0, entry);
            while (CRASHES.size() > 3) {
                CRASHES.remove(CRASHES.size() - 1);
            }
        }
    }

    /** 第 i 新的崩溃文本；不足返回空串（空串覆盖清掉模块 App 里的旧会话残留）。 */
    private static String crashAt(int i) {
        synchronized (CRASH_LOCK) {
            return i < CRASHES.size() ? CRASHES.get(i) : "";
        }
    }

    /**
     * 仅测试使用：复位进程级静态状态（UEH 只包一次的闩锁与崩溃环）。
     * Gradle 单测所有类共用一个 JVM，installCrashHandler / recordCrash 的静态
     * 状态不复位会让后续用例拿不到已安装的包装链、或读到上一用例残留的崩溃。
     * 与 TiebaQuiet 的测试隔离口径一致。
     */
    static synchronized void resetForTest() {
        sCrashHandlerInstalled = false;
        synchronized (CRASH_LOCK) {
            CRASHES.clear();
        }
    }

    private static String truncateCrash(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_CRASH_CHARS ? s : s.substring(0, MAX_CRASH_CHARS);
    }

    /**
     * 节流判定 + 退避判定的读起点：必须在同一把锁内取快照，否则定时线程与
     * 崩溃线程可能都判定为「该发」并把同一内容回传两次。
     */
    private static boolean shouldFlush(Map<String, String> content, long now) {
        synchronized (FLUSH_LOCK) {
            boolean changed = !content.equals(sLast);
            // 内容没变也要按心跳重发，否则 meta.updated_at 会一直停在旧值，
            // 超过 TTL 后 StatusProvider 会把正常会话误判为过期。
            if (!changed && now - sLastOk < HEARTBEAT_MS) {
                return false;
            }
            return now >= sNextTry;
        }
    }

    /** 回传成功：记录业务内容与时间，并清掉失败残留的 60 秒退避。 */
    static void noteSuccess(Map<String, String> content, long now) {
        synchronized (FLUSH_LOCK) {
            sLast = content;
            sLastOk = now;
            // 瞬时失败后残留的退避必须清掉：否则一次 60 秒失败会让后续本应
            // 立即发出的正常快照被压制最多一分钟（诊断数据被延迟）。
            sNextTry = 0L;
        }
    }

    /**
     * call() 返回 null / 超时 / 抛异常都视为 provider 暂不可达：退避 60 秒，
     * 不更新 sLast，下次内容变化或退避到期后再试。失败计数与退避写入都在锁内，
     * 避免 sErrors++ 丢更新。
     */
    static void noteFailure() {
        synchronized (FLUSH_LOCK) {
            sNextTry = System.currentTimeMillis() + RETRY_MS;
            if (sErrors++ < 3) {
                Log.w(TAG, "status provider unavailable");
            }
        }
    }

    /** 仅供 JVM 单测：一次锁内读回「最近一次成功回传的内容 + 时间」，避免撕裂读。 */
    static String lastSuccessForTest() {
        synchronized (FLUSH_LOCK) {
            return String.valueOf(sLast) + "@" + sLastOk;
        }
    }

    /** 仅供 JVM 单测：失败残留的退避截止时间（0 表示无退避）。 */
    static long nextTryAt() {
        synchronized (FLUSH_LOCK) {
            return sNextTry;
        }
    }

    /** 仅供 JVM 单测：provider 失败计数。 */
    static int failureCount() {
        synchronized (FLUSH_LOCK) {
            return sErrors;
        }
    }

    /** 仅供 JVM 单测：复位回传状态机，避免用例间互相污染。 */
    static void resetStateForTest() {
        synchronized (FLUSH_LOCK) {
            sLast = new TreeMap<>();
            sLastOk = 0L;
            sNextTry = 0L;
            sErrors = 0;
        }
    }

    private static void flush() {
        Map<String, String> batch = snapshot();
        Map<String, String> content = contentOf(batch);
        if (content.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!shouldFlush(content, now)) {
            return;
        }
        Context context = context();
        if (context == null) {
            return;
        }
        Bundle extras = extrasOf(batch);
        Bundle result = callProvider(context, extras);
        if (result != null) {
            noteSuccess(content, now);
            return;
        }
        noteFailure();
    }

    /**
     * 崩溃路径的同步 flush：不经过节流/心跳/退避，也不使用后台调用池，直接
     * 同步调用 provider，并要求 provider 用 commit() 落盘（{@link StatusProvider#EXTRA_SYNC}）。
     * 任何异常都吞掉——UEH 里只允许记录失败，绝不允许影响宿主原有的崩溃处理。
     */
    static void flushNow() {
        try {
            Map<String, String> batch = snapshot();
            Context context = context();
            if (context == null) {
                return;
            }
            Bundle extras = extrasOf(batch);
            extras.putBoolean(StatusProvider.EXTRA_SYNC, true);
            Uri uri = Uri.parse("content://" + StatusProvider.AUTHORITY);
            Bundle result = context.getContentResolver().call(uri, "report", null, extras);
            if (result != null) {
                noteSuccess(contentOf(batch), System.currentTimeMillis());
            }
        } catch (Throwable ignored) {
            // 崩溃路径 fail-safe：不向外抛任何异常。
        }
    }

    /** 把快照打平为 provider 需要的 keys/values 两个数组。 */
    private static Bundle extrasOf(Map<String, String> batch) {
        List<String> keys = new ArrayList<>(batch.keySet());
        List<String> values = new ArrayList<>();
        for (String key : keys) {
            values.add(batch.get(key));
        }
        Bundle extras = new Bundle();
        extras.putStringArray("keys", keys.toArray(new String[0]));
        extras.putStringArray("values", values.toArray(new String[0]));
        return extras;
    }

    /**
     * provider 调用放到静态有界线程池，最多等 2 秒；超时取消并返回 null，
     * 由 flush 走原有退避路径，绝不向诊断线程外抛异常。
     */
    private static Bundle callProvider(Context context, Bundle extras) {
        final Uri uri = Uri.parse("content://" + StatusProvider.AUTHORITY);
        Future<Bundle> future;
        try {
            future = CALL_POOL.submit(() -> context.getContentResolver().call(uri, "report", null, extras));
        } catch (Throwable t) {
            // 队列已满（前一次调用卡死）或线程池已关闭：直接按失败处理。
            return null;
        }
        return ReportProtocol.awaitOrCancel(future, CALL_TIMEOUT_MS);
    }

    /** 去掉会话/时间戳后的业务内容，用于判断“内容是否真的变化”。 */
    private static Map<String, String> contentOf(Map<String, String> batch) {
        Map<String, String> out = new TreeMap<>(batch);
        out.remove(ReportProtocol.KEY_META_SESSION);
        out.remove(ReportProtocol.KEY_META_UPDATED_AT);
        return out;
    }

    /**
     * HookState 快照 → 平铺的 key/value（key 用 reg./hit./skip./crit. 前缀，
     * value 截断到 256），并附带 meta.session / meta.updated_at 与 crash0/1/2。
     *
     * reg 与 skip 对每个 touched Feature 都必须发送，缺失时发空串：StatusProvider 用
     * 最新值覆盖旧值，只有空串才能清掉上一次目标进程会话遗留的 skip。否则旧 skip
     * 会永久留在 status_report 里，让横幅按「已启用且 skip 非空」持续误报。
     * hit 仍只在 >0 时发送，由 StatusProvider 取历史最大值，不会被空值覆盖。
     * crit 只在主锚点（类）缺失时为 "1"，方法级 skip 保持空串。
     * crash0/1/2（0 最新）始终发送、无崩溃发空串：与 reg/skip 同一清残留机制。
     */
    static Map<String, String> snapshot() {
        Map<String, String> out = new TreeMap<>();
        for (Feature f : HookState.touched()) {
            out.put(StatusProvider.PREFIX_REG + f.key,
                    truncate(join(HookState.labels(f))));
            long hits = HookState.hitCount(f);
            if (hits > 0) {
                out.put(StatusProvider.PREFIX_HIT + f.key, String.valueOf(hits));
            }
            out.put(StatusProvider.PREFIX_SKIP + f.key,
                    truncate(HookState.skipSummary(f)));
            out.put(StatusProvider.PREFIX_CRIT + f.key,
                    HookState.hasCriticalSkip(f) ? "1" : "");
        }
        out.put(ReportProtocol.KEY_CRASH_0, truncate(crashAt(0)));
        out.put(ReportProtocol.KEY_CRASH_1, truncate(crashAt(1)));
        out.put(ReportProtocol.KEY_CRASH_2, truncate(crashAt(2)));
        out.put(ReportProtocol.KEY_META_SESSION, SESSION);
        out.put(ReportProtocol.KEY_META_UPDATED_AT,
                String.valueOf(System.currentTimeMillis()));
        return out;
    }

    private static String join(List<String> labels) {
        StringBuilder sb = new StringBuilder();
        for (String s : labels) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_VALUE ? s : s.substring(0, MAX_VALUE);
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
