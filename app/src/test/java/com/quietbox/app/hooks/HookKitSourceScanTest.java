package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.quietbox.app.TestPaths;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import io.github.yigenhuobah.core.test.SourceScan;

/**
 * 源码级约束：hook 包的“静默失败”必须被显式记录，崩溃路径必须真的同步 flush。
 *
 * <p>锚点查找存在等价静默规则：{@code HookKit.findByNameQuiet(Class, String)}
 * 不带 module/Feature，也不记 skip；其它 quiet 重载虽然也不记账，但调用点都把
 * 结果交给 {@code HookKit.install}，由 install 统一记“挂载点为空”并返回 false，
 * 因此这里只锁住没有兜底记录的 {@code findByNameQuiet}。
 *
 * <p>字段查找没有等价的静默 helper：{@code getDeclaredField} 的各调用点要么在
 * 失败分支调用 {@code HookState.markNotFound}（FeedAdHooks/FeedCardHooks/
 * LivePhotoWatermarkHooks），要么有明确的 getter 回退路径（NoteUnavailableHooks），
 * 无法用一条统一的正则在不误报回退逻辑的前提下表达，故不加字段规则。
 */
public class HookKitSourceScanTest {

    private static final SourceScan.Rule NO_EXTERNAL_SILENT_NAME_LOOKUP = new SourceScan.Rule(
            "\\bHookKit\\.findByNameQuiet\\s*\\(",
            "禁止直接调用静默的 HookKit.findByNameQuiet；请用带 Feature 的 "
                    + "HookKit.findMethod(module, f, cls, name) 以便记录 skip");

    private static final SourceScan.Rule CRASH_FLUSH = new SourceScan.Rule(
            "\\bCrashFlush\\.runBestEffort\\s*\\(",
            call -> call.contains("Report.flushNow") && call.contains("200")
                    && call.contains("CRASH_LOGGER") && call.contains("TAG"),
            "UEH 崩溃路径必须调用 CrashFlush.runBestEffort(() -> Report.flushNow(), 200, "
                    + "logger, TAG)，让超时/异常以 WARN 可见");

    /** 旧的不记日志入口：崩溃 flush 超时或任务抛异常时完全静默，必须禁止回退。 */
    private static final SourceScan.Rule NO_SILENT_CRASH_FLUSH = new SourceScan.Rule(
            "\\bCrashFlush\\.run\\s*\\(",
            "崩溃路径不得使用不记日志的 CrashFlush.run；请用 "
                    + "runBestEffort(..., CRASH_LOGGER, TAG)");

    /** DESIGN §14 第 1 条：Hooker 一律匿名内部类，lambda 会在 bootclasspath desugar 下失效。 */
    private static final SourceScan.Rule NO_HOOKER_LAMBDA = new SourceScan.Rule(
            "\\bchain\\s*->",
            "Hooker 不得使用 lambda；请写成 new XposedInterface.Hooker() { ... }");

    /** 框架类挂载只允许 PV-01 的运行时发现入口使用。 */
    private static final SourceScan.Rule DISCOVERY_INSTALL_CALLSITE = new SourceScan.Rule(
            "\\bHookKit\\.installForDiscovery\\s*\\(",
            "只有 ScreenShotHooks 可以使用框架类发现入口");

    /**
     * DESIGN §14 第 4 条：hook id 用 {@code <ID>#<label>}，升级后保持稳定。
     * 混淆类名（如 {@code h8b.e#b}）每版都会变，禁止再作为 install 的 label
     * 字面量出现，避免适配时批量改名导致诊断/日志 id 漂移（F-062）。
     *
     * <p>正则命中的是调用点本身；masked 代码里字符串被遮罩，所以判定放在
     * {@code allowed} 对原始完整调用片段上执行（SourceScan 传给 allowed 的是
     * 未遮罩的原始源码片段），检查第 3 参 label 里是否含短混淆类名。
     */
    private static final SourceScan.Rule NO_OBFUSCATED_HOOK_LABEL = new SourceScan.Rule(
            "\\bHookKit\\.install\\s*\\(",
            call -> !containsObfuscatedHookLabel(call),
            "hook id 的 label 不得内嵌混淆类名；请改用语义稳定名"
                    + "（DESIGN §14 第 4 条）");

    /** 形如 "h8b.e#b(...)" / "bt9.j#c(...)" 的短混淆类名 label。只检查第 3 参。 */
    private static boolean containsObfuscatedHookLabel(String call) {
        String label = thirdArgumentLabel(call);
        return label != null && java.util.regex.Pattern.compile(
                "^[a-z0-9]{1,4}\\.[a-z0-9]{1,4}[#/(]")
                .matcher(label).find();
    }

