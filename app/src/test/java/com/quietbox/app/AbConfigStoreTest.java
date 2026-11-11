package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AbConfigStore 的写入契约：remote 不可用拒绝、commit 失败不更新本地副本、
 * read-modify-write 不丢其他已强开的项。
 */
public class AbConfigStoreTest {

    private static FakeSharedPreferences remoteWith(String overrides) {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        remote.edit().putString(Config.KEY_AB_OVERRIDES, overrides).commit();
        return remote;
    }

    @Test
    public void remoteUnavailableRejectsForcedWriteAndDoesNotMirror() {
        FakeSharedPreferences report = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(null, new FakeSharedPreferences(), report);
        assertFalse(store.setForced("k", true, "1"));
        assertFalse("remote 不可用时不能写本地副本冒充成功",
                report.contains(AbReportProvider.KEY_OVERRIDES));
    }

    @Test
    public void forcedWriteCommitsRemoteAndMirrorsReport() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        FakeSharedPreferences report = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(remote, new FakeSharedPreferences(), report);
        AtomicBoolean result = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.setForced("k", true, "1", committed -> {
            result.set(committed);
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(result.get());
        assertEquals("k=1", remote.getString(Config.KEY_AB_OVERRIDES, ""));
        assertEquals("k=1", report.getString(AbReportProvider.KEY_OVERRIDES, ""));
    }

    @Test
    public void failedCommitDoesNotMirrorReport() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        remote.setCommitResult(false);
        FakeSharedPreferences report = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(remote, new FakeSharedPreferences(), report);
        AtomicBoolean result = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.setForced("k", true, "1", committed -> {
            result.set(committed);
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse("commit 失败必须回调失败", result.get());
        assertFalse("remote 写失败时不能更新本地副本",
                report.contains(AbReportProvider.KEY_OVERRIDES));
    }

    @Test
    public void readFailureDoesNotClearOtherOverrides() throws Exception {
        FakeSharedPreferences remote = remoteWith("a=1");
        remote.setThrowOnRead(true);
        FakeSharedPreferences report = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(remote, new FakeSharedPreferences(), report);
        AtomicBoolean result = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.setForced("b", true, "2", committed -> {
            result.set(committed);
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse("读失败不能拿空表回写", result.get());
        remote.setThrowOnRead(false);
        assertEquals("a=1", remote.getString(Config.KEY_AB_OVERRIDES, ""));
        assertFalse(report.contains(AbReportProvider.KEY_OVERRIDES));
    }

    @Test
    public void forcedWriteKeepsOtherOverrides() throws Exception {
        FakeSharedPreferences remote = remoteWith("a=1");
        FakeSharedPreferences report = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(remote, new FakeSharedPreferences(), report);
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.setForced("b", true, "2", committed -> done.countDown()));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("a=1\nb=2", remote.getString(Config.KEY_AB_OVERRIDES, ""));
    }

    @Test
    public void panelWriteCommitsRemoteAndMirrorsReport() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        FakeSharedPreferences report = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(remote, new FakeSharedPreferences(), report);
        AtomicBoolean result = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.setPanelEnabled(true, committed -> {
            result.set(committed);
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(result.get());
        assertTrue(remote.getBoolean(Feature.AB_TEST_PANEL.key, false));
        assertTrue(report.getBoolean(AbReportProvider.KEY_PANEL_ENABLED, false));
    }

    @Test
    public void panelWriteCarriesConfigVersionInSameCommit() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(remote, new FakeSharedPreferences(),
                new FakeSharedPreferences());
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.setPanelEnabled(true, committed -> done.countDown()));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("面板开关写入必须在同一次 commit 内带上 config_version",
                Config.CURRENT_CONFIG_VERSION,
                remote.getInt(Config.KEY_CONFIG_VERSION, -1));
        assertEquals("版本标记与开关值共用一次 commit（不能分两次写）",
                1, remote.commitCount());
    }

    @Test
    public void forcedWriteCarriesConfigVersionInSameCommit() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(remote, new FakeSharedPreferences(),
                new FakeSharedPreferences());
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.setForced("k", true, "1", committed -> done.countDown()));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("AB 强开写入必须在同一次 commit 内带上 config_version",
                Config.CURRENT_CONFIG_VERSION,
                remote.getInt(Config.KEY_CONFIG_VERSION, -1));
        assertEquals("版本标记与覆盖表共用一次 commit（不能分两次写）",
                1, remote.commitCount());
    }

