package com.quietbox.app.anchor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Test;

import com.quietbox.app.TestPaths;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 锚点框架类黑名单契约（纯 JVM）：android./java./dalvik./androidx./kotlin. 开头的
 * 目标类不得进入 anchors.json，也不得被 AnchorTable 运行时解析。
 *
 * <p>覆盖三层中的两层：anchors.json 单一来源（生成结果里没有框架类）与
 * AnchorTable 运行时（前缀判断 + 失败类别）；第三层 tools/gen_anchors.py 由
 * {@link #generatorRejectsFrameworkTargets()} 直接调用其判定函数验证。
 */
public class AnchorFrameworkBlacklistTest {

    private static final String[] FRAMEWORK_PREFIXES = {
            "android.", "java.", "dalvik.", "androidx.", "kotlin.",
    };

    @Test
    public void frameworkPrefixesAreRejected() {
        for (String prefix : FRAMEWORK_PREFIXES) {
            assertTrue("框架类前缀必须被拒绝：" + prefix,
                    AnchorTable.isFrameworkClass(prefix + "SomeClass"));
        }
        assertTrue("android.app.Activity 必须拒绝",
                AnchorTable.isFrameworkClass("android.app.Activity"));
        assertTrue("androidx.activity.ComponentActivity 必须拒绝",
                AnchorTable.isFrameworkClass("androidx.activity.ComponentActivity"));
    }

    @Test
    public void hostClassesAndLookalikesAreAllowed() {
        assertFalse("宿主类不能被误判",
                AnchorTable.isFrameworkClass("com.xingin.advert.spi.AdsIndexSpiImp"));
        assertFalse("混名列不能被误判", AnchorTable.isFrameworkClass("hgd.m0"));
        assertFalse("null 不能崩溃", AnchorTable.isFrameworkClass(null));
        assertFalse("android 前缀必须带点，androidx 之外的 androidFoo 不误伤",
                AnchorTable.isFrameworkClass("androidfoo.Bar"));
        assertFalse("javax 与 java. 是不同的包",
                AnchorTable.isFrameworkClass("javax.crypto.Cipher"));
    }

    @Test
    public void frameworkFailureLabelIsEnglishLogcatToken() {
        assertEquals("失败类别必须是可 grep 的英文 token",
                "framework class", AnchorTable.Failure.FRAMEWORK_CLASS.label);
        String message = AnchorTable.failureMessage("some_anchor",
                AnchorTable.Failure.FRAMEWORK_CLASS, "android.app.Activity", null);
        assertTrue("WARN 文案必须带锚点 key: " + message, message.contains("some_anchor"));
        assertTrue("WARN 文案必须带框架类名: " + message,
                message.contains("android.app.Activity"));
        assertTrue("WARN 文案必须是可打印 ASCII: " + message,
                message.matches("[\\x20-\\x7E]+"));
    }

    /** anchors.json 是单一来源：生成结果里不得出现框架类目标。 */
    @Test
    public void anchorsTableHasNoFrameworkTargets() throws Exception {
        Map<String, Object> json = loadAnchors();
        Map<String, Object> anchors = object(json.get("anchors"));
        for (Map.Entry<String, Object> item : anchors.entrySet()) {
            Object className = object(item.getValue()).get("c");
            assertTrue("锚点 " + item.getKey() + " 缺少 c 字段（或不是 String）",
                    className instanceof String);
            assertFalse("锚点 " + item.getKey() + " 指向框架类：" + className,
                    AnchorTable.isFrameworkClass((String) className));
        }
        Map<String, Object> classes = object(json.get("classes"));
        for (Map.Entry<String, Object> item : classes.entrySet()) {
            Object className = item.getValue();
            assertTrue("类锚点 " + item.getKey() + " 的类名必须是 String",
                    className instanceof String);
            assertFalse("类锚点 " + item.getKey() + " 指向框架类：" + className,
                    AnchorTable.isFrameworkClass((String) className));
        }
    }

    /**
     * 生成器必须对锚点表与 classes 两段都做框架类拒绝；直接导入 gen_anchors.py
     * 调用判定函数，避免只靠源码文本断言。
     */
    @Test
    public void generatorRejectsFrameworkTargets() throws Exception {
        File script = findRepoFile("tools/gen_anchors.py");
        String source = read(script);
        assertTrue("生成器必须定义框架类前缀名单",
                source.contains("FRAMEWORK_CLASS_PREFIXES"));
        // 去掉参数名计数：只重命名循环变量（如 cls -> target）不应让守卫假失败（F397）。
        int guards = countCalls(source, "is_framework_class(");
        assertTrue("生成器必须在 anchors 与 classes 两段都调用 is_framework_class（实际 "
                        + guards + " 处调用）",
                guards >= 2);

        String python = findPython();
        Assume.assumeTrue("未找到 python，跳过生成器判定函数的行为验证", python != null);
        ProcessBuilder builder = new ProcessBuilder(python, "-I", "-B", "-c",
                "import sys; sys.path.insert(0, sys.argv[1]); import gen_anchors as g;"
                        + "assert g.is_framework_class('android.content.Context');"
                        + "assert g.is_framework_class('java.lang.String');"
                        + "assert g.is_framework_class('dalvik.system.DexFile');"
                        + "assert g.is_framework_class('androidx.activity.ComponentActivity');"
                        + "assert g.is_framework_class('kotlin.Unit');"
                        + "assert not g.is_framework_class('com.xingin.xhs.Foo');"
                        + "assert not g.is_framework_class('javax.crypto.Cipher');"
                        + "print('OK')",
                script.getParentFile().getAbsolutePath());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = readAll(process.getInputStream());
        // 子进程不退出时不得让 JVM 单测永久阻塞（F395）。
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("生成器判定函数 60s 未退出，已强制终止：" + output);
        }
        assertEquals("生成器判定函数行为不符：" + output, 0, process.exitValue());
    }

    private static String findPython() {
        for (String candidate : new String[]{"python3", "python"}) {
            Process process = null;
            try {
                process = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true).start();
                if (process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // 尝试下一个解释器名
            } finally {
                if (process != null) {
                    process.destroyForcibly();
                }
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        assertTrue("期望 JSON 对象，实际是 " + value, value instanceof Map);
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> loadAnchors() throws Exception {
        InputStream stream = AnchorFrameworkBlacklistTest.class.getClassLoader()
                .getResourceAsStream("anchors.json");
        assertNotNull("anchors.json 必须在测试 classpath 上", stream);
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                text.append(buffer, 0, read);
            }
        }
        Object parsed = new AnchorContractTest.JsonParser(text.toString()).parse();
        return object(parsed);
    }

    /**
     * 与 QB AnchorContractTest.mainSourceRoot() 一致：cwd 可能是 app/（默认）、
     * project/、QuietBox/ 或 D:\modules，逐级向上尝试显式候选，避免单一相对路径
     * 在非默认 cwd 下抛 AssertionError 把生成器契约变成假失败。
     */
    private static File findRepoFile(String relative) {
        return TestPaths.resolveFromCwd(
                relative,
                "project/" + relative,
                "QuietBox/project/" + relative).toFile();
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static int count(String text, String needle) {
        int total = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            total++;
            index = text.indexOf(needle, index + needle.length());
        }
        return total;
    }

    /** 统计调用次数：跳过 def 定义行，避免把函数定义本身算作一处守卫。 */
    private static int countCalls(String text, String needle) {
        int total = 0;
        for (String line : text.split("\n")) {
            if (line.trim().startsWith("def ")) {
                continue;
            }
            total += count(line, needle);
        }
        return total;
    }
}