    /**
     * 取 install(module, f, "...", ...) 的第 3 参字符串内容；
     * 拼接型 label（"code=" + CONST）不在本规则管辖范围，返回 null。
     * 只做顶层逗号切分，够覆盖本仓的全部 install 调用。
     */
    private static String thirdArgumentLabel(String call) {
        int open = call.indexOf('(');
        if (open < 0) {
            return null;
        }
        int start = open + 1;
        int argIndex = 0;
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < call.length(); i++) {
            char c = call.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                    if (argIndex == 2) {
                        return call.substring(start, i);
                    }
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                if (argIndex == 2) {
                    start = i + 1;
                }
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (c == ')' && depth == 0) {
                    return null;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                argIndex++;
                if (argIndex > 2) {
                    return null;
                }
            }
        }
        return null;
    }

    @Test
    public void crashHandlerFlushesSynchronouslyWithinBudget() throws IOException {
        List<SourceScan.Finding> findings = SourceScan.scan(hooksSource(), CRASH_FLUSH);

        assertTrue("崩溃路径必须使用 CrashFlush.runBestEffort(...Report.flushNow()..., 200, "
                + "CRASH_LOGGER, TAG)：" + findings, findings.isEmpty());
        // 反向确认规则不是空转：至少存在一条 runBestEffort 调用。
        assertFalse("Report.java 必须实际调用 CrashFlush.runBestEffort",
                SourceScan.scan(hooksSource(),
                        new SourceScan.Rule("\\bCrashFlush\\.runBestEffort\\s*\\(",
                                "崩溃 flush 缺失"))
                        .isEmpty());
    }

    @Test
    public void crashHandlerDoesNotUseSilentFlushEntryPoint() throws IOException {
        List<SourceScan.Finding> findings = SourceScan.scan(
                hooksSource(), Collections.singletonList(NO_SILENT_CRASH_FLUSH));

        assertTrue("崩溃路径不得回退到静默的 CrashFlush.run：" + findings,
                findings.isEmpty());
    }

    /** 规则本身必须能命中旧写法，避免以后把正则改坏后门禁静默通过。 */
    @Test
    public void silentCrashFlushRuleCatchesLegacyCall() {
        String source = "class A { void a() { CrashFlush.run(() -> Report.flushNow(), 200); } }";

        List<SourceScan.Finding> findings = SourceScan.scanSource(
                source, "A.java", NO_SILENT_CRASH_FLUSH);

        assertEquals(1, findings.size());
    }

    @Test
    public void silentNameLookupIsNotUsedOutsideHookKit() throws IOException {
        List<SourceScan.Finding> findings = SourceScan.scan(
                hooksSource(), Collections.singletonList(NO_EXTERNAL_SILENT_NAME_LOOKUP));

        assertTrue("hook 包不得直接调用静默的 findByNameQuiet：" + findings,
                findings.isEmpty());
    }

    /** 规则本身必须能命中旧写法，避免以后把正则改坏后门禁静默通过。 */
    @Test
    public void silentNameLookupRuleCatchesLegacyCall() {
        String source = "class A { void a() { HookKit.findByNameQuiet(cls, \"m\"); } }";

        List<SourceScan.Finding> findings = SourceScan.scanSource(
                source, "A.java", NO_EXTERNAL_SILENT_NAME_LOOKUP);

        assertEquals(1, findings.size());
    }

    @Test
    public void hookersAreAnonymousClassesNotLambdas() throws IOException {
        List<SourceScan.Finding> findings = SourceScan.scan(
                hooksSource(), Collections.singletonList(NO_HOOKER_LAMBDA));

        assertTrue("Hooker 不得使用 lambda（DESIGN §14 第 1 条）：" + findings,
                findings.isEmpty());
    }

    @Test
    public void hookerLambdaRuleCatchesLegacyStyle() {
        String source = "class A { void a() { HookKit.install(m, f, \"x\", method, chain -> {"
                + " return chain.proceed(); }); } }";

        List<SourceScan.Finding> findings = SourceScan.scanSource(
                source, "A.java", NO_HOOKER_LAMBDA);

        assertEquals(1, findings.size());
    }

    /** F-062：hook id 的 label 必须是语义稳定名，不内嵌每版都变的混淆类名。 */
    @Test
    public void hookIdsUseStableSemanticLabels() throws IOException {
        List<SourceScan.Finding> findings = SourceScan.scan(
                hooksSource(), Collections.singletonList(NO_OBFUSCATED_HOOK_LABEL));

        assertTrue("hook id 的 label 不得内嵌混淆类名（DESIGN §14 第 4 条）：" + findings,
                findings.isEmpty());
    }

    /** 规则本身必须能命中旧写法，避免改坏正则后门禁静默通过。 */
    @Test
    public void obfuscatedLabelRuleCatchesLegacyStyle() {
        String source = "class A { void a() { HookKit.install(module, f, "
                + "\"h8b.e#b(setPrimaryClip)\", m, hooker); } }";

        List<SourceScan.Finding> findings = SourceScan.scanSource(
                source, "A.java", NO_OBFUSCATED_HOOK_LABEL);

        assertEquals(1, findings.size());
    }

    @Test
    public void frameworkDiscoveryInstallOnlyUsedByScreenshotHooks() throws IOException {
        List<SourceScan.Finding> findings = SourceScan.scan(
                hooksSource(), Collections.singletonList(DISCOVERY_INSTALL_CALLSITE));

        for (SourceScan.Finding finding : findings) {
            assertEquals("框架类挂载例外只允许 ScreenShotHooks 使用：" + finding,
                    "ScreenShotHooks.java", finding.file);
        }
        assertFalse("必须至少存在 PV-01 的发现入口调用", findings.isEmpty());
    }

    /** 测试从 app/、project/、QuietBox/ 或 D:\modules 运行都能定位 hook 源码。 */
    private static Path hooksSource() {
        return TestPaths.resolveFromCwd(
                "src/main/java/com/quietbox/app/hooks",
                "app/src/main/java/com/quietbox/app/hooks",
                "project/app/src/main/java/com/quietbox/app/hooks",
                "QuietBox/project/app/src/main/java/com/quietbox/app/hooks");
    }
}
