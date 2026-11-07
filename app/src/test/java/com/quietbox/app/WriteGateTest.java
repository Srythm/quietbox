package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 写回状态机的契约测试（{@link WriteGate}）。
 *
 * 锁住两条曾经会导致「界面与远端不一致」的语义：
 * <ul>
 *   <li>成功写入一律记入确认值——即使已被更新的写入取代，后续失败仍要回滚到它；</li>
 *   <li>只有最新一次写入的结果才能更新界面，旧结果不能覆盖用户的新选择。</li>
 * </ul>
 */
public class WriteGateTest {

    @Test
    public void supersededSuccessStillBecomesConfirmed() {
        WriteGate gate = new WriteGate();
        long first = gate.begin("k");
        long second = gate.begin("k");

        assertFalse("旧写入已被更新的写入取代，不能改 UI",
                gate.onResult("k", first, true, true));
        assertEquals("但它确实落盘了，必须记入确认值", Boolean.TRUE,
                gate.rollback("k", false));

        assertTrue("最新一次写入的结果仍要应用",
                gate.onResult("k", second, false, false));
        assertEquals("最新写入失败时回滚到最近确认值，而不是更旧的乐观值",
                Boolean.TRUE, gate.rollback("k", true));
    }

    @Test
    public void failureRollsBackToLastConfirmedNotOptimisticValue() {
        WriteGate gate = new WriteGate();
        long first = gate.begin("k");
        assertTrue(gate.onResult("k", first, true, true));

        long second = gate.begin("k");
        assertTrue("最新写入的失败结果要应用到 UI",
                gate.onResult("k", second, false, false));

        assertEquals("回滚目标应是已确认落盘的 true", Boolean.TRUE,
                gate.rollback("k", false));
    }

    @Test
    public void staleFailureCannotRollbackNewerSelection() {
        WriteGate gate = new WriteGate();
        long first = gate.begin("k");
        assertTrue(gate.onResult("k", first, true, true));

        long second = gate.begin("k");
        assertFalse("旧写入的失败结果不能应用到界面",
                gate.onResult("k", first, false, false));
        assertTrue("最新写入的结果仍要应用",
                gate.onResult("k", second, false, false));
        assertEquals("回滚目标仍是最近确认落盘的 true", Boolean.TRUE,
                gate.rollback("k", false));
    }

    @Test
    public void noConfirmedValueBeforeFirstSuccess() {
        WriteGate gate = new WriteGate();
        long seq = gate.begin("k");
        assertTrue(gate.onResult("k", seq, false, true));

        assertEquals("从未成功落盘时回到调用方兜底值，由调用方保证 UI 与上一次选择一致",
                Boolean.TRUE, gate.rollback("k", true));
    }

    @Test
    public void clearDropsInFlightResults() {
        WriteGate gate = new WriteGate();
        long seq = gate.begin("k");
        gate.clear();

        assertFalse("Fragment 销毁后在途结果不能再改界面",
                gate.onResult("k", seq, false, true));
        assertEquals("确认值随 clear 一并丢弃，回到兜底值", Boolean.FALSE,
                gate.rollback("k", false));
    }

    // ------------------------------------------------ 刷新播种 / 在途状态（F-046）

    @Test
    public void seedProvidesBaselineForFirstFailure() {
        WriteGate gate = new WriteGate();
        // 页面打开时读到真实落盘值 false；随后两次快速写入都失败。
        gate.seed("k", false);
        long seq = gate.begin("k");
        assertTrue(gate.onResult("k", seq, false, true));

        assertEquals("两次快速写入都失败时，应回退到刷新时播种的落盘值，"
                        + "而不是第二次写入前的乐观值",
                Boolean.FALSE, gate.rollback("k", true));
    }

    @Test
    public void seedNeverOverwritesConfirmedValue() {
        WriteGate gate = new WriteGate();
        long seq = gate.begin("k");
        assertTrue(gate.onResult("k", seq, true, true));

        // 之后的刷新会再次播种；确认值优先，不能被尚未完成的回读覆盖。
        gate.seed("k", false);
        assertEquals("确认落盘的 true 必须优先于后续快照",
                Boolean.TRUE, gate.rollback("k", false));
    }

    @Test
    public void pendingTracksLatestWriteOnly() {
        WriteGate gate = new WriteGate();
        long first = gate.begin("k");
        assertTrue("刚入队的写入必须标记为在途", gate.isPending("k"));

        long second = gate.begin("k");
        assertFalse("被更新写入取代的旧结果不能清除在途标记",
                gate.onResult("k", first, true, true));
        assertTrue("第二次写入仍在途", gate.isPending("k"));

        assertTrue(gate.onResult("k", second, false, false));
        assertFalse("最新写入返回后不再有在途标记", gate.isPending("k"));
    }

    @Test
    public void clearDropsPendingState() {
        WriteGate gate = new WriteGate();
        gate.begin("k");
        gate.clear();
        assertFalse("view 销毁后不得再因旧在途标记跳过刷新", gate.isPending("k"));
    }
}
