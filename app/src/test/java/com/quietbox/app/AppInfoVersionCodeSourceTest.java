package com.quietbox.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * F338：targetVersion() 必须与 versionCode() 同口径读取完整版本号。
 *
 * <p>PackageInfo.versionCode 自 API 28 起废弃，只截 int；目标应用版本号涨到
 * 超过 int 或框架兼容填充行为变化时会静默输出截断值。这里用源码门禁锁住
 * targetVersion 走 getLongVersionCode()，避免两处口径再次漂移。
 */
public class AppInfoVersionCodeSourceTest {

    @Test
    public void targetVersionUsesLongVersionCode() throws Exception {
        Path appInfo = TestPaths.resolveFromCwd(
                "src/main/java/com/quietbox/app/AppInfo.java",
                "app/src/main/java/com/quietbox/app/AppInfo.java",
                "project/app/src/main/java/com/quietbox/app/AppInfo.java",
                "QuietBox/project/app/src/main/java/com/quietbox/app/AppInfo.java");
        String source = new String(Files.readAllBytes(appInfo), StandardCharsets.UTF_8);

        int start = source.indexOf("static String targetVersion(Context context)");
        assertTrue("AppInfo 必须保留 targetVersion(Context)", start >= 0);
        int end = source.indexOf("\n    }", start);
        assertTrue("找不到 targetVersion 方法结尾", end > start);
        String body = source.substring(start, end);

        assertTrue("targetVersion 必须用 getLongVersionCode() 读取完整版本号",
                body.contains("getLongVersionCode()"));
        assertFalse("不得再读已废弃的 PackageInfo.versionCode 字段",
                body.contains("info.versionCode"));
    }
}
