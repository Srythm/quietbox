package com.quietbox.app.anchor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * F122：check-mounts.sh 的 Python 解释器解析必须与同仓 check-anchors.sh /
 * verify-apk.sh 一致 —— 优先 python3、没有再退 python。旧写法只认 `python`，
 * 在只有 python3 的 Debian/Ubuntu/CI 镜像上直接 die。
 *
 * <p>纯 JVM：源码门禁固定三级回退表达式；行为验证用受控 PATH（只放一个假的
 * python3、屏蔽 python）实际执行解析逻辑，确认解释器能被解析且不会 die。
 * 探测脚本经 stdin（bash -s）传入且只用 POSIX 内置命令，因此 MSYS Git bash 与
 * WSL bash 都能跑（WSL 读不到 C:/... 的 Windows 临时路径，不能靠传文件路径）。
 */
public class CheckMountsPythonFallbackTest {

    private static final String SCRIPT_RELATIVE = "tools/check-mounts.sh";

    private static File findRepoFile(String relative) {
        File dir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, relative);
            if (candidate.isFile()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        throw new AssertionError("找不到 " + relative + "（cwd="
                + System.getProperty("user.dir") + "）");
    }

    @Test
    public void scriptResolvesPython3BeforePython() throws Exception {
        String script = new String(Files.readAllBytes(findRepoFile(SCRIPT_RELATIVE).toPath()),
                StandardCharsets.UTF_8);
        assertTrue("必须优先 command -v python3，回退 command -v python",
                script.contains("PYTHON=\"${PYTHON:-$(command -v python3 || command -v python || true)}\""));
        assertFalse("不得再硬编码 PYTHON 默认值为 python",
                script.contains("PYTHON=\"${PYTHON:-python}\""));
        assertTrue("缺解释器时必须给出明确修复提示",
                script.contains("安装 Python 3 或设置 PYTHON 环境变量"));
    }

    /**
     * 行为验证：受控 PATH 里只放 python3（无 python）。解析表达式必须命中 python3
     * 且解析结果可执行；旧写法 PYTHON=python 会在这种环境下 die。
     */
    @Test
    public void onlyPython3OnPathStillResolves() throws Exception {
        String bash = findBash();
        Assume.assumeTrue("未找到可执行的 bash，跳过行为验证", bash != null);

        String output = runBash(bash, probeScript());
        Assume.assumeFalse("该 bash 不支持 mktemp，跳过行为验证:\n" + output,
                output.contains("NO_MKTEMP"));

        assertTrue("只有 python3 时解析结果必须指向 python3 且可执行，实际输出:\n" + output,
                output.contains("RESOLVED=") && output.contains("python3")
                        && output.contains("CHECK=PASS") && output.contains("FAKE_PY3"));
        assertFalse("不得因找不到 python 而 die（旧缺陷）", output.contains("CHECK=DIE"));
    }

    /**
     * 对照组：把 PYTHON 预置成旧默认值 `python`（等价于修复前的
     * `PYTHON="${PYTHON:-python}"`），在只有 python3 的环境里必须落到 CHECK=DIE。
     * 证明上一个用例不是空转 —— 解析逻辑一旦退回硬编码 python 就会失败。
     * 预置值写成脚本内的变量赋值（WSL bash 收不到 Windows 侧环境变量）。
     */
    @Test
    public void oldHardcodedPythonDefaultWouldDie() throws Exception {
        String bash = findBash();
        Assume.assumeTrue("未找到可执行的 bash，跳过行为验证", bash != null);

        String output = runBash(bash, "PYTHON=python\n" + probeScript());
        Assume.assumeFalse("该 bash 不支持 mktemp，跳过行为验证:\n" + output,
                output.contains("NO_MKTEMP"));

        assertTrue("旧写法 PYTHON=python 在只有 python3 的环境必须失败（非空转对照），实际输出:\n"
                + output, output.contains("CHECK=DIE"));
    }

    /**
     * 与 check-mounts.sh:259-261 同一段解析逻辑：PATH 里只放一个可执行的假 python3。
     * 只依赖 POSIX 内置命令与 /bin/sh shebang，避免 Windows 路径与 mktemp 之外的依赖。
     */
    private static String probeScript() {
        return "BIN=$(mktemp -d 2>/dev/null) || BIN=\"\"\n"
                + "if [ -z \"$BIN\" ]; then echo NO_MKTEMP; exit 3; fi\n"
                + "printf '#!/bin/sh\\nprintf \"FAKE_PY3\\\\n\"\\n' > \"$BIN/python3\"\n"
                + "chmod +x \"$BIN/python3\" 2>/dev/null\n"
                + "PATH=\"$BIN\"\n"
                + "export PATH\n"
                + "PYTHON=\"${PYTHON:-$(command -v python3 || command -v python || true)}\"\n"
                + "printf 'RESOLVED=%s\\n' \"$PYTHON\"\n"
                + "if [ -n \"$PYTHON\" ] && command -v \"$PYTHON\" >/dev/null 2>&1; then\n"
                + "    echo CHECK=PASS\n"
                + "    \"$PYTHON\"\n"
                + "else\n"
                + "    echo CHECK=DIE\n"
                + "fi\n";
    }

    /** 经 stdin 喂给 `bash -s`：不传文件路径，MSYS/WSL 都能执行。 */
    private static String runBash(String bash, String script) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(bash, "-s");
        builder.environment().remove("PYTHON");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(script.getBytes(StandardCharsets.UTF_8));
        }
        String output = readAll(process.getInputStream());
        process.waitFor();
        return output;
    }

    private static String readAll(InputStream stream) throws Exception {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                text.append(buffer, 0, read);
            }
        }
        return text.toString();
    }

    /** 优先挑 PATH 里非 System32 的 bash（Git bash），再回落到系统 bash（含 WSL）。 */
    private static String findBash() {
        List<String> candidates = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(File.pathSeparator)) {
                if (entry.toLowerCase().contains("system32")) {
                    continue;
                }
                File dir = new File(entry);
                File direct = new File(dir, "bash.exe");
                if (direct.isFile()) {
                    candidates.add(direct.getAbsolutePath());
                }
                File parent = dir.getParentFile();
                if (parent != null) {
                    File inBin = new File(new File(parent, "bin"), "bash.exe");
                    if (inBin.isFile()) {
                        candidates.add(inBin.getAbsolutePath());
                    }
                }
            }
        }
        candidates.add("bash");
        for (String candidate : candidates) {
            try {
                Process process = new ProcessBuilder(candidate, "-c", "printf ok")
                        .redirectErrorStream(true).start();
                String banner = readAll(process.getInputStream());
                if (process.waitFor() == 0 && banner.contains("ok")) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // 尝试下一个候选
            }
        }
        return null;
    }
}
