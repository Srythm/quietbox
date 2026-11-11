package com.quietbox.app;

import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.color.DynamicColors;

/**
 * 单 Activity 外壳：工具栏 + 四个顶层 tab（设置 / 测试 / 诊断 / 关于）+ 常驻底栏。
 *
 * 设置/诊断/关于三个 Fragment 首次创建时一次性 add，切换只用 hide/show，因此各自的
 * Preference 滚动位置与控件状态都会保留；「测试」tab 按需创建（其 onCreatePreferences
 * 会同步做两次 remote prefs binder，不该让从不打开测试页的用户也承担）。设置是默认
 * tab，返回键在任何非设置 tab 上都先回到设置，只有在设置页才退出应用。
 */
public class MainActivity extends AppCompatActivity {

    private static final String STATE_TAB = "state_tab";
    private static final String TAG_SETTINGS = "tab_settings";
    private static final String TAG_TEST = "tab_test";
    private static final String TAG_DIAGNOSTICS = "tab_diagnostics";
    private static final String TAG_ABOUT = "tab_about";

    private static final int[] TAB_IDS = {
            R.id.nav_settings, R.id.nav_test, R.id.nav_diagnostics, R.id.nav_about
    };
    private static final String[] TAB_TAGS = {
            TAG_SETTINGS, TAG_TEST, TAG_DIAGNOSTICS, TAG_ABOUT
    };

    private MaterialToolbar mToolbar;
    private BottomNavigationView mBottomNav;
    private Fragment mCurrentFragment;
    private int mSelectedTab = R.id.nav_settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 必须在 super.onCreate 之前调用，否则动态取色不会生效。
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mToolbar = findViewById(R.id.toolbar);
        mToolbar.setTitle(R.string.app_name);
        mToolbar.setSubtitle(getString(R.string.app_subtitle, AppInfo.versionName(this)));

        mBottomNav = findViewById(R.id.bottom_nav);

