package com.quietbox.app;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * F096：MainActivity.onCreate 不得一次性 eager add 「测试」tab 的 AbTestFragment。
 *
 * <p>AbTestFragment.onCreatePreferences 会在主线程同步构造 ConfigStore / AbConfigStore
 * （两次 getRemotePreferences 跨进程 binder）。commitNow 一次性 add 四个 Fragment 时，
 * 连从不打开测试页的用户、每次旋转重建都会白付这两次同步 binder。修复方式是测试 tab
 * 按需创建：switchTab 已支持 target==null 时 createFragment + add。
 *
 * <p>MainActivity 是 AppCompatActivity，纯 JVM 不能实例化，用源码扫描固定「不在
 * onCreate 事务里 add 测试 tab」这条结构性不变量（与 SettingsFragmentLogicTest 的
 * 源码顺序护栏同构）。
 */
public class MainActivityLazyTabTest {

    /** onCreate 的 commitNow 事务里不得再 add/hide 测试 tab（TAG_TEST / new AbTestFragment）。 */
    @Test
    public void onCreateDoesNotEagerlyAddTestTab() throws Exception {
        String text = mainActivitySource();
        int onCreate = text.indexOf("protected void onCreate(Bundle savedInstanceState)");
        assertTrue("onCreate 必须存在", onCreate >= 0);
        int savedInstanceStateNull = text.indexOf("if (savedInstanceState == null)", onCreate);
        assertTrue("onCreate 必须保留 savedInstanceState==null 分支", savedInstanceStateNull >= 0);
        int elseBranch = text.indexOf("} else {", savedInstanceStateNull);
        assertTrue("onCreate 必须保留重建分支", elseBranch >= 0);

        String transaction = text.substring(savedInstanceStateNull, elseBranch);
        assertFalse("不得在 onCreate 事务里 new AbTestFragment()（应改为按需创建）",
                transaction.contains("new AbTestFragment()"));
        assertFalse("不得在 onCreate 事务里 add(..., TAG_TEST)",
                transaction.contains("TAG_TEST"));
        assertFalse("不得在 onCreate 事务里声明 test 局部变量",
                transaction.contains("Fragment test"));
    }

    /** 测试 tab 仍能被按需创建：createFragment 必须保留 nav_test → new AbTestFragment 分支。 */
    @Test
    public void testTabIsStillCreatedOnDemand() throws Exception {
        String text = mainActivitySource();
        int createFragment = text.indexOf("private Fragment createFragment(int tabId)");
        assertTrue("createFragment 必须存在", createFragment >= 0);
        int switchEnd = text.indexOf("private int tabIndex(", createFragment);
        String body = text.substring(createFragment, switchEnd > 0 ? switchEnd : text.length());
        assertTrue("createFragment 必须能把 nav_test 映射成 AbTestFragment",
                body.contains("case R.id.nav_test") && body.contains("new AbTestFragment()"));

        // switchTab 仍支持 target==null 时 add（按需创建的落点）。
        int switchTab = text.indexOf("private void switchTab(int tabId)");
        assertTrue("switchTab 必须存在", switchTab >= 0);
        int afterSwitch = text.indexOf("private Fragment createFragment(", switchTab);
        String switchBody = text.substring(switchTab, afterSwitch > 0 ? afterSwitch : text.length());
        assertTrue("switchTab 必须在 target==null 时 createFragment + add",
                switchBody.contains("target == null")
                        && switchBody.contains("createFragment(tabId)")
                        && switchBody.contains("tx.add("));
    }

    private static String mainActivitySource() throws Exception {
        Path source = TestPaths.resolveFromCwd(
                "src/main/java/com/quietbox/app/MainActivity.java",
                "app/src/main/java/com/quietbox/app/MainActivity.java",
                "project/app/src/main/java/com/quietbox/app/MainActivity.java",
                "QuietBox/project/app/src/main/java/com/quietbox/app/MainActivity.java");
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }
}
