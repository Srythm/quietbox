package com.quietbox.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 写回状态机「无回调」路径的清理契约（F010）。
 *
 * <p>{@link ConfigStore#put(String, boolean, ConfigWrites.Callback)} 在 remote 不可用
 * 或队列拒绝时直接返回 false 且不会触发 callback（ConfigStore.java:100-104、
 * 124-127），{@link WriteGate#begin} 记下的在途标记必须由调用方显式释放，
 * 否则 refresh 会永远跳过回灌、开关看起来被冻结。
 */
public class WriteGateCancelTest {

    @Test
    public void cancelReleasesPendingWhenWriteWasRejected() {
        WriteGate gate = new WriteGate();
        long seq = gate.begin("k");
        assertTrue(gate.isPending("k"));

        // put 返回 false 且不会回调：必须显式 cancel，不能等 onResult。
        gate.cancel("k", seq);
        assertFalse("被拒绝的写入不得永久留在途标记中", gate.isPending("k"));
    }

    @Test
    public void staleCancelCannotReleaseNewerPendingWrite() {
        WriteGate gate = new WriteGate();
        long first = gate.begin("k");
        long second = gate.begin("k");

        gate.cancel("k", first);
        assertTrue("旧序号的 cancel 不能清掉仍在途的新写入", gate.isPending("k"));

        gate.cancel("k", second);
        assertFalse("最新序号的 cancel 才释放在途标记", gate.isPending("k"));
    }

    @Test
    public void staleResultCannotClearPendingOfNewerWrite() {
        WriteGate gate = new WriteGate();
        long first = gate.begin("k");
        long second = gate.begin("k");

        assertFalse("旧结果已被更新的写入取代",
                gate.onResult("k", first, true, true));
        assertTrue("仍有更新的写入在途", gate.isPending("k"));

        assertTrue(gate.onResult("k", second, false, false));
        assertFalse("最新结果返回后才清除在途标记", gate.isPending("k"));
    }

    @Test
    public void duplicateResultIsIdempotentForPendingState() {
        WriteGate gate = new WriteGate();
        long seq = gate.begin("k");
        assertTrue(gate.onResult("k", seq, true, true));
        assertFalse(gate.isPending("k"));

        long next = gate.begin("k");
        // 旧结果重复到达（如回调被重放）：不能替下一次写入清标记。
        gate.onResult("k", seq, true, true);
        assertTrue("重复旧结果不得清除新一轮在途标记", gate.isPending("k"));

        gate.onResult("k", next, true, true);
        assertFalse(gate.isPending("k"));
    }

    @Test
    public void clearDropsPendingAndLaterCancelIsNoOp() {
        WriteGate gate = new WriteGate();
        long seq = gate.begin("k");
        gate.clear();
        gate.cancel("k", seq);
        assertFalse(gate.isPending("k"));
    }
}
