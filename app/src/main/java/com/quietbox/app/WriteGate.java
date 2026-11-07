package com.quietbox.app;

/**
 * 单个 key 的写回状态机适配器：逻辑全部由 core 的泛型
 * {@link io.github.yigenhuobah.core.WriteGate} 实现，本类只固定 Boolean 值类型
 * 与模块内的调用形状。
 *
 * <p>保留原有模块 API（begin/onResult/rollback/clear），调用点无需改动；
 * 回滚值由 core 的 {@code rollback(key, fallback)} 给出：最近一次确认落盘的值
 * 优先，从未确认时由调用方传入的兜底值决定。
 */
final class WriteGate {

    private final io.github.yigenhuobah.core.WriteGate<Boolean> mDelegate =
            new io.github.yigenhuobah.core.WriteGate<>();
    /** 有在途写入的 key：refresh 不能拿回读值覆盖用户刚做的乐观选择。 */
    private final java.util.Set<String> mPending = new java.util.HashSet<>();

    /** 记录一次新写入并返回其序号（单调递增，不依赖系统时钟）。 */
    long begin(String key) {
        synchronized (mPending) {
            mPending.add(key);
        }
        return mDelegate.begin(key);
    }

    /**
     * 记录快照读到的值，作为「还没有确认落盘值」时的回滚基线。
     * 已有确认值时不会覆盖（core 语义），refresh 可以放心逐次播种。
     */
    void seed(String key, boolean value) {
        mDelegate.seed(key, value);
    }

    /** 该 key 是否有尚未返回结果的写入；有则 refresh 不得回写开关状态。 */
    boolean isPending(String key) {
        synchronized (mPending) {
            return mPending.contains(key);
        }
    }

    /**
     * 记录一次写入结果。
     *
     * @return true 表示该结果仍是最新一次写入，调用方可以据此更新界面；
     *         false 表示已有更新的写入，旧结果不能覆盖界面。
     */
    boolean onResult(String key, long seq, boolean committed, boolean requested) {
        boolean latest = mDelegate.onResult(key, seq, committed, requested);
        if (latest) {
            synchronized (mPending) {
                mPending.remove(key);
            }
        }
        return latest;
    }

    /**
     * 失败回滚值：最近一次确认落盘的值；从未确认时返回调用方兜底值。
     *
     * @param key      配置键
     * @param fallback 没有确认值时使用的值（模块内为 {@code !requested}）
     */
    boolean rollback(String key, boolean fallback) {
        return mDelegate.rollback(key, fallback);
    }

    /** Fragment 销毁时丢弃在途结果与确认值。 */
    void clear() {
        synchronized (mPending) {
            mPending.clear();
        }
        mDelegate.clear();
    }
}
