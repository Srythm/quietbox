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
 * 与 {@code HookKit.findMethodQuiet(Class, String, Class...)} 都不带
 * module/Feature，失败只返回 null，不 WARN、不写 HookState。这类无上下文重载的
 * 调用点必须自己兜底：把结果交给带 Feature 的
 * {@code HookKit.findMethodQuiet(module, f, cls, name, ...)}（由 HookKit 统一记
 * skip），或由调用点自己 {@code HookKit.warn} + {@code HookState.markNotFound}。
 * 本文件用 {@link #NO_SILENT_METHOD_LOOKUP} 锁住这条约定：除了已记账的
 * {@code findMethodQuiet(module, f, cls, name, ...)} 重载与 PV-01 交给
 * {@code installForDiscovery} 的发现重载，新增静默调用点一律失败；
 * ScreenShotHooks.java:94/:116 两处 onChange 定位是待补诊断记录的存量缺口，
 * 由该文件的属主修复，见 silentMethodLookupIsGatedAndKnownGapsArePinned。
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

    /**
     * 无 module/Feature 上下文的静默方法定位：{@code HookKit.findMethodQuiet(Class, String,
     * Class...)} 失败时既不 WARN 也不写 HookState，调用点必须自己兜底：
     * 换成带 Feature 的 {@code findMethodQuiet(module, f, cls, name, ...)}（HookKit
     * 统一记 skip），或在失败分支 {@code HookKit.warn} + {@code HookState.markNotFound}。
     *
     * <p>历史教训：ScreenShotHooks 曾在这类调用点直接
     * {@code return} / {@code return null}（:94 {@code noteLocateMiss}、:116
     * {@code locateOuter}），宿主把 {@code ContentObserver#onChange(boolean, Uri)}
     * 改名时 PV-01 定位整体静默失效，诊断页连 skip/critical 都没有。
     */
    private static final SourceScan.Rule NO_SILENT_METHOD_LOOKUP = new SourceScan.Rule(
            "\\bHookKit\\.findMethodQuiet\\s*\\(",
            call -> isScopedQuietLookup(call) || isDiscoveryHandoff(call),
            "禁止直接调用静默的 HookKit.findMethodQuiet(Class, String, Class...)；"
                    + "请改用带 Feature 的 HookKit.findMethodQuiet(module, f, cls, name, ...)"
                    + "或在失败分支 HookKit.warn + HookState.markNotFound");

    /**
     * 带 module/Feature 的记账重载：{@code (module, f, cls, name, ...)} 至少 5 个顶层
     * 实参，第 1 参是 module 变量（不是 {@code X.class}）、第 2 参是 Feature 变量
     * （不是字符串字面量）。旧的无上下文重载第 1 参是 Class、第 2 参是方法名字面量，
     * 两者都不满足。失败由 HookKit 统一记 skip。
     */
    private static boolean isScopedQuietLookup(String call) {
        List<String> args = topLevelArgs(call);
        if (args.size() < 5) {
            return false;
        }
        String first = args.get(0).trim();
        String second = args.get(1).trim();
        return !first.endsWith(".class") && !second.startsWith("\"");
    }

    /** PV-01 发现重载：ScreenShotHooks 把结果交给 installForDiscovery 记账。 */
    private static boolean isDiscoveryHandoff(String call) {
        return call.contains("\"registerContentObserver\"")
                && call.contains("Uri.class") && call.contains("int.class");
    }

    /** 顶层逗号切分调用片段的参数，足够区分 scoped 重载与旧的无上下文重载。 */
    private static List<String> topLevelArgs(String call) {
        List<String> args = new java.util.ArrayList<>();
        int open = call.indexOf('(');
        if (open < 0) {
            return args;
        }
        int depth = 0;
        boolean inString = false;
        int start = open + 1;
        for (int i = open + 1; i < call.length(); i++) {
            char c = call.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (c == ')' && depth == 0) {
                    addArg(args, call.substring(start, i));
                    return args;
                }
            } else if (c == ',' && depth == 0) {
                addArg(args, call.substring(start, i));
                start = i + 1;
            }
        }
        return args;
    }

    private static void addArg(List<String> args, String raw) {
        String arg = raw.trim();
        if (!arg.isEmpty()) {
            args.add(arg);
        }
    }

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

    /**
     * 门禁必须抓住「无上下文 findMethodQuiet 且失败分支直接 return」的静默路径，
     * 同时不误报已记账重载与 PV-01 发现重载；ScreenShotHooks 的 :94/:116 两处
     * onChange 定位是待补诊断记录的存量缺口（该文件归别的簇属主，不能在本簇改），
     * 在此钉住：只允许这两条，属主补上 HookKit.warn + HookState.markNotFound 后
     * 自然变为 0 条、本用例仍通过；任何第三处静默定位都会让门禁失败。
     */
    @Test
    public void silentMethodLookupIsGatedAndKnownGapsArePinned() throws IOException {
        List<SourceScan.Finding> findings = SourceScan.scan(
                hooksSource(), Collections.singletonList(NO_SILENT_METHOD_LOOKUP));

        for (SourceScan.Finding finding : findings) {
            assertTrue("只允许 ScreenShotHooks :94/:116 的 onChange 静默定位存量缺口"
                            + "（属主补诊断记录后应消失）；新增静默定位必须记账：" + finding,
                    "ScreenShotHooks.java".equals(finding.file)
                            && finding.snippet.contains("\"onChange\""));
        }
        assertTrue("门禁不得放过 3 处以上静默定位：" + findings, findings.size() <= 2);
    }

    /** 规则本身必须能命中旧写法，避免以后把正则改坏后门禁静默通过。 */
    @Test
    public void silentMethodLookupRuleCatchesLegacyCall() {
        String source = "class A { void a() { if (HookKit.findMethodQuiet("
                + "cls, \"onChange\", boolean.class, Uri.class) == null) return; } }";

        List<SourceScan.Finding> findings = SourceScan.scanSource(
                source, "A.java", NO_SILENT_METHOD_LOOKUP);

        assertEquals("旧的无上下文重载必须被门禁抓住", 1, findings.size());
    }

    /** 带 Feature/label 的记账重载不能被误报（失败由 HookKit 统一记 skip）。 */
    @Test
    public void silentMethodLookupRuleAllowsScopedOverload() {
        String source = "class A { void a() { HookKit.findMethodQuiet("
                + "module, f, cls, \"onChange\", boolean.class, Uri.class); } }";

        List<SourceScan.Finding> findings = SourceScan.scanSource(
                source, "A.java", NO_SILENT_METHOD_LOOKUP);

        assertTrue("记账重载不应被误报：" + findings, findings.isEmpty());
    }

    /** 旧写法即使参数变多（方法名用变量）也不能被当成 scoped 重载放行。 */
    @Test
    public void silentMethodLookupRuleCatchesLegacyCallWithVariableName() {
        String source = "class A { void a() { HookKit.findMethodQuiet("
                + "cls, methodName, boolean.class, Uri.class, int.class); } }";

        List<SourceScan.Finding> findings = SourceScan.scanSource(
                source, "A.java", NO_SILENT_METHOD_LOOKUP);

        assertEquals("第 1 参是 Class 的旧重载必须被门禁抓住", 1, findings.size());
    }

    /** PV-01 发现重载把结果交给 installForDiscovery，允许沿用静默定位。 */
    @Test
    public void silentMethodLookupRuleAllowsDiscoveryHandoff() {
        String source = "class A { void a() { Method four = HookKit.findMethodQuiet("
                + "resolver, \"registerContentObserver\", Uri.class, boolean.class, "
                + "ContentObserver.class, int.class); } }";

        List<SourceScan.Finding> findings = SourceScan.scanSource(
                source, "A.java", NO_SILENT_METHOD_LOOKUP);

        assertTrue("发现重载不应被误报：" + findings, findings.isEmpty());
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
