package com.quietbox.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 源码/资源扫描测试共用的工作目录定位器（纯 JVM）。
 *
 * Gradle 单测的 cwd 可能是 app/（默认）、project/、QuietBox/ 或 D:\modules；
 * 以前三个测试各写一套向上探测/候选规则，cwd 一变就抛「找不到文件」而不是真正
 * 的契约失败。这里统一成「从 cwd 逐级向上，按顺序尝试显式候选相对路径」。
 */
public final class TestPaths {

    private TestPaths() {
    }

    /**
     * 从 user.dir 出发，逐级向上遍历每一级目录并依次尝试每个相对候选；
     * 返回第一个存在（文件或目录均可）的绝对路径。
     *
     * <p>调用方把 app/、仓库根、模块根（QuietBox/）、D:\modules 四种 cwd 假设
     * 全部列成显式候选；逐级向上让任一假设在任意 cwd 下都能命中。
     *
     * @param relativeCandidates 相对路径候选，按优先级排列
     */
    public static Path resolveFromCwd(String... relativeCandidates) {
        Path cwd = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        List<String> tried = new ArrayList<>();
        for (Path base = cwd; base != null; base = base.getParent()) {
            for (String candidate : relativeCandidates) {
                Path resolved = base.resolve(candidate).normalize();
                if (Files.exists(resolved)) {
                    return resolved;
                }
                tried.add(resolved.toString());
            }
        }
        throw new IllegalStateException("找不到路径：cwd=" + cwd
                + "，候选=" + Arrays.toString(relativeCandidates)
                + "，已尝试=" + tried);
    }
}
