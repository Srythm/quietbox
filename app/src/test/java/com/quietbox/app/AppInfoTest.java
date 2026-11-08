package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.yigenhuobah.core.test.SourceScan;

/**
 * 包级 AppInfo 工具类的去重契约（F-054）：版本查询方法与目标包名只允许一处定义，
 * 四个页面/Provider/Module 都改用 AppInfo，避免适配升级时漏改与格式漂移。
 */
public class AppInfoTest {

    private static Path mainJava() {
        return TestPaths.resolveFromCwd(
                "src/main/java",
                "app/src/main/java",
                "project/app/src/main/java",
                "QuietBox/project/app/src/main/java");
    }

    /** versionName() 私有复制：曾有 4 份逐字相同，现在只允许 Config 之外的 0 份。 */
    private static final SourceScan.Rule NO_PRIVATE_VERSION_NAME = new SourceScan.Rule(
            "private\\s+String\\s+versionName\\s*\\(",
            "versionName() 必须统一走 AppInfo，不能在页面里复制");

    /** 目标包名硬编码：只允许 AppInfo 一处（其余文件必须引用 AppInfo.TARGET_PACKAGE）。 */
    private static final String TARGET_PACKAGE_LITERAL = "\"com\\.xingin\\.xhs\"";

    @Test
    public void targetPackageIsDefinedOnlyInAppInfo() throws IOException {
        Pattern literal = Pattern.compile(TARGET_PACKAGE_LITERAL);
        int hits = 0;
        StringBuilder where = new StringBuilder();
        try (java.util.stream.Stream<Path> stream = Files.walk(mainJava())) {
            for (Path p : (Iterable<Path>) stream
                    .filter(x -> x.toString().endsWith(".java"))::iterator) {
                String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                Matcher m = literal.matcher(text);
                while (m.find()) {
                    hits++;
                    where.append(p.getFileName()).append(' ');
                }
            }
        }
        assertEquals("com.xingin.xhs 只允许在 AppInfo 一处硬编码，实际出现于：" + where,
                1, hits);
    }

    @Test
    public void appInfoExposesTheSingleTargetPackageConstant() {
        assertEquals("目标包名必须与适配说明一致",
                "com.xingin.xhs", AppInfo.TARGET_PACKAGE);
    }

    @Test
    public void noPageDefinesItsOwnVersionNameMethod() throws IOException {
        List<SourceScan.Finding> findings = SourceScan.scan(
                mainJava(), NO_PRIVATE_VERSION_NAME);
        assertTrue("页面不得再复制 versionName()（改用 AppInfo.versionName）：" + findings,
                findings.isEmpty());
    }

    /** 规则本身必须能命中旧写法，避免正则改坏后门禁静默通过。 */
    @Test
    public void privateVersionNameRuleCatchesLegacyCopy() {
        String legacy = "class A { private String versionName() { return \"?\"; } }";
        assertEquals(1, SourceScan.scanSource(legacy, "A.java", NO_PRIVATE_VERSION_NAME).size());
    }

    /** F-057：导出/复制相关的用户可见中文文案必须全部进 strings.xml，Java 不写中文字面量。 */
    @Test
    public void diagnosticsChineseTextLivesInStringsXml() throws IOException {
        Path strings = TestPaths.resolveFromCwd(
                "src/main/res/values/strings.xml",
                "app/src/main/res/values/strings.xml",
                "project/app/src/main/res/values/strings.xml",
                "QuietBox/project/app/src/main/res/values/strings.xml");
        String resources = new String(Files.readAllBytes(strings), StandardCharsets.UTF_8);
        for (String name : new String[]{
                "diag_export_title", "diag_export_summary", "diag_export_failed",
                "diag_crash_category", "diag_copy_crash_header", "diag_export_system",
                "diag_export_framework", "diag_export_target", "diag_framework_unbound",
                "diag_target_not_installed", "diag_export_section_features",
                "diag_export_section_hits", "diag_export_section_crashes"}) {
            assertTrue("strings.xml 缺少导出文案资源：" + name,
                    resources.contains("name=\"" + name + "\""));
        }

        Path diag = mainJava().resolve("com/quietbox/app/DiagnosticsFragment.java");
        String source = new String(Files.readAllBytes(diag), StandardCharsets.UTF_8);
        // 中文 UI 字面量（导出标题/失败 Toast/分段名等）不得再出现在 Java 里。
        for (String banned : new String[]{
                "导出诊断包\"", "诊断包导出失败\"", "最近崩溃:\"",
                "\"\\n[功能状态]\\n\"", "\"\\n[最近命中]\\n\"", "\"\\n[最近崩溃]\\n\""}) {
            assertFalse("DiagnosticsFragment 不得硬编码用户可见中文文案：" + banned,
                    source.contains(banned));
        }
        assertTrue("段名必须由 strings.xml 资源传入 joinExportText",
                source.contains("R.string.diag_export_section_features"));
    }
}
