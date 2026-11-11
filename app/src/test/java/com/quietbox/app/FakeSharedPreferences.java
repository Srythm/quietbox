package com.quietbox.app;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 纯 JVM 的 SharedPreferences 替身：只实现 ConfigStore/AbConfigStore/StatusProvider
 * 用到的路径。用可控的 commit 结果验证「写失败不能当成功」。
 */
final class FakeSharedPreferences implements SharedPreferences {

    private final Map<String, Object> mValues;
    private final AtomicInteger mCommitCount = new AtomicInteger();
    private boolean mCommitResult = true;
    private boolean mThrowOnCommit;
    private boolean mThrowOnRead;
    /** commit 成功但丢弃 remove：模拟「删除没真正落盘」。 */
    private boolean mDropRemovals;

    /** remove 在 editor 操作表里的哨兵值；与真实 Editor「最后操作生效」语义对齐。 */
    private static final Object REMOVED = new Object();

    FakeSharedPreferences() {
        this(new HashMap<>());
    }

    /** 多个 handle 共享同一份底层数据：用于模拟「重新取一份 prefs 句柄」。 */
    FakeSharedPreferences(Map<String, Object> sharedValues) {
        mValues = sharedValues;
    }

    int commitCount() {
        return mCommitCount.get();
    }

    void setCommitResult(boolean result) {
        mCommitResult = result;
    }

    void setThrowOnCommit(boolean throwOnCommit) {
        mThrowOnCommit = throwOnCommit;
    }

    void setThrowOnRead(boolean throwOnRead) {
        mThrowOnRead = throwOnRead;
    }

    void setDropRemovals(boolean dropRemovals) {
        mDropRemovals = dropRemovals;
    }

    Map<String, Object> values() {
        return mValues;
    }

    /** 读失败注入统一入口：所有读方法一致生效，避免只有两个方法能触发生产侧 catch（F-113）。 */
    private void checkThrowOnRead() {
        if (mThrowOnRead) {
            throw new IllegalStateException("read boom");
        }
    }

    @Override
    public Map<String, ?> getAll() {
        checkThrowOnRead();
        return new HashMap<>(mValues);
    }

    @Override
    public String getString(String key, String defValue) {
        checkThrowOnRead();
        Object v = mValues.get(key);
        return v instanceof String ? (String) v : defValue;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        checkThrowOnRead();
        Object v = mValues.get(key);
        return v instanceof Set ? new HashSet<>((Set<String>) v) : defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        checkThrowOnRead();
        Object v = mValues.get(key);
        return v instanceof Integer ? (Integer) v : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        checkThrowOnRead();
        Object v = mValues.get(key);
        return v instanceof Long ? (Long) v : defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        checkThrowOnRead();
        Object v = mValues.get(key);
        return v instanceof Float ? (Float) v : defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        checkThrowOnRead();
        Object v = mValues.get(key);
        return v instanceof Boolean ? (Boolean) v : defValue;
    }

    @Override
    public boolean contains(String key) {
        checkThrowOnRead();
        return mValues.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new FakeEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
    }

    private final class FakeEditor implements Editor {

        /** 按调用顺序记录 put/remove：同一 key 的后一次操作覆盖前一次。 */
        private final Map<String, Object> mOperations = new LinkedHashMap<>();
        private boolean mClear;

        @Override
        public Editor putString(String key, String value) {
            mOperations.put(key, value);
            return this;
        }

        @Override
        public Editor putStringSet(String key, Set<String> values) {
            mOperations.put(key, values == null ? null : new HashSet<>(values));
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            mOperations.put(key, value);
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            mOperations.put(key, value);
            return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
            mOperations.put(key, value);
            return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            mOperations.put(key, value);
            return this;
        }

        @Override
        public Editor remove(String key) {
            mOperations.put(key, REMOVED);
            return this;
        }

        @Override
        public Editor clear() {
            mClear = true;
            return this;
        }

        @Override
        public boolean commit() {
            mCommitCount.incrementAndGet();
            if (mThrowOnCommit) {
                throw new IllegalStateException("commit boom");
            }
            if (!mCommitResult) {
                return false;
            }
            applyPending();
            return true;
        }

        @Override
        public void apply() {
            if (mThrowOnCommit) {
                throw new IllegalStateException("apply boom");
            }
            applyPending();
        }

        private void applyPending() {
            if (mClear) {
                mValues.clear();
            }
            // 按调用顺序回放：同一 editor 内 put 后 remove 必须清键，
            // remove 后 put 必须留下新值（与真实 SharedPreferences 一致）。
            for (Map.Entry<String, Object> operation : mOperations.entrySet()) {
                if (operation.getValue() == REMOVED) {
                    if (!mDropRemovals) {
                        mValues.remove(operation.getKey());
                    }
                } else {
                    mValues.put(operation.getKey(), operation.getValue());
                }
            }
        }
    }
}
