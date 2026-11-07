package com.quietbox.app.anchor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

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
            String className = (String) object(item.getValue()).get("c");
            assertFalse("锚点 " + item.getKey() + " 指向框架类：" + className,
                    AnchorTable.isFrameworkClass(className));
        }
        Map<String, Object> classes = object(json.get("classes"));
        for (Map.Entry<String, Object> item : classes.entrySet()) {
            String className = (String) item.getValue();
            assertFalse("类锚点 " + item.getKey() + " 指向框架类：" + className,
                    AnchorTable.isFrameworkClass(className));
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
        int guards = count(source, "is_framework_class(cls)");
        assertTrue("生成器必须在 anchors 与 classes 两段都拒绝框架类（实际 " + guards + " 处）",
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
        assertEquals("生成器判定函数行为不符：" + output, 0, process.waitFor());
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

    private static File findRepoFile(String relative) {
        File dir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, relative);
            if (candidate.isFile()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        throw new AssertionError("找不到 " + relative
                + "（cwd=" + System.getProperty("user.dir") + "）");
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
}
