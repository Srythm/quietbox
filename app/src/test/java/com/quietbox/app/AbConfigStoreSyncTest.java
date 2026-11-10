package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * F090：本地副本 mReport（ab_report，宿主运行中 provider 的读源）的写入顺序。
 *
 * <p>{@code syncPanelEnabled}/{@code syncOverrides} 曾以主线程同步写 mReport：
 * 主线程用一个「调用前读到的旧 remote 快照」覆盖后台 {@link ConfigWrites} 线程上
 * 刚 mirror 的新值，导致目标进程活通道回退。修复后 sync* 也提交到 ConfigWrites
 * 同一单线程，并在执行时重读 remote 现值。这里锁定：
 * <ul>
 *   <li>执行时以 remote 现值为准，不用调用方旧快照；</li>
 *   <li>后台刚提交的新值不会被随后一次旧快照 sync 覆盖回退；</li>
 *   <li>remote 读失败只记日志，不写、不抛。</li>
 * </ul>
 */
public class AbConfigStoreSyncTest {

    /** 排一个 barrier 到 ConfigWrites 单线程；FIFO 保证此前提交的 sync 都已执行完。 */
    private static void drainConfigWrites() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        assertTrue(ConfigWrites.submit(done::countDown));
        assertTrue("ConfigWrites 必须在 5 秒内排空", done.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void syncPanelEnabledReReadsRemoteInsteadOfStaleArgument() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        // 后台已把 remote 改成 true；主线程手里仍是提交前的旧快照 false。
        remote.edit().putBoolean(Feature.AB_TEST_PANEL.key, true).commit();
        FakeSharedPreferences report = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(remote, new FakeSharedPreferences(), report);

        store.syncPanelEnabled(false);
        drainConfigWrites();

        assertTrue("sync 必须以执行时的 remote 现值为准，不能被调用方旧快照覆盖",
                report.getBoolean(AbReportProvider.KEY_PANEL_ENABLED, false));
    }

    @Test
    public void staleSyncCannotRollBackNewerCommittedValue() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        FakeSharedPreferences report = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(remote, new FakeSharedPreferences(), report);

        // 后台提交 true 并 mirror 到本地副本。
        CountDownLatch committed = new CountDownLatch(1);
        assertTrue(store.setPanelEnabled(true, result -> committed.countDown()));
        assertTrue(committed.await(5, TimeUnit.SECONDS));
        assertTrue("前置条件：新值已 mirror", report.getBoolean(
                AbReportProvider.KEY_PANEL_ENABLED, false));

        // 主线程仍持有旧快照 false，随后 sync：不得把刚 mirror 的 true 覆盖回 false。
        store.syncPanelEnabled(false);
        drainConfigWrites();

        assertTrue("旧快照的 sync 不得让活通道回退到旧值",
                report.getBoolean(AbReportProvider.KEY_PANEL_ENABLED, false));
    }

    @Test
    public void syncOverridesMirrorsCurrentRemote() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        remote.edit().putString(Config.KEY_AB_OVERRIDES, "a=1").commit();
        FakeSharedPreferences report = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(remote, new FakeSharedPreferences(), report);

        store.syncOverrides();
        drainConfigWrites();

        assertEquals("a=1", report.getString(AbReportProvider.KEY_OVERRIDES, ""));
    }

    @Test
    public void syncIsNoOpWithoutRemote() throws Exception {
        FakeSharedPreferences report = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(null, new FakeSharedPreferences(), report);

        store.syncOverrides();
        store.syncPanelEnabled(true);
        drainConfigWrites();

        assertFalse("remote 不可用时不能写覆盖表副本",
                report.contains(AbReportProvider.KEY_OVERRIDES));
        assertFalse("remote 不可用时不能写面板开关副本",
                report.contains(AbReportProvider.KEY_PANEL_ENABLED));
    }

    @Test
    public void syncOverridesReadFailureDoesNotThrowOrWrite() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        remote.setThrowOnRead(true);
        FakeSharedPreferences report = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(remote, new FakeSharedPreferences(), report);

        store.syncOverrides();
        drainConfigWrites();

        assertFalse("remote 读失败只记日志，不得写空表覆盖副本",
                report.contains(AbReportProvider.KEY_OVERRIDES));
    }
}
