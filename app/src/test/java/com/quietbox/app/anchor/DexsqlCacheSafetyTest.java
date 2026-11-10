package com.quietbox.app.anchor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

/**
 * DexSQL 缓存清理安全契约（纯 JVM）：缓存失效时只能删本工具生成的 cache.dxc2 与
 * apk.meta，不得 `shutil.rmtree(整个目录)`。
 *
 * <p>历史实现只要缓存不匹配就 rmtree 整个 cache 目录，而 --cache-dir 是命令行
 * 任意传入的：typo、相对路径解析到别处、CI 指向工作目录，都会被整目录递归删掉
 * （ignore_errors=True 还会把删除失败拖到 prepare-cache 才暴露）。同族的
 * CloudMusicTool/QuietMap 只删自己写的单文件。本类把新约束固化成单测：
 * 1) 两个脚本里不再有 rmtree 调用；
 * 2) --cache-dir 必须落在仓库根内（resolve_cache_dir 前置拒绝）；
 * 3) 清理只按文件名删除，目录与其他内容原样保留（行为验证）。
 */
public class DexsqlCacheSafetyTest {

    private static final String GENERATOR_RELATIVE = "tools/gen_anchors.py";
    private static final String CHECK_MOUNTS_RELATIVE = "tools/check-mounts.sh";

    /** 只匹配真正的调用语句（shutil.rmtree(...)），不匹配注释/文档里的提及。 */
    private static final Pattern RMTREE_CALL =
            Pattern.compile("(?m)^\\s*shutil\\.rmtree\\(");

    private static final String PY_SNIPPET =
            "import sys, tempfile, pathlib\n"
            + "sys.path.insert(0, sys.argv[1])\n"
            + "import gen_anchors as g\n"
            + "tools = pathlib.Path(sys.argv[1])\n"
            + // 仓库内：原样解析成绝对路径
            "inside = g.resolve_cache_dir(str(tools / '.dexsql-cache'))\n"
            + "assert inside.is_absolute(), inside\n"
            // 仓库外：必须拒绝（typo / CI 工作目录 / 相对路径落到别处）
            + "try:\n"
            + "    g.resolve_cache_dir(tempfile.gettempdir())\n"
            + "    raise AssertionError('resolve_cache_dir 必须拒绝仓库外路径')\n"
            + "except SystemExit as exc:\n"
            + "    assert '仓库根' in str(exc), str(exc)\n"
            // 只删 cache.dxc2 / apk.meta：目录、其他文件、子目录一律保留
            + "d = pathlib.Path(tempfile.mkdtemp()) / 'cache'\n"
            + "d.mkdir()\n"
            + "(d / 'cache.dxc2').write_text('x')\n"
            + "(d / 'apk.meta').write_text('x')\n"
            + "(d / 'keep.txt').write_text('keep')\n"
            + "(d / 'sub').mkdir()\n"
            + "(d / 'sub' / 'inner.txt').write_text('keep')\n"
            + "g._purge_cache_files(d)\n"
            + "assert d.is_dir(), '缓存目录本身不得被删除'\n"
            + "assert (d / 'keep.txt').read_text() == 'keep', '目录内其他文件必须保留'\n"
            + "assert (d / 'sub' / 'inner.txt').read_text() == 'keep', '子目录内容必须保留'\n"
            + "assert not (d / 'cache.dxc2').exists(), 'cache.dxc2 必须被删除'\n"
            + "assert not (d / 'apk.meta').exists(), 'apk.meta 必须被删除'\n"
            // 缓存文件本来就不存在时不能炸
            + "empty = pathlib.Path(tempfile.mkdtemp()) / 'empty'\n"
            + "empty.mkdir()\n"
            + "g._purge_cache_files(empty)\n"
            + "assert empty.is_dir()\n"
            + "print('OK')\n";

    @Test
    public void noWholeDirectoryRemovalInGenerator() throws Exception {
        String source = read(findRepoFile(GENERATOR_RELATIVE));
        assertFalse("gen_anchors.py 不得再 rmtree 整个缓存目录（误传 --cache-dir 即误删）",
                RMTREE_CALL.matcher(source).find());
        assertTrue("必须定义本工具生成的缓存文件名清单",
                source.contains("CACHE_FILES = ("));
        assertTrue("必须按文件名清理（_purge_cache_files）",
                source.contains("def _purge_cache_files"));
        assertTrue("清理前必须打印绝对路径（删除对象可追溯）",
                source.contains("removing stale cache file: %s"));
        assertTrue("--cache-dir 必须经 resolve_cache_dir 收敛到仓库根内",
                source.contains("def resolve_cache_dir"));
        assertTrue("--cache-dir 的 help 必须说明仓库根约束",
                source.contains("必须位于仓库根以内"));
    }

    @Test
    public void noWholeDirectoryRemovalInCheckMounts() throws Exception {
        String source = read(findRepoFile(CHECK_MOUNTS_RELATIVE));
        assertFalse("check-mounts.sh 的内嵌 Python 不得 rmtree 整个缓存目录",
                source.contains("rmtree"));
        assertTrue("必须只删 cache.dxc2 / apk.meta 两个缓存文件",
                source.contains("cache_file, meta_path"));
    }

    @Test
    public void generatorCacheHelpersOnlyDeleteCacheFiles() throws Exception {
        File script = findRepoFile(GENERATOR_RELATIVE);
        String python = findPython();
        Assume.assumeTrue("未找到 python，跳过缓存助手行为验证", python != null);
        ProcessBuilder builder = new ProcessBuilder(python, "-I", "-B", "-c",
                PY_SNIPPET, script.getParentFile().getAbsolutePath());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = readAll(process.getInputStream());
        int exitCode = process.waitFor();
        assertTrue("gen_anchors.py 缓存助手行为不符（exit=" + exitCode + "）：\n" + output,
                exitCode == 0);
        assertTrue("助手输出必须以 OK 结尾（前面只允许有删除日志）：\n" + output,
                output.trim().endsWith("OK"));
    }

    private static File findRepoFile(String relative) {
        File dir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, relative);
            if (candidate.isFile()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        fail("找不到 " + relative + "（cwd=" + System.getProperty("user.dir") + "）");
        return null;
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String findPython() {
        for (String candidate : new String[]{"python3", "python"}) {
            try {
                Process process = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true).start();
                if (process.waitFor() == 0) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // 尝试下一个解释器名
            }
        }
        return null;
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
}