        if (savedInstanceState == null) {
            Fragment settings = new SettingsFragment();
            Fragment diagnostics = new DiagnosticsFragment();
            Fragment about = new AboutFragment();
            // commitNow：四个 Fragment 必须在本方法返回前就位，后续 setSelectedItemId
            // 与 mCurrentFragment 才能立即引用；此时监听器尚未挂上，setSelectedItemId
            // 不会触发 switchTab。
            // 「测试」tab 按需创建：AbTestFragment.onCreatePreferences 会同步构造
            // ConfigStore/AbConfigStore（两次 getRemotePreferences binder），一次性 add
            // 会让从不打开测试页的用户也在主线程/每次 Activity 重建时白付这两次 binder。
            // switchTab 已支持 target==null 时 createFragment+add，首次切到测试页才构造。
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.settings_container, settings, TAG_SETTINGS)
                    .add(R.id.settings_container, diagnostics, TAG_DIAGNOSTICS)
                    .hide(diagnostics)
                    .add(R.id.settings_container, about, TAG_ABOUT)
                    .hide(about)
                    .commitNow();
            mCurrentFragment = settings;
            mSelectedTab = R.id.nav_settings;
        } else {
            mSelectedTab = savedInstanceState.getInt(STATE_TAB, R.id.nav_settings);
            if (tabIndex(mSelectedTab) < 0) {
                mSelectedTab = R.id.nav_settings;
            }
            restoreFragments();
        }

        mBottomNav.setSelectedItemId(mSelectedTab);
        mBottomNav.setOnItemSelectedListener(item -> {
            switchTab(item.getItemId());
            return true;
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mSelectedTab != R.id.nav_settings) {
                    // 先切 Fragment，再同步底栏选中态；setSelectedItemId 若触发
                    // 回调，switchTab 会因已选中而直接返回。
                    switchTab(R.id.nav_settings);
                    mBottomNav.setSelectedItemId(R.id.nav_settings);
                } else {
                    finish();
                }
            }
        });

        applyInsets();

        // 顺序不能反：registerListener 对已缓存的 binder 会立即回调，
        // 回调必须先挂上，否则首次绑定会被空回调吃掉、永远等不到重绘。
        ServiceBridge.setOnBound(() -> runOnUiThread(this::refreshCurrent));
        ServiceBridge.bind(getApplicationContext());
    }

    /** 旋转恢复：按 tag 找回四个 Fragment，只显示保存的 tab。 */
    private void restoreFragments() {
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction();
        Fragment target = null;
        String wantedTag = TAB_TAGS[tabIndex(mSelectedTab)];
        for (String tag : TAB_TAGS) {
            Fragment f = fm.findFragmentByTag(tag);
            if (f == null) {
                continue;
            }
            if (tag.equals(wantedTag)) {
                target = f;
                tx.show(f);
            } else {
                tx.hide(f);
            }
        }
        if (target == null) {
            // 状态不完整时按当前 tab 重建，避免容器空白。
            target = createFragment(mSelectedTab);
            tx.add(R.id.settings_container, target, wantedTag);
        }
        tx.commitNow();
        mCurrentFragment = target;
    }

    /** hide/show 切换，不 replace：保留每个 tab 的滚动位置与控件状态。 */
    private void switchTab(int tabId) {
        int index = tabIndex(tabId);
        if (index < 0) {
            tabId = R.id.nav_settings;
            index = 0;
        }
        if (tabId == mSelectedTab && mCurrentFragment != null) {
            return;
        }
        FragmentManager fm = getSupportFragmentManager();
        if (fm.isStateSaved()) {
            return;
        }

        String tag = TAB_TAGS[index];
        Fragment target = fm.findFragmentByTag(tag);
        FragmentTransaction tx = fm.beginTransaction();
        if (mCurrentFragment != null && mCurrentFragment != target) {
            tx.hide(mCurrentFragment);
        }
        if (target == null) {
            target = createFragment(tabId);
            tx.add(R.id.settings_container, target, tag);
        } else {
            tx.show(target);
        }
        tx.commit();

        mCurrentFragment = target;
        mSelectedTab = tabId;
        // 进入新 tab 时刷新一次，等价于二级页时代 onResume 的行为。
        refreshCurrent();
    }

    private Fragment createFragment(int tabId) {
        switch (tabId) {
            case R.id.nav_test:
                return new AbTestFragment();
            case R.id.nav_diagnostics:
                return new DiagnosticsFragment();
            case R.id.nav_about:
                return new AboutFragment();
            case R.id.nav_settings:
            default:
                return new SettingsFragment();
        }
    }

    /** 返回 tab 在 TAB_IDS/TAB_TAGS 中的下标；未知 id 返回 -1。 */
    private int tabIndex(int tabId) {
        for (int i = 0; i < TAB_IDS.length; i++) {
            if (TAB_IDS[i] == tabId) {
                return i;
            }
        }
        return -1;
    }

    /** targetSdk 36 强制 edge-to-edge，内容会画到状态栏/导航栏下面，必须自己吃 insets。 */
    private void applyInsets() {
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            // bottom 不能写到 root：BottomNavigationView 在 Material 构造时会自己把
            // 同一份导航栏 inset 加进自身 padding，root 再垫一次底栏会被抬高约
            // 两倍导航栏高度。bottom 只由底栏自己消化（insets 原样下发）。
            v.setPadding(bars.left, bars.top, bars.right, 0);
            return windowInsets;
        });
    }

    /** 框架绑定/断开后刷新当前可见页；关于页是静态内容，不处理。 */
    private void refreshCurrent() {
        // binder 回调可能晚于 Activity 销毁，避免在已销毁的实例上操作 Fragment。
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Fragment f = mCurrentFragment;
        if (f instanceof SettingsFragment) {
            ((SettingsFragment) f).refresh();
        } else if (f instanceof AbTestFragment) {
            ((AbTestFragment) f).refresh();
        } else if (f instanceof DiagnosticsFragment) {
            ((DiagnosticsFragment) f).refresh();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, mSelectedTab);
    }

    @Override
    protected void onDestroy() {
        // 清掉对 Activity 的静态引用，防止退出后 binder 回调仍持有已销毁实例。
        ServiceBridge.setOnBound(null);
        super.onDestroy();
    }
}
