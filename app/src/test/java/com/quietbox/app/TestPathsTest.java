package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试工作目录定位器（F-067）的纯 JVM 单测：三种源码/资源扫描测试以前各写一套
 * 基于 user.dir 的向上探测，候选路径与回退规则都不一致。这里锁定统一实现：
 * 逐级向上 + 显式候选，且候选覆盖 app/、仓库根、模块根三种 cwd 假设。
 */
public class TestPathsTest {

    @Test
    public void resolvesAppJavaSourceRootFromGradleCwd() {
        Path root = TestPaths.resolveFromCwd(
                "src/main/java",
                "app/src/main/java",
                "project/app/src/main/java",
                "QuietBox/project/app/src/main/java");
        assertTrue("必须定位到 app/src/main/java 目录", Files.isDirectory(root));
        assertTrue("目录下必须有 AppInfo.java",
                Files.isRegularFile(root.resolve("com/quietbox/app/AppInfo.java")));
    }

    @Test
    public void resolvesPrefsXmlFromGradleCwd() {
        Path prefs = TestPaths.resolveFromCwd(
                "src/main/res/xml/prefs.xml",
                "app/src/main/res/xml/prefs.xml",
                "project/app/src/main/res/xml/prefs.xml",
                "QuietBox/project/app/src/main/res/xml/prefs.xml");
        assertTrue("必须定位到 prefs.xml 文件", Files.isRegularFile(prefs));
    }

    @Test
    public void firstMatchingCandidateWins() {
        Path first = TestPaths.resolveFromCwd(
                "src/main/java/com/quietbox/app/hooks",
                "app/src/main/java",
                "project/app/src/main/java");
        assertNotNull("优先级最高的候选应被返回", first);
        assertTrue("必须命中 hooks 目录", Files.isDirectory(first));
    }

    @Test
    public void missingPathThrowsWithCwdAndCandidatesInMessage() {
        IllegalStateException error = assertThrows("全部候选都不存在时必须抛异常而非返回 null",
                IllegalStateException.class,
                () -> TestPaths.resolveFromCwd("no/such/path/A", "no/such/path/B"));
        assertTrue("异常信息必须带 cwd", error.getMessage().contains("cwd="));
        assertTrue("异常信息必须列出候选",
                error.getMessage().contains("no/such/path/A"));
    }
}
