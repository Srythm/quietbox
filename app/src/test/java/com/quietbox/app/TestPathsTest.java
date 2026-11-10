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
        // 只断言 isDirectory 会让实现退化成「命中任意候选」也全绿；必须锁定
        // 返回的就是优先级最高的那个 hooks 目录，而非列表里更靠后的候选。
        assertTrue("必须命中优先级最高的 hooks 目录（而不是靠后的 app/src/main/java）",
                first.endsWith("src/main/java/com/quietbox/app/hooks"));
    }

    /**
     * 用临时 user.dir 构造可控场景，真正锁住「候选优先级」与「逐级向上」：
     * - 两个候选都在 cwd 时，必须返回优先级最高的候选 1（反转候选顺序会变红）；
     * - 候选只在父级时，必须沿父级链向上找到（去掉外层 for(base=base.getParent())
     *   循环或只在 cwd 解析会变红）。
     * 只断言 isDirectory 无法区分这两种回归，故这里对具体返回值做 assertEquals。
     */
    @Test
    public void candidatePriorityAndUpwardTraversalAreLocked() throws Exception {
        Path tmp = Files.createTempDirectory("c11-testpaths-");
        Path cwd = Files.createDirectories(tmp.resolve("deep/inner/cwd"));
        Path firstAtCwd = Files.createFile(cwd.resolve("first-choice"));
        Path secondAtCwd = Files.createFile(cwd.resolve("second-choice"));
        Path parentOnly = Files.createFile(tmp.resolve("parent-only-choice"));

        String original = System.getProperty("user.dir");
        System.setProperty("user.dir", cwd.toString());
        try {
            assertEquals("同一级命中时优先级最高的候选 1 必须胜出",
                    firstAtCwd.toAbsolutePath().normalize(),
                    TestPaths.resolveFromCwd("first-choice", "second-choice")
                            .toAbsolutePath().normalize());
            assertEquals("候选只在父级命中时必须逐级向上找到",
                    parentOnly.toAbsolutePath().normalize(),
                    TestPaths.resolveFromCwd("parent-only-choice")
                            .toAbsolutePath().normalize());
        } finally {
            if (original == null) {
                System.clearProperty("user.dir");
            } else {
                System.setProperty("user.dir", original);
            }
            Files.deleteIfExists(firstAtCwd);
            Files.deleteIfExists(secondAtCwd);
            Files.deleteIfExists(parentOnly);
            Files.deleteIfExists(cwd);
            Files.deleteIfExists(cwd.getParent());
            Files.deleteIfExists(cwd.getParent().getParent());
            Files.deleteIfExists(tmp);
        }
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
