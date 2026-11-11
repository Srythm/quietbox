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

    /**
     * 不限定的方法名匹配：同包调用可以省略 {@code HookKit.}，只认限定名会让
     * {@code findByNameQuiet(cls, name)} 这类调用静默绕过门禁。属主类
     * HookKit.java 内部的声明/自调用由用例按 file 豁免。
     */
    private static final SourceScan.Rule NO_EXTERNAL_SILENT_NAME_LOOKUP = new SourceScan.Rule(
            "\\bfindByNameQuiet\\s*\\(",
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
            "\\bfindMethodQuiet\\s*\\(",
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
        // 记账重载的约定形状是 (module, f, cls, name, ...)：第 2 参必须是 Feature 变量。
        // 只排除 ".class"/字符串字面量不够——旧重载 (cls, methodName, ...) 也能蒙混过关。
        boolean featureArg = "f".equals(second) || "feature".equals(second)
                || second.startsWith("Feature.");
        return !first.endsWith(".class") && !second.startsWith("\"") && featureArg;
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
                // depth 从调用自身 '(' 之后开始计，配对的右括号应落到 -1；
                // 若判 == 0，会漏掉最后一个实参（且被 lambda 的 () 提前截断）。
                if (c == ')' && depth < 0) {
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
            HookKitSourceScanTest::isCrashFlushCall,
            "UEH 崩溃路径必须调用 CrashFlush.runBestEffort(() -> Report.flushNow(), 200, "
                    + "logger, TAG)，让超时/异常以 WARN 可见");

    /**
     * 按实参位置校验崩溃 flush：任务体必须调 Report.flushNow，第二个实参（timeoutMs）
     * 必须是字面量 200，后两个实参分别是 CRASH_LOGGER 与 TAG。只看“文本里出现过 200”
     * 会把其它位置的数字误判成预算。
     */
    private static boolean isCrashFlushCall(String call) {
        List<String> args = topLevelArgs(call);
        if (args.size() < 4) {
            return false;
        }
        return args.get(0).contains("Report.flushNow")
                && "200".equals(args.get(1).trim())
                && args.get(2).contains("CRASH_LOGGER")
                && args.get(3).contains("TAG");
    }

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

    /**
     * F-120：install 的第 3 参 label 必须是字符串字面量。旧规则只认字面量第 3 参，
     * {@link #thirdArgumentLabel} 对非字面量返回 null 直接放行，于是 ScreenShotHooks
     * 用运行时混淆类名（{@code outer.getName()}）当 label 就静默漏过，规则自己的目标
     * （F-062 id 稳定）没被强制。这里把「第 3 参非字面量」的调用点一律报告，由
     * {@code dynamicHookLabelsAreExplicitlyPinned} 按 (file, 表达式) 显式登记语义稳定的
     * 例外；新增未登记的非字面量 label 一律让门禁失败。
     */
    private static final SourceScan.Rule NO_DYNAMIC_HOOK_LABEL = new SourceScan.Rule(
            "\\bHookKit\\.install\\s*\\(",
            call -> thirdArgumentIsStringLiteral(call),
            "install 的 label（第 3 参）必须是字符串字面量；变量/表达式 label"
                    + "需在动态 label 清单里显式登记理由（DESIGN §14 第 4 条）");

    /** 第 3 参是否为纯字符串字面量；拼接表达式（"x" + cond）视为非字面量。 */
    private static boolean thirdArgumentIsStringLiteral(String call) {
        List<String> args = topLevelArgs(call);
        if (args.size() < 3) {
            // 解析不到第 3 参时不误报，交给其它规则。
            return true;
        }
        String third = args.get(2).trim();
        // 不能只看首尾引号：`"h8b.e#b(x)" + ""` 同样以引号收尾，但它是拼接表达式。
        return isSingleStringLiteral(third);
    }

    /** 取第 3 参的原始源码文本（用于与动态 label 清单比对）。 */
    private static String thirdArgumentRaw(String call) {
        List<String> args = topLevelArgs(call);
        return args.size() >= 3 ? args.get(2).trim() : "";
    }

    /** 形如 "h8b.e#b(...)" / "bt9.j#c(...)" 的短混淆类名 label。只检查第 3 参。 */
    private static boolean containsObfuscatedHookLabel(String call) {
        String label = thirdArgumentLabel(call);
        return label != null && java.util.regex.Pattern.compile(
                "^[a-z0-9]{1,4}\\.[a-z0-9]{1,4}[#/(]")
                .matcher(label).find();
    }

    /**
     * 取 install(module, f, "...", ...) 的第 3 参字符串内容；
     * 只有第 3 个实参整体就是一个字符串字面量时才返回其内容（不含引号）。
     * 拼接型/嵌套表达式（"code=" + CONST、foo("x")）返回 null，避免把第一个
     * 字面量误当成整个 label 而漏掉后半段。
     */
    private static String thirdArgumentLabel(String call) {
        List<String> args = topLevelArgs(call);
        if (args.size() < 3) {
            return null;
        }
        String third = args.get(2).trim();
        if (!isSingleStringLiteral(third)) {
            return null;
        }
        return third.substring(1, third.length() - 1);
    }

    /** 实参文本是否整体就是一个字符串字面量（拼接/嵌套表达式不算）。 */
    private static boolean isSingleStringLiteral(String arg) {
        if (arg.length() < 2 || arg.charAt(0) != '"') {
            return false;
        }
        for (int i = 1; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i == arg.length() - 1;
            }
        }
        return false;
    }

    @Test
    public void crashHandlerFlushesSynchronouslyWithinBudget() throws IOException {
        // 崩溃路径只在 Report.java：按 file 过滤后只校验这一处，避免将来别处新增
        // 合法调用点因参数形状不同被误报，失败信息也直接指向真正违约的文件。
        List<SourceScan.Finding> reportFindings = inReport(
                SourceScan.scan(hooksSource(), CRASH_FLUSH));

        assertTrue("崩溃路径必须使用 CrashFlush.runBestEffort(...Report.flushNow()..., 200, "
                + "CRASH_LOGGER, TAG)：" + reportFindings, reportFindings.isEmpty());
        // 反向确认规则不是空转：Report.java 必须实际存在 runBestEffort 调用。
        assertFalse("Report.java 必须实际调用 CrashFlush.runBestEffort",
                inReport(SourceScan.scan(hooksSource(),
                        new SourceScan.Rule("\\bCrashFlush\\.runBestEffort\\s*\\(",
                                "崩溃 flush 缺失")))
                        .isEmpty());
    }

    /** 只保留 Report.java 的命中；其它文件的调用点不属于本规则的崩溃路径。 */
    private static List<SourceScan.Finding> inReport(List<SourceScan.Finding> findings) {
        List<SourceScan.Finding> out = new java.util.ArrayList<>();
        for (SourceScan.Finding finding : findings) {
            if ("Report.java".equals(finding.file)) {
                out.add(finding);
            }
        }
        return out;
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

        for (SourceScan.Finding finding : findings) {
            assertEquals("静默 findByNameQuiet 只允许 HookKit 属主类内部使用：" + finding,
                    "HookKit.java", finding.file);
        }
    }

    /** 规则本身必须能命中旧写法，避免以后把正则改坏后门禁静默通过。 */
    @Test
    public void silentNameLookupRuleCatchesLegacyCall() {
        String qualified = "class A { void a() { HookKit.findByNameQuiet(cls, \"m\"); } }";
        String unqualified = "class A { void a() { findByNameQuiet(cls, \"m\"); } }";

        assertEquals(1, SourceScan.scanSource(
                qualified, "A.java", NO_EXTERNAL_SILENT_NAME_LOOKUP).size());
        assertEquals("同包省略 HookKit. 的调用也必须命中", 1, SourceScan.scanSource(
                unqualified, "A.java", NO_EXTERNAL_SILENT_NAME_LOOKUP).size());
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

        int external = 0;
        for (SourceScan.Finding finding : findings) {
            if ("HookKit.java".equals(finding.file)) {
                // 属主类的内部静默重载调用/声明，不对外部调用点设限。
                continue;
            }
            external++;
            assertTrue("只允许 ScreenShotHooks :94/:116 的 onChange 静默定位存量缺口"
                            + "（属主补诊断记录后应消失）；新增静默定位必须记账：" + finding,
                    "ScreenShotHooks.java".equals(finding.file)
                            && finding.snippet.contains("\"onChange\""));
        }
        assertTrue("门禁不得放过 3 处以上静默定位：" + findings, external <= 2);
    }

    /** 规则本身必须能命中旧写法，避免以后把正则改坏后门禁静默通过。 */
    @Test
    public void silentMethodLookupRuleCatchesLegacyCall() {
        String qualified = "class A { void a() { if (HookKit.findMethodQuiet("
                + "cls, \"onChange\", boolean.class, Uri.class) == null) return; } }";
        String unqualified = "class A { void a() { if (findMethodQuiet("
                + "cls, \"onChange\", boolean.class, Uri.class) == null) return; } }";

        assertEquals("旧的无上下文重载必须被门禁抓住", 1,
                SourceScan.scanSource(qualified, "A.java", NO_SILENT_METHOD_LOOKUP).size());
        assertEquals("同包省略 HookKit. 的调用也必须被门禁抓住", 1,
                SourceScan.scanSource(unqualified, "A.java", NO_SILENT_METHOD_LOOKUP).size());
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

    /** F407：拼接型第 3 参不是「一个纯字面量」，本规则不取其中第一个字面量。 */
    @Test
    public void obfuscatedLabelRuleOnlyChecksPureLiteralThirdArgument() {
        String source = "class A { void a() { HookKit.install(module, f, "
                + "\"h8b.e#b(x)\" + \"\", m, hooker); } }";

        assertTrue("拼接 label 交给动态 label 台账，本规则只检查纯字面量",
                SourceScan.scanSource(source, "A.java", NO_OBFUSCATED_HOOK_LABEL).isEmpty());
    }

    /** F407：以字面量结尾的拼接表达式仍不是纯字面量，动态 label 门禁必须抓住。 */
    @Test
    public void dynamicLabelRuleTreatsConcatenationEndingWithLiteralAsDynamic() {
        String source = "class A { void a() { HookKit.install(module, f, "
                + "\"h8b.e#b(x)\" + \"\", m, hooker); } }";

        assertEquals("拼接表达式即使以字面量结尾也必须按非字面量登记",
                1, SourceScan.scanSource(source, "A.java", NO_DYNAMIC_HOOK_LABEL).size());
    }

    /**
     * F-120：第 3 参不是字面量的 install 调用点必须显式登记（file + 第 3 参表达式），
     * 否则「用运行时混淆类名当 label」会像旧规则那样静默漏过。这里的清单是唯一
     * 例外台账：新增未登记的非字面量 label 会让门禁失败。ScreenShotHooks:178 的
     * {@code key}（{@code outer.getName()}）是已知待修缺口（属主 C45/F-374 改为语义
     * 稳定名后应从清单移除），先钉住不让它继续隐形。
     */
    @Test
    public void dynamicHookLabelsAreExplicitlyAllowlisted() throws IOException {
        List<SourceScan.Finding> findings = SourceScan.scan(
                hooksSource(), Collections.singletonList(NO_DYNAMIC_HOOK_LABEL));

        for (SourceScan.Finding finding : findings) {
            String third = thirdArgumentRaw(finding.snippet);
            assertTrue("未登记的非字面量 label 调用点：file=" + finding.file
                            + " third=" + third + " | " + finding,
                    isAllowlistedDynamicLabel(finding.file, third));
        }
        assertFalse("规则未命中任何非字面量 label，可能被改坏（本仓应至少命中 ScreenShotHooks）",
                findings.isEmpty());
    }

    /**
     * 非字面量 label 的显式台账：file -> 允许的第 3 参表达式。
     * 语义稳定 = 来自 AnchorKeys / KEYS / 固定 label 表等编译期常量，不是运行时类名。
     */
    private static boolean isAllowlistedDynamicLabel(String file, String third) {
        switch (file) {
            case "AutoPlayHooks.java":      // for (String key : KEYS) —— AnchorKeys 常量
            case "ContentRatingHooks.java": // for (String key : {AnchorKeys.*}) —— 常量
            case "PushGuideHooks.java":     // for (String key : SHOW_KEYS) —— 常量
            case "SplashAdHooks.java":      // key/label 来自 *_KEYS / FULLSCREEN_LABELS
                return "key".equals(third) || "label".equals(third);
            case "AbTestHooks.java":        // "ABStore#" + key —— 稳定前缀 + 常量 key
                return ("\"ABStore#\" + key").equals(third);
            case "ScreenShotHooks.java":    // 已知缺口：key = outer.getName()（运行时混淆类名）
                return "key".equals(third) || "HOOK_LABEL".equals(third);
            case "LivePhotoWatermarkHooks.java":
                // "livePhotoSaveSwitch(code=" + LIVE_PHOTO_SAVE_SWITCH_CODE + "→false)"
                // —— 稳定前后缀 + 编译期常量，不含运行时类名。
                return ("\"livePhotoSaveSwitch(code=\" + LIVE_PHOTO_SAVE_SWITCH_CODE "
                        + "+ \"→false)\"").equals(third);
            default:
                return false;
        }
    }

    /** 规则自检：非字面量 label 必须被捕获，字面量 label 不得误报。 */
    @Test
    public void dynamicLabelRuleCatchesVariableLabel() {
        String variable = "class A { void a() { HookKit.install(module, f, key, m, hooker); } }";
        assertEquals("变量 label 必须被门禁抓住",
                1, SourceScan.scanSource(variable, "A.java", NO_DYNAMIC_HOOK_LABEL).size());

        String literal = "class A { void a() { HookKit.install(module, f, \"stableName\", m, hooker); } }";
        assertTrue("字面量 label 不应被误报",
                SourceScan.scanSource(literal, "A.java", NO_DYNAMIC_HOOK_LABEL).isEmpty());
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