    @Test
    public void failedWriteDoesNotLeaveConfigVersionBehind() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        remote.setCommitResult(false);
        AbConfigStore store = new AbConfigStore(remote, new FakeSharedPreferences(),
                new FakeSharedPreferences());
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.setPanelEnabled(true, committed -> done.countDown()));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse("commit 失败时不能出现单独写入的版本标记",
                remote.contains(Config.KEY_CONFIG_VERSION));
    }

    @Test
    public void panelWriteRejectedWhenRemoteUnavailable() {
        FakeSharedPreferences report = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(null, new FakeSharedPreferences(), report);
        assertFalse(store.setPanelEnabled(true));
        assertFalse(report.contains(AbReportProvider.KEY_PANEL_ENABLED));
    }

    @Test
    public void runtimeAbTestEntriesHaveNoResourceCopy() {
        // F335：forKey 造出的运行时条目 titleRes/summaryRes 为 0，UI 必须先判 hasCopy()，
        // 否则 getString(0) 抛 Resources.NotFoundException。
        assertFalse("运行时条目没有资源文案",
                AbTest.forKey("andr_unknown", "1", AbTest.ValueType.INT).hasCopy());
        for (AbTest curated : AbTests.ALL) {
            assertTrue("精选条目必须带文案: " + curated.key, curated.hasCopy());
        }
    }

    @Test
    public void overridesDoNotFallBackToLocalPrefs() {
        // ab_overrides 只存在于 remote；本地 prefs 从未写入过该键，回退读会误报旧值。
        FakeSharedPreferences local = new FakeSharedPreferences();
        local.edit().putString(Config.KEY_AB_OVERRIDES, "stale=1").commit();
        AbConfigStore store = new AbConfigStore(null, local, new FakeSharedPreferences());

        assertTrue("remote 不可用时覆盖表必须为空，不能读本地残留",
                store.overrides().isEmpty());
    }

    @Test
    public void panelEnabledDefaultsToFeatureDefault() {
        AbConfigStore store = new AbConfigStore(new FakeSharedPreferences(),
                new FakeSharedPreferences(), new FakeSharedPreferences());
        assertEquals("面板开关默认值必须来自 Feature 枚举，不能在读取点硬编码",
                Feature.AB_TEST_PANEL.defaultEnabled, store.panelEnabled());
    }

    @Test
    public void panelCommitThrowReportsFailureAndDoesNotMirror() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        remote.setThrowOnCommit(true);
        FakeSharedPreferences report = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(remote, new FakeSharedPreferences(), report);
        AtomicBoolean result = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.setPanelEnabled(true, committed -> {
            result.set(committed);
            done.countDown();
        }));
        assertTrue("commit 抛异常也必须回调，否则 UI 乐观值永不回滚",
                done.await(5, TimeUnit.SECONDS));
        assertFalse("commit 抛异常必须回调失败", result.get());
        assertFalse("remote 写失败时不能更新本地副本",
                report.contains(AbReportProvider.KEY_PANEL_ENABLED));
    }

    @Test
    public void forcedCommitThrowReportsFailureAndDoesNotMirror() throws Exception {
        FakeSharedPreferences remote = remoteWith("a=1");
        remote.setThrowOnCommit(true);
        FakeSharedPreferences report = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(remote, new FakeSharedPreferences(), report);
        AtomicBoolean result = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.setForced("b", true, "2", committed -> {
            result.set(committed);
            done.countDown();
        }));
        assertTrue("commit 抛异常也必须回调", done.await(5, TimeUnit.SECONDS));
        assertFalse("commit 抛异常必须回调失败", result.get());
        assertFalse(report.contains(AbReportProvider.KEY_OVERRIDES));
    }

    @Test
    public void mirrorCommitFailureKeepsRemoteSuccess() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        FakeSharedPreferences report = new FakeSharedPreferences();
        report.setCommitResult(false);
        AbConfigStore store = new AbConfigStore(remote, new FakeSharedPreferences(), report);
        AtomicBoolean result = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.setPanelEnabled(true, committed -> {
            result.set(committed);
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue("remote 已成功时，本地镜像失败不能把结果改成失败", result.get());
        assertTrue(remote.getBoolean(Feature.AB_TEST_PANEL.key, false));
        assertFalse("镜像 commit 失败时不得留下半写状态",
                report.contains(AbReportProvider.KEY_PANEL_ENABLED));
    }
}
