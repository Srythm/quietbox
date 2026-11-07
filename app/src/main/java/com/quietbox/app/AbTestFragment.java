package com.quietbox.app;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.libxposed.service.XposedService;

/**
 * 「实验」页（EX-02 AB 测试面板）。
 *
 * 顶部是面板总开关和「导出全量实验」，下面是精选的高价值实验，
 * 最底部是模块探测到但未收录的 key（50 条一批，滚到底自动加载）。
 */
public class AbTestFragment extends PreferenceFragmentCompat {

    private static final int OTHER_BATCH = 50;
    private static final String CATALOG = "ab_catalog.tsv";
    /** 宿主每 3 秒回传一次探测值；停留本页时按同样节奏检查是否需要重绘「其他」。 */
    private static final long OTHER_POLL_MS = 3_000L;

    /** 本地副本读取放后台，主线程只负责比较结果与复用已有 Preference 更新。 */
    private static final ExecutorService OTHER_POLL_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "QuietBox-AB-other-poll");
                t.setDaemon(true);
                return t;
            });

    private final ActivityResultLauncher<String> mCreateDoc =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"),
                    this::onExportUri);

    private ConfigStore mStore;
    private AbConfigStore mAb;
    /** mStore/mAb 对应的 service 实例；同一实例内 remote prefs 不变，可复用。 */
    private XposedService mStoreService;
    private MaterialSwitchPreference mMaster;
    private PreferenceCategory mOther;
    private Preference mMore;

    private final List<String> mOtherKeys = new ArrayList<>();
    private final Map<String, String> mOtherReport = new LinkedHashMap<>();
    /** 「其他」区最近一次真正渲染的完整快照，用于判断后台轮询到的新数据是否需要重绘。 */
    private Map<String, String> mOtherSnapshot = Collections.emptyMap();
    private long mOtherSnapshotLast;
    /** 停留本页时定时检查宿主回传；离开页面/切 tab 必须移除。 */
    private final Handler mOtherHandler = new Handler(Looper.getMainLooper());
    private final Runnable mOtherPoll = new Runnable() {
        @Override
        public void run() {
            pollOther();
        }
    };
    /** 每个 key 最近一次写入的序号：旧回调不能覆盖新状态。 */
    private final Map<String, Long> mWriteSeq = new HashMap<>();
    private long mWriteCounter;
    private int mOtherLoaded;
    private boolean mLoadingMore;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);
        mStoreService = ServiceBridge.get();
        mStore = new ConfigStore(requireContext());
        mAb = new AbConfigStore(requireContext());
        build(screen);
    }

    @Override
    public void onDestroyView() {
        // view 已销毁，旧写入的回调不能再改 UI；轮询也必须停掉。
        stopOtherPolling();
        mWriteSeq.clear();
        super.onDestroyView();
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        RecyclerView list = getListView();
        if (list == null) {
            return;
        }
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || rv.canScrollVertically(1)) {
                    return;
                }
                // 不能在 RecyclerView 布局/滚动过程中直接改 adapter，post 到下一帧。
                if (mLoadingMore) {
                    return;
                }
                mLoadingMore = true;
                rv.post(() -> {
                    mLoadingMore = false;
                    if (!isAdded() || getContext() == null) {
                        return;
                    }
                    loadMoreOther();
                });
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
        startOtherPolling();
    }

    @Override
    public void onPause() {
        stopOtherPolling();
        super.onPause();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            stopOtherPolling();
        } else {
            // MainActivity 用 hide/show 保留 Fragment，从别的 tab 回来不会走 onResume。
            refresh();
            startOtherPolling();
        }
    }

    private void build(PreferenceScreen screen) {
        Context ctx = requireContext();

        PreferenceCategory intro = new PreferenceCategory(ctx);
        intro.setTitle(R.string.ab_warning_title);
        screen.addPreference(intro);

        Preference warning = new Preference(ctx);
        warning.setLayoutResource(R.layout.preference_footer);
        warning.setSelectable(false);
        warning.setSummary(R.string.ab_warning);
        intro.addPreference(warning);

        mMaster = new MaterialSwitchPreference(ctx);
        mMaster.setIcon(R.drawable.ic_test);
        mMaster.setTitle(R.string.ab_panel_title);
        mMaster.setSummary(R.string.ab_panel_summary);
        mMaster.setOnPreferenceChangeListener((pref, value) -> {
            boolean requested = (Boolean) value;
            long seq = nextWriteSeq(Feature.AB_TEST_PANEL.key);
            boolean accepted = mAb.setPanelEnabled(requested,
                    committed -> onWriteResult(Feature.AB_TEST_PANEL.key, seq, committed));
            if (!accepted) {
                Toast.makeText(requireContext(), R.string.action_write_failed,
                        Toast.LENGTH_SHORT).show();
                return false;
            }
            // 直接传新值：写入是后台异步的，紧接着读 store 可能还是旧值。
            refresh(requested);
            return true;
        });
        screen.addPreference(mMaster);

        Preference export = new Preference(ctx);
        export.setIcon(R.drawable.ic_test);
        export.setTitle(R.string.ab_export_title);
        export.setSummary(R.string.ab_export_summary);
        export.setOnPreferenceClickListener(pref -> {
            mCreateDoc.launch(exportName());
            return true;
        });
        screen.addPreference(export);

        int lastGroup = -1;
        PreferenceCategory category = null;
        for (AbTest test : AbTests.ALL) {
            if (test.group != lastGroup) {
                category = new PreferenceCategory(ctx);
                category.setTitle(AbTests.groupTitle(test.group));
                screen.addPreference(category);
                lastGroup = test.group;
            }
            MaterialSwitchPreference item = new MaterialSwitchPreference(ctx);
            item.setIcon(R.drawable.ic_test);
            item.setTitle(test.titleRes);
            item.setOnPreferenceChangeListener((pref, value) -> {
                boolean forced = (Boolean) value;
                long seq = nextWriteSeq(test.key);
                boolean accepted = mAb.setForced(test.key, forced, test.forceValue,
                        committed -> onWriteResult(test.key, seq, committed));
                if (!accepted) {
                    Toast.makeText(requireContext(), R.string.action_write_failed,
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
                // 写入是后台异步的，立刻回读可能拿到旧值；这里按本次操作构造一份
                // 乐观覆盖表，只用于刷新摘要。
                Map<String, String> forcedNow = new HashMap<>(mAb.overrides());
                if (forced) {
                    forcedNow.put(test.key, test.forceValue);
                } else {
                    forcedNow.remove(test.key);
                }
                updateItem(item, test, mAb.report(), forcedNow);
                return true;
            });
            item.setKey(test.key);
            category.addPreference(item);
        }

        mOther = new PreferenceCategory(ctx);
        mOther.setTitle(R.string.ab_other_title);
        screen.addPreference(mOther);
    }

    void refresh() {
        refresh(null);
    }

    void refresh(Boolean masterOverride) {
        if (mMaster == null) {
            return;
        }
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        ensureStores(ctx);
        mAb.syncOverrides();
        boolean master = masterOverride != null
                ? masterOverride
                : mStore.get(Feature.AB_TEST_PANEL.key,
                Feature.AB_TEST_PANEL.defaultEnabled);
        // remote 是启动时的权威值；本地副本给运行中的拉取通道用，二者保持一致。
        // masterOverride 是用户刚请求、后台还没 commit 的值：不能先写本地副本，
        // 否则 commit 失败（尤其用户已离开页面、onWriteResult 无法回滚 UI 时）
        // 活通道会一直按未落盘的值生效。成功落盘后由 AbConfigStore 的 mirror 更新。
        if (mayMirrorPanelEnabled(masterOverride != null, mStore.remote())) {
            mAb.syncPanelEnabled(master);
        }
        mMaster.setCheckedValue(master);
        mMaster.setEnabled(mStore.remote());
        mMaster.setSummary(mStore.remote()
                ? R.string.ab_panel_summary : R.string.ab_need_framework);

        boolean active = master && mStore.remote();
        Map<String, String> report = mAb.report();
        Map<String, String> overrides = mAb.overrides();
        PreferenceScreen screen = getPreferenceScreen();
        if (screen != null) {
            // 开关都挂在 PreferenceCategory 下，必须按 key 全局查找，
            // 不能只遍历 screen 的直接子节点。
            for (AbTest test : AbTests.ALL) {
                Preference p = screen.findPreference(test.key);
                if (!(p instanceof MaterialSwitchPreference)) {
                    continue;
                }
                MaterialSwitchPreference item = (MaterialSwitchPreference) p;
                item.setEnabled(active);
                item.setCheckedValue(overrides.containsKey(test.key));
                updateItem(item, test, report, overrides);
            }
        }
        refreshOther(report, mAb.lastReport());
    }

    /**
     * 本地副本（宿主运行中 provider 的读源）只跟随已确认落盘的总开关值。
     *
     * masterOverride 非空 = 这次 refresh 用的是用户刚请求、后台 commit 还没返回的值，
     * 此时同步会给活通道塞一个可能写失败的值；写入成功后由
     * {@link AbConfigStore#setPanelEnabled(boolean, ConfigWrites.Callback)} 的 mirror 更新。
     *
     * 包级可见：由 AbTestFragmentTest 固定这条判定。
     */
    static boolean mayMirrorPanelEnabled(boolean requestedOverride, boolean remoteAvailable) {
        return !requestedOverride && remoteAvailable;
    }

    /**
     * 同一 service 实例内 remote prefs 不会变，复用 store 可省掉每次 refresh
     * 重新构造 ConfigStore/AbConfigStore 触发的 remote prefs binder。
     */
    private void ensureStores(Context ctx) {
        XposedService service = ServiceBridge.get();
        if (ConfigWritePolicy.reuseStore(mStore != null && mAb != null,
                mStoreService, service)) {
            return;
        }
        mStore = new ConfigStore(ctx);
        mAb = new AbConfigStore(ctx);
        mStoreService = service;
    }

    private long nextWriteSeq(String key) {
        long seq = ++mWriteCounter;
        mWriteSeq.put(key, seq);
        return seq;
    }

    /**
     * 后台 commit 的结果回到主线程。成功时 UI 已经乐观更新过，无需处理；
     * 失败必须从真实存储重读，把这次改动从 UI 上撤掉。
     */
    private void onWriteResult(String key, long seq, boolean committed) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!isAdded() || getView() == null || getContext() == null) {
                return;
            }
            Long latest = mWriteSeq.get(key);
            if (latest == null || latest.longValue() != seq) {
                // 用户已经又改了一次，旧写入的结果不能覆盖新状态。
                return;
            }
            if (ConfigWritePolicy.keepUiState(true, committed)) {
                return;
            }
            Toast.makeText(requireContext(), R.string.action_write_failed,
                    Toast.LENGTH_SHORT).show();
            refresh();
        });
    }

    private void updateItem(MaterialSwitchPreference item, AbTest test,
                            Map<String, String> report, Map<String, String> overrides) {
        boolean forced = overrides.containsKey(test.key);
        String current = report.get(test.key);
        String state;
        if (forced) {
            state = getString(R.string.ab_forced_value, test.forceValue);
        } else if (current != null) {
            state = getString(R.string.ab_current_value, current);
        } else {
            state = getString(R.string.ab_not_read);
        }
        item.setSummary(test.key + " · " + state + "\n" + getString(test.summaryRes));
    }

    /** 重置分页并加载第一批；后续由滚动监听 / 「加载更多」增量追加。 */
    private void refreshOther(Map<String, String> report, long lastReport) {
        if (mOther == null) {
            return;
        }
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        mOther.removeAll();
        mMore = null;
        mOtherLoaded = 0;
        mOtherKeys.clear();
        mOtherReport.clear();
        // 记录本次真正渲染出去的快照，轮询据此判断宿主是否回传了新 key/值。
        mOtherSnapshot = new LinkedHashMap<>(report);
        mOtherSnapshotLast = lastReport;

        Preference info = new Preference(ctx);
        info.setSelectable(false);
        info.setSummary(lastReport == 0
                ? getString(R.string.ab_other_empty)
                : getString(R.string.ab_other_last,
                DateFormat.format("MM-dd HH:mm:ss", lastReport)));
        mOther.addPreference(info);

        for (Map.Entry<String, String> e : report.entrySet()) {
            if (!AbTests.isCurated(e.getKey())) {
                mOtherReport.put(e.getKey(), e.getValue());
            }
        }
        mOtherKeys.addAll(mOtherReport.keySet());
        Collections.sort(mOtherKeys);

        loadMoreOther();
    }

    /** 停留本页时每 3 秒检查一次宿主回传，避免「其他」区一直停在进入页面时的快照。 */
    private void startOtherPolling() {
        mOtherHandler.removeCallbacks(mOtherPoll);
        if (!isResumed() || isHidden()) {
            return;
        }
        mOtherHandler.postDelayed(mOtherPoll, OTHER_POLL_MS);
    }

    private void stopOtherPolling() {
        mOtherHandler.removeCallbacks(mOtherPoll);
    }

    /**
     * 本地副本读取放后台（SharedPreferences 不保证主线程零 I/O），
     * 只有探测值或回传时间真的变化时才在主线程重绘「其他」区。
     */
    private void pollOther() {
        if (!isAdded() || !isResumed() || isHidden() || getView() == null) {
            return;
        }
        final Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        // 不在这里 ensureStores：service 换绑后构造 store 会走 binder，绑定回调
        // 已经会触发 refresh() 重建；轮询只读现有 store 的本地副本。
        final AbConfigStore store = mAb;
        final Activity host = getActivity();
        if (host == null || store == null) {
            return;
        }
        OTHER_POLL_EXECUTOR.execute(() -> {
            Map<String, String> latest;
            long last;
            try {
                latest = store.report();
                last = store.lastReport();
            } catch (Throwable t) {
                latest = null;
                last = 0L;
            }
            final Map<String, String> report = latest;
            final long reportLast = last;
            host.runOnUiThread(() -> {
                if (!isAdded() || !isResumed() || isHidden() || getView() == null
                        || getActivity() != host) {
                    return;
                }
                if (report != null
                        && otherReportChanged(mOtherSnapshot, mOtherSnapshotLast,
                        report, reportLast)) {
                    refreshOther(report, reportLast);
                }
                startOtherPolling();
            });
        });
    }

    /**
     * 「其他」区是否需要重绘：探测值集合或最近回传时间任一变化都要刷新。
     * 包级可见：由 AbTestFragmentTest 固定该判定。
     */
    static boolean otherReportChanged(Map<String, String> rendered, long renderedLast,
                                      Map<String, String> latest, long latestLast) {
        if (renderedLast != latestLast) {
            return true;
        }
        return rendered == null ? latest != null : !rendered.equals(latest);
    }

    private void loadMoreOther() {
        if (mOther == null || mOtherLoaded >= mOtherKeys.size()) {
            updateMore();
            return;
        }
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        int end = Math.min(mOtherLoaded + OTHER_BATCH, mOtherKeys.size());
        for (; mOtherLoaded < end; mOtherLoaded++) {
            String key = mOtherKeys.get(mOtherLoaded);
            Preference p = new Preference(ctx);
            p.setTitle(key);
            p.setSummary(getString(R.string.ab_other_summary,
                    mOtherReport.get(key), otherTag(key)));
            p.setSelectable(false);
            mOther.addPreference(p);
        }

        if (mMore == null) {
            mMore = new Preference(ctx);
            mMore.setOnPreferenceClickListener(p -> {
                loadMoreOther();
                return true;
            });
        }
        // 把「加载更多」始终挪到最底部。
        if (mMore.getParent() != null) {
            ((PreferenceGroup) mMore.getParent()).removePreference(mMore);
        }
        mOther.addPreference(mMore);
        updateMore();
    }

    private void updateMore() {
        if (mMore == null) {
            return;
        }
        int total = mOtherKeys.size();
        if (mOtherLoaded >= total) {
            mMore.setTitle(R.string.ab_other_done);
            mMore.setSummary(getString(R.string.ab_other_done_summary, total));
            mMore.setSelectable(false);
        } else {
            mMore.setTitle(R.string.ab_other_load_more);
            mMore.setSummary(getString(R.string.ab_other_loaded, mOtherLoaded, total));
            mMore.setSelectable(true);
        }
    }

    private String otherTag(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        if (k.contains("_ad") || k.contains("ads_") || k.contains("track")
                || k.contains("apm") || k.startsWith("andr_live")
                || k.contains("_config") || k.contains("_interval")) {
            return getString(R.string.ab_other_risky);
        }
        return getString(R.string.ab_other_unknown);
    }

    // ---------- 全量导出 ----------

    private static String exportName() {
        return "quietbox-abtest-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + ".csv";
    }

    private void onExportUri(Uri uri) {
        if (uri == null) {
            return;
        }
        Context app = requireContext().getApplicationContext();
        // 复用当前 store 的本地副本，避免导出线程再构造一份并触发 remote prefs binder。
        AbConfigStore store = mAb;
        new Thread(() -> {
            try {
                String csv = buildCsv(app, store);
                int rows = countRows(csv);
                try (OutputStream os = app.getContentResolver().openOutputStream(uri)) {
                    if (os == null) {
                        throw new IOException("openOutputStream returned null");
                    }
                    // UTF-8 BOM：Excel 直接打开不乱码。
                    os.write(0xEF);
                    os.write(0xBB);
                    os.write(0xBF);
                    os.write(csv.getBytes(StandardCharsets.UTF_8));
                }
                toast(app, app.getString(R.string.ab_export_done, rows));
            } catch (Throwable t) {
                String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                toast(app, app.getString(R.string.ab_export_failed, msg));
            }
        }, "QuietBox-Export").start();
    }

    /** 全量目录（assets/ab_catalog.tsv） + 本次探测值，合成与 PC 导出同口径的 CSV。 */
    private String buildCsv(Context app, AbConfigStore store) throws IOException {
        Map<String, String> report = store != null
                ? store.report() : new AbConfigStore(app).report();
        StringBuilder sb = new StringBuilder(1 << 20);
        sb.append("key,current_value,status,primary_category,recommendation,")
                .append("curated,force_value,curated_title\n");

        Set<String> seen = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                app.getAssets().open(CATALOG), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length < 3 || !seen.add(parts[0])) {
                    continue;
                }
                appendRow(sb, app, parts[0], parts[1], parts[2], report);
            }
        }

        // 运行中探测到、但不在 9.50.0 目录里的 key（服务端新增等）。
        for (String key : report.keySet()) {
            if (seen.add(key)) {
                appendRow(sb, app, key, "其他", "未收录", report);
            }
        }
        return sb.toString();
    }

    private void appendRow(StringBuilder sb, Context app, String key, String category,
                           String recommendation, Map<String, String> report) {
        AbTest curated = AbTests.byKey(key);
        String value = report.get(key);
        String status = value != null ? "已读取" : "未读取";
        String rec = curated != null ? "精选（已收录）" : recommendation;
        String force = curated != null ? curated.forceValue : "";
        String title = curated != null ? app.getString(curated.titleRes) : "";
        sb.append(esc(key)).append(',')
                .append(esc(value == null ? "" : value)).append(',')
                .append(status).append(',')
                .append(esc(category)).append(',')
                .append(esc(rec)).append(',')
                .append(curated != null ? "是" : "否").append(',')
                .append(esc(force)).append(',')
                .append(esc(title)).append('\n');
    }

    /**
     * CSV 转义。除逗号/引号/换行外，还要防公式注入：Excel/WPS 会把以
     * {@code = + - @}（以及制表符/回车）开头的单元格当公式执行，而探测值来自
     * 宿主 AB 下发，可能被恶意内容注入。这类值前加单引号强制按文本处理。
     */
    static String esc(String value) {
        if (value == null) {
            return "";
        }
        String safe = neutralizeFormula(value);
        boolean quote = safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0
                || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0;
        String escaped = safe.replace("\"", "\"\"");
        return quote ? "\"" + escaped + "\"" : escaped;
    }

    /** 以 Excel 公式起始字符开头的值加单引号，避免 CSV 被当作公式执行。 */
    static String neutralizeFormula(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r') {
            return "'" + value;
        }
        return value;
    }

    private static int countRows(String csv) {
        int lines = 0;
        for (int i = 0; i < csv.length(); i++) {
            if (csv.charAt(i) == '\n') {
                lines++;
            }
        }
        return Math.max(0, lines - 1);
    }

    private static void toast(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(
                () -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }
}
