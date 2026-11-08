package com.quietbox.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 诊断页：逐功能显示 registered / hit / skip。
 *
 * 主页面零诊断，所有细节集中在这里。数据由目标进程的 HookState 经
 * hooks/Report 回传到 StatusProvider，再落到 status_report。
 * 进入页面和 onResume 时刷新，不自动轮询。
 *
 * <p>主线程只做 UI：逐功能读 SharedPreferences、框架 API/目标版本查询全部在
 * {@link #STATUS_EXECUTOR} 后台单线程完成，结果回主线程前检查 isAdded() 与
 * Activity 实例（DESIGN §14）。
 */
public class DiagnosticsFragment extends PreferenceFragmentCompat {

    /** 诊断数据读取与导出文本构建统一放后台，避免主线程 I/O / binder 卡顿。 */
    private static final ExecutorService STATUS_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "qb-diagnostics-status");
                t.setDaemon(true);
                return t;
            });

    /** 当前页面刷新任务；新刷新提交前取消旧的。 */
    private Future<?> mRefreshFuture;
    /** 刷新序号：旧后台任务晚到不能覆盖更新的快照。 */
    private long mRefreshSeq;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);
        // 内容由 onResume 的异步刷新填充；主线程不读 SharedPreferences。
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onDestroyView() {
        mRefreshSeq++;
        if (mRefreshFuture != null) {
            mRefreshFuture.cancel(false);
            mRefreshFuture = null;
        }
        super.onDestroyView();
    }

    /** 重新读取回传数据；MainActivity 在 service 绑定后也会调用。 */
    void refresh() {
        PreferenceScreen screen = getPreferenceScreen();
        Context context = getContext();
        if (screen == null || context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        final Activity host = getActivity();
        if (app == null || host == null) {
            return;
        }
        final String refreshTime = DateFormat.getTimeFormat(context)
                .format(new Date(System.currentTimeMillis()));
        if (mRefreshFuture != null) {
            mRefreshFuture.cancel(false);
        }
        final long seq = ++mRefreshSeq;
        mRefreshFuture = STATUS_EXECUTOR.submit(() -> {
            DiagData data = collect(app, refreshTime);
            host.runOnUiThread(() -> {
                if (seq != mRefreshSeq || !isAdded() || getView() == null
                        || getActivity() != host || getPreferenceScreen() != screen) {
                    return;
                }
                screen.removeAll();
                build(screen, data);
            });
        });
    }

    private void build(PreferenceScreen screen, DiagData data) {
        Preference header = new Preference(requireContext());
        header.setTitle(R.string.diag_title);
        header.setSummary(getString(R.string.diag_header, data.refreshTime)
                + "\n" + getString(R.string.diag_hint));
        header.setSelectable(false);
        screen.addPreference(header);

        boolean hasData = false;
        for (Feature.Group group : Feature.Group.values()) {
            PreferenceCategory category = new PreferenceCategory(requireContext());
            category.setTitle(groupTitle(group));
            // 先把 category 挂到 screen，它才会拿到 PreferenceManager；
            // 否则 category.addPreference() 在 getNextId 上 NPE。
            screen.addPreference(category);
            for (Feature f : Feature.values()) {
                if (f.group != group) {
                    continue;
                }
                category.addPreference(row(f, data));
                if (Boolean.TRUE.equals(data.hasData.get(f))) {
                    hasData = true;
                }
            }
        }

        if (!hasData) {
            Preference empty = new Preference(requireContext());
            empty.setTitle(R.string.diag_empty_title);
            empty.setSummary(getString(R.string.diag_empty,
                    getString(R.string.app_target_name)));
            empty.setSelectable(false);
            screen.addPreference(empty);
        }

        addCrashSection(screen, data.crashes);

        Preference refresh = new Preference(requireContext());
        refresh.setTitle(R.string.diag_refresh);
        refresh.setSummary(R.string.diag_refresh_summary);
        refresh.setOnPreferenceClickListener(p -> {
            refresh();
            return true;
        });
        screen.addPreference(refresh);

        Preference copy = new Preference(requireContext());
        copy.setTitle(R.string.diag_copy);
        copy.setSummary(R.string.diag_copy_summary);
        copy.setOnPreferenceClickListener(p -> {
            copyDiagnostics();
            return true;
        });
        screen.addPreference(copy);

        Preference export = new Preference(requireContext());
        export.setTitle(R.string.diag_export_title);
        export.setSummary(R.string.diag_export_summary);
        export.setOnPreferenceClickListener(p -> {
            exportDiagnostics();
            return true;
        });
        screen.addPreference(export);
    }

    /** 最近崩溃区（crash0 最新）：无崩溃时不显示。 */
    private void addCrashSection(PreferenceScreen screen, String[] crashes) {
        PreferenceCategory category = null;
        for (int i = 0; i < crashes.length; i++) {
            String crash = crashes[i];
            if (crash == null || crash.isEmpty()) {
                continue;
            }
            if (category == null) {
                category = new PreferenceCategory(requireContext());
                category.setTitle(R.string.diag_crash_category);
                screen.addPreference(category);
            }
            category.addPreference(crashRow(crash));
        }
    }

    /** 崩溃行：标题为格式化时间，摘要为线程/异常/栈帧明细（首段解析失败则整行原文）。 */
    private Preference crashRow(String crash) {
        Preference p = new Preference(requireContext());
        String title = crash;
        String detail = crash;
        int sep = crash.indexOf('|');
        if (sep > 0) {
            try {
                long ms = Long.parseLong(crash.substring(0, sep));
                title = DateFormat.getTimeFormat(requireContext()).format(new Date(ms));
                detail = crash.substring(sep + 1);
            } catch (NumberFormatException ignored) {
            }
        }
        p.setTitle(title);
        p.setSummary(detail);
        p.setSelectable(false);
        return p;
    }

    private Preference row(Feature f, DiagData data) {
        Preference p = new Preference(requireContext());
        p.setKey("diag_" + f.key);
        p.setTitle(f.id + "  " + getString(f.titleRes));
        p.setSummary(data.summaries.get(f));
        p.setSelectable(false);
        return p;
    }

    private void copyDiagnostics() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        final Activity host = getActivity();
        if (app == null || host == null) {
            return;
        }
        final String refreshTime = DateFormat.getTimeFormat(context)
                .format(new Date(System.currentTimeMillis()));
        STATUS_EXECUTOR.execute(() -> {
            DiagData data = collect(app, refreshTime);
            host.runOnUiThread(() -> {
                if (!isAdded() || getView() == null || getActivity() != host) {
                    return;
                }
                try {
                    ClipboardManager cm = (ClipboardManager) app
                            .getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("diagnostics", data.copyText));
                        Toast.makeText(app, R.string.diag_copied, Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable ignored) {
                    // 剪贴板不可用不阻塞诊断页。
                }
            });
        });
    }

    /**
     * 导出诊断包：聚合模块版本/系统版本/框架 API/目标版本/各功能状态/最近命中/
     * 最近崩溃为一段文本，ACTION_SEND text/plain 系统分享——不写文件、不依赖
     * FileProvider。文本在后台构建，分享面板拉不起来只弹 Toast，不阻塞诊断页。
     */
    private void exportDiagnostics() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        final Activity host = getActivity();
        if (app == null || host == null) {
            return;
        }
        final String refreshTime = DateFormat.getTimeFormat(context)
                .format(new Date(System.currentTimeMillis()));
        STATUS_EXECUTOR.execute(() -> {
            DiagData data = collect(app, refreshTime);
            host.runOnUiThread(() -> {
                if (!isAdded() || getView() == null || getActivity() != host) {
                    return;
                }
                try {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, data.exportText);
                    startActivity(Intent.createChooser(send,
                            getString(R.string.diag_export_title)));
                } catch (Throwable ignored) {
                    Toast.makeText(app, R.string.diag_export_failed, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /**
     * 后台读取一份完整诊断快照；所有 SharedPreferences / PackageManager /
     * binder 访问都在这里完成，返回后主线程只负责渲染字符串。
     */
    private static DiagData collect(Context app, String refreshTime) {
        SharedPreferences sp = app.getSharedPreferences(StatusProvider.PREFS,
                Context.MODE_PRIVATE);
        Map<Feature, RawStatus> raw = readRawStatus(sp);
        String[] crashes = readCrashes(sp);

        Map<Feature, String> summaries = new EnumMap<>(Feature.class);
        Map<Feature, Boolean> hasData = new EnumMap<>(Feature.class);
        List<String> featureLines = new ArrayList<>();
        List<String> hitLines = new ArrayList<>();
        for (Feature f : Feature.values()) {
            RawStatus status = raw.get(f);
            String summary;
            if (f.blocked()) {
                summary = app.getString(R.string.diag_blocked, f.blockedReason);
            } else {
                summary = summaryText(app, status);
            }
            summaries.put(f, summary);
            hasData.put(f, !f.blocked() && status != null && status.hasReport());
            String title = app.getString(f.titleRes);
            featureLines.add(f.id + " " + title + ": " + summary);
            if (status != null && status.hit > 0) {
                hitLines.add(f.id + " " + title + ": hit " + status.hit);
            }
        }

        String version = AppInfo.versionName(app);
        String target = AppInfo.targetVersion(app);
        String targetText = target != null ? target
                : app.getString(R.string.diag_target_not_installed);
        // 「复制」与「分享」共用同一组抬头行与 featureLines，导出只在其上追加系统信息
        // 并交给 joinExportText 追加分段（ReportProtocolTest 锁定分段顺序）。
        List<String> headerLines = new ArrayList<>();
        headerLines.add(app.getString(R.string.app_name) + " " + version);
        headerLines.add(app.getString(R.string.diag_export_system, Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT));
        headerLines.add(app.getString(R.string.diag_export_framework, frameworkApi(app)));
        headerLines.add(app.getString(R.string.diag_export_target, targetText));
        headerLines.add(app.getString(R.string.diag_header, refreshTime));
        String exportText = joinExportText(joinLines(headerLines), featureLines, hitLines,
                joinCrashText(crashes), app.getString(R.string.diag_no_data),
                app.getString(R.string.diag_export_section_features),
                app.getString(R.string.diag_export_section_hits),
                app.getString(R.string.diag_export_section_crashes));

        List<String> copyHeader = new ArrayList<>();
        copyHeader.add(app.getString(R.string.app_name) + " " + version);
        copyHeader.add(app.getString(R.string.diag_header, refreshTime));
        String copyText = joinReportText(joinLines(copyHeader), featureLines,
                joinCrashText(crashes), app.getString(R.string.diag_copy_crash_header));
        return new DiagData(refreshTime, summaries, hasData, crashes,
                exportText, copyText);
    }

    /** 把一组行按 '\n' 连接（不追加尾换行），供导出/复制共用同一组抬头文件。 */
    static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    /**
     * 「复制」文本：抬头 + 全部功能状态行 + 最近崩溃段。与「分享」共用 collect()
     * 里同一份 featureLines 与 crashBlock，新增诊断字段只改一处；静态包级可见供
     * JVM 单测锁定拼装结果。
     */
    static String joinReportText(String headerBlock, List<String> featureLines,
            String crashBlock, String crashHeader) {
        StringBuilder sb = new StringBuilder(headerBlock).append('\n');
        for (String line : featureLines) {
            sb.append(line).append('\n');
        }
        if (!crashBlock.isEmpty()) {
            sb.append(crashHeader).append('\n').append(crashBlock);
        }
        return sb.toString();
    }

    /**
     * 单功能状态：registered · hit N · skip 原因；无数据用“—”。
     * 纯格式化（只依赖 Context.getString），在后台线程调用。
     */
    private static String summaryText(Context app, RawStatus status) {
        String dash = app.getString(R.string.diag_no_data);
        if (status == null) {
            return dash;
        }
        boolean hasReg = status.reg != null && !status.reg.isEmpty();
        boolean hasSkip = status.skip != null && !status.skip.isEmpty();
        boolean hasHit = status.hit > 0;
        if (!hasReg && !hasSkip && !hasHit) {
            return dash;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(hasReg ? app.getString(R.string.diag_registered) : dash);
        sb.append(" · ").append(app.getString(R.string.diag_hit, status.hit));
        sb.append(" · ").append(app.getString(R.string.diag_skip, hasSkip ? status.skip : dash));
        return sb.toString();
    }

    /**
     * 一次读出全部功能的状态（纯 JVM 可测）：逐键读取失败只影响该键。
     * 调用方已在后台线程，避免主线程逐功能 I/O。
     */
    static Map<Feature, RawStatus> readRawStatus(SharedPreferences sp) {
        Map<Feature, RawStatus> out = new EnumMap<>(Feature.class);
        if (sp == null) {
            return out;
        }
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                continue;
            }
            out.put(f, new RawStatus(
                    readString(sp, StatusProvider.VALUE_PREFIX + StatusProvider.PREFIX_REG
                            + f.key),
                    readString(sp, StatusProvider.VALUE_PREFIX + StatusProvider.PREFIX_SKIP
                            + f.key),
                    readLong(sp, StatusProvider.VALUE_PREFIX + StatusProvider.PREFIX_HIT
                            + f.key)));
        }
        return out;
    }

    /** 按 crash0/1/2 原下标读取最近崩溃；缺项为 null。纯 JVM 可测。 */
    static String[] readCrashes(SharedPreferences sp) {
        String[] out = new String[3];
        if (sp == null) {
            return out;
        }
        out[0] = readString(sp, StatusProvider.VALUE_PREFIX + ReportProtocol.KEY_CRASH_0);
        out[1] = readString(sp, StatusProvider.VALUE_PREFIX + ReportProtocol.KEY_CRASH_1);
        out[2] = readString(sp, StatusProvider.VALUE_PREFIX + ReportProtocol.KEY_CRASH_2);
        return out;
    }

    /** 崩溃文本（crashN 原文逐行）；无崩溃返回空串。原下标必须保留。 */
    static String joinCrashText(String[] crashes) {
        StringBuilder sb = new StringBuilder();
        if (crashes == null) {
            return "";
        }
        for (int i = 0; i < crashes.length; i++) {
            if (crashes[i] != null && !crashes[i].isEmpty()) {
                sb.append("crash").append(i).append(' ').append(crashes[i]).append('\n');
            }
        }
        return sb.toString();
    }

    private static String readString(SharedPreferences sp, String key) {
        try {
            return sp.getString(key, null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long readLong(SharedPreferences sp, String key) {
        try {
            return sp.getLong(key, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 导出文本的纯拼装（无 Android 依赖，静态包级可见供 JVM 单测锁定关键段，
     * 见 ReportProtocolTest）：headerBlock 为版本/系统/框架 API/目标版本/数据时间，
     * 功能状态/最近命中/最近崩溃三个段按固定顺序追加，空段用 noData 占位。
     * 段名由调用方从 strings.xml 传入，Java 不写中文字面量（DESIGN §10）。
     */
    static String joinExportText(String headerBlock, List<String> featureLines,
            List<String> hitLines, String crashBlock, String noData,
            String featuresSection, String hitsSection, String crashesSection) {
        StringBuilder sb = new StringBuilder();
        sb.append(headerBlock);
        sb.append('\n').append(featuresSection).append('\n');
        for (String line : featureLines) {
            sb.append(line).append('\n');
        }
        sb.append('\n').append(hitsSection).append('\n');
        if (hitLines.isEmpty()) {
            sb.append(noData).append('\n');
        } else {
            for (String line : hitLines) {
                sb.append(line).append('\n');
            }
        }
        sb.append('\n').append(crashesSection).append('\n');
        sb.append(crashBlock.isEmpty() ? noData + '\n' : crashBlock);
        return sb.toString();
    }

    /** Xposed 框架 API 版本（模块 App 进程经 XposedService 绑定得知）；未绑定返回提示。 */
    private static String frameworkApi(Context app) {
        try {
            io.github.libxposed.service.XposedService service = ServiceBridge.get();
            return service != null ? String.valueOf(service.getApiVersion())
                    : app.getString(R.string.diag_framework_unbound);
        } catch (Throwable t) {
            return "?";
        }
    }

    private int groupTitle(Feature.Group group) {
        switch (group) {
            case AD:
                return R.string.group_ad;
            case UI:
                return R.string.group_ui;
            case PV:
                return R.string.group_pv;
            case EN:
                return R.string.group_en;
            case EX:
            default:
                return R.string.group_ex;
        }
    }

    /** 一次后台采集的完整快照：主线程渲染时不再触碰任何存储或 binder。 */
    private static final class DiagData {
        final String refreshTime;
        final Map<Feature, String> summaries;
        final Map<Feature, Boolean> hasData;
        final String[] crashes;
        final String exportText;
        final String copyText;

        DiagData(String refreshTime, Map<Feature, String> summaries,
                 Map<Feature, Boolean> hasData, String[] crashes,
                 String exportText, String copyText) {
            this.refreshTime = refreshTime;
            this.summaries = summaries;
            this.hasData = hasData;
            this.crashes = crashes;
            this.exportText = exportText;
            this.copyText = copyText;
        }
    }

    /** 单个功能的一次读取结果；包级可见供 JVM 单测断言。 */
    static final class RawStatus {
        final String reg;
        final String skip;
        final long hit;

        RawStatus(String reg, String skip, long hit) {
            this.reg = reg;
            this.skip = skip;
            this.hit = hit;
        }

        boolean hasReport() {
            return (reg != null && !reg.isEmpty())
                    || (skip != null && !skip.isEmpty())
                    || hit > 0;
        }
    }
}
