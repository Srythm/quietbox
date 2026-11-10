package com.quietbox.app.anchor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.quietbox.app.VersionGate;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 发版门禁契约（纯 JVM）：tools/verify-apk.sh 必须在版本号校验（#6）与签名校验（#7）
 * 之间拦住「anchors.json 漏打包 / 版本错配」。
 *
 * <p>运行时从 classpath 根读 anchors.json，缺失即 fail-open（仅记 WARN + critical），
 * 因此漏打包/表过期不会在运行时报错，只能在发版前硬失败。
 * 本类把这条约束固化成单测：
 * 1) anchors 门禁确实位于 #6 与 #7 之间（顺序错位等于门禁失效）；
 * 2) 门禁覆盖「表缺失」「appVersion 错配」「versionCode 错配」三种失败且都需要 python 读表；
 * 3) 门禁比对的是 VersionGate 声明的适配版本，而不是脚本里的硬编码值；
 * 4) VersionGate 的适配版本与 anchors.json 的 appVersion/versionCode 一致——门禁要比对的正是它。
 */
public class VerifyApkAnchorGateTest {

    private static final String SCRIPT_RELATIVE = "tools/verify-apk.sh";
    private static final String VERSION_GATE_RELATIVE =
            "app/src/main/java/com/quietbox/app/VersionGate.java";
    private static final String SOURCE_TABLE_RELATIVE =
            "app/src/main/resources/anchors.json";

    private static final Pattern SUPPORTED_VERSION =
            Pattern.compile("SUPPORTED_VERSION\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern SUPPORTED_VERSION_CODE =
            Pattern.compile("SUPPORTED_VERSION_CODE\\s*=\\s*([0-9]+)L?");
    private static final Pattern APP_VERSION =
            Pattern.compile("\"appVersion\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern VERSION_CODE =
            Pattern.compile("\"versionCode\"\\s*:\\s*([0-9]+)");

    /** 从测试工作目录向上找仓库文件（Gradle 单测 cwd 多为 app/ 模块目录）。 */
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

    private static String firstGroup(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        assertTrue("无法从文本中提取 " + pattern.pattern(), matcher.find());
        return matcher.group(1);
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

    /** #6b 门禁块：从 6b 注释到 #7 签名校验之前。 */
    private static String anchorGateBlock(String script) {
        int start = script.indexOf("# 6b.");
        assertTrue("verify-apk.sh 缺少 #6b anchors.json 校验", start >= 0);
        int end = script.indexOf("# 7. 签名证书 SHA-256", start);
        assertTrue("verify-apk.sh 缺少 #7 签名校验", end > start);
        return script.substring(start, end);
    }

    @Test
    public void anchorGateSitsBetweenVersionAndSignatureChecks() throws Exception {
        String script = read(findRepoFile(SCRIPT_RELATIVE));
        int versionCheck = script.indexOf("# 6. aapt2 badging 版本号");
        int anchorGate = script.indexOf("# 6b.");
        int signatureCheck = script.indexOf("# 7. 签名证书 SHA-256");
        assertTrue("verify-apk.sh 缺少 #6 版本号校验", versionCheck >= 0);
        assertTrue("verify-apk.sh 缺少 #6b anchors.json 校验（版本号后直接进签名校验，锚点表漏打包无人拦截）",
                anchorGate >= 0);
        assertTrue("verify-apk.sh 缺少 #7 签名校验", signatureCheck >= 0);
        assertTrue("anchors.json 校验必须排在版本号校验之后", anchorGate > versionCheck);
        assertTrue("anchors.json 校验必须排在签名校验之前", anchorGate < signatureCheck);
    }

    @Test
    public void anchorGateCoversMissingTableAndVersionDrift() throws Exception {
        String gate = anchorGateBlock(read(findRepoFile(SCRIPT_RELATIVE)));
        assertTrue("门禁必须检查 APK 内 anchors.json 是否存在",
                gate.contains("[ ! -f \"$TMP_DIR/anchors.json\" ]"));
        assertTrue("表缺失时必须 bad（锚点表漏打包，运行时会 fail-open）",
                gate.contains("bad \"APK 内缺少 anchors.json"));
        assertTrue("门禁缺少 python 时的降级 bad 分支",
                gate.contains("[ -z \"$PYTHON_BIN\" ]"));
        // 错配必须硬失败（bad），而不是只打印 ok：ok 消息里同样含
        // "anchors.json appVersion=…" 字样，contains("appVersion") 无法区分
        // 「比对」与「只打印」——门禁丢失时单测必须拦住。
        assertTrue("门禁必须在 appVersion 错配时 bad",
                gate.contains("bad \"anchors.json appVersion 期望适配版本"));
        assertTrue("门禁必须在 versionCode 错配时 bad",
                gate.contains("bad \"anchors.json versionCode 期望适配版本"));
        assertTrue("门禁至少需要 5 条 bad：表缺失 / 无 python / 读不到 SUPPORTED_VERSION"
                        + " / appVersion 错配 / versionCode 错配",
                count(gate, "bad ") >= 5);
    }

    @Test
    public void anchorGateComparesAgainstVersionGateConstants() throws Exception {
        String gate = anchorGateBlock(read(findRepoFile(SCRIPT_RELATIVE)));
        assertTrue("门禁必须比对 VersionGate 的适配版本",
                gate.contains("VersionGate.java"));
        assertTrue("门禁必须读 SUPPORTED_VERSION", gate.contains("SUPPORTED_VERSION"));
        assertTrue("门禁必须读 SUPPORTED_VERSION_CODE",
                gate.contains("SUPPORTED_VERSION_CODE"));
        assertTrue("门禁不得出现写死的适配版本号",
                !SUPPORTED_VERSION.matcher(gate).find());
        File gateFile = findRepoFile(VERSION_GATE_RELATIVE);
        assertTrue("仓库缺少 VersionGate.java " + gateFile, gateFile.isFile());
    }

    @Test
    public void versionGateMatchesSourceTable() throws Exception {
        String gateSource = read(findRepoFile(VERSION_GATE_RELATIVE));
        String tableSource = read(findRepoFile(SOURCE_TABLE_RELATIVE));
        String gateVersion = firstGroup(gateSource, SUPPORTED_VERSION);
        String gateCode = firstGroup(gateSource, SUPPORTED_VERSION_CODE);
        assertEquals("VersionGate 的 SUPPORTED_VERSION 必须与 anchors.json appVersion 一致"
                        + "（改了适配版本却忘记重新生成表时，发版门禁要比出的正是这个差异）",
                gateVersion, firstGroup(tableSource, APP_VERSION));
        assertEquals("VersionGate 的 SUPPORTED_VERSION_CODE 必须与 anchors.json versionCode 一致",
                Long.parseLong(gateCode),
                Long.parseLong(firstGroup(tableSource, VERSION_CODE)));
    }

    /** 保障 gate 块提取本身没走空：脚本必须真的定义了 PYTHON_BIN。 */
    @Test
    public void scriptDiscoversPython() throws Exception {
        String script = read(findRepoFile(SCRIPT_RELATIVE));
        assertTrue("verify-apk.sh 必须定义 PYTHON_BIN",
                script.contains("PYTHON_BIN=\"$(command -v python3 || command -v python || true)\""));
    }

    /**
     * 行为断言（F-xxx）：用真实解析器把 anchors.json 解析成 AnchorTable，再断言
     * 解析结果的版本信息与 VersionGate 一致——而不是只对脚本文本做正则比对。
     *
     * <p>这条把「锚点表能对上适配版本」从「脚本里含某段文字」升级为「真实解析器
     * 读到的 appVersion/versionCode 就是 VersionGate 声明的那一版」。
     */
    @Test
    public void sourceTableParsesToVersionGateBehavior() {
        AnchorTable table = AnchorTable.load(AnchorTable.class.getClassLoader());
        assertNotNull("anchors.json 必须能在测试 classpath 上被真实解析器加载", table);
        assertEquals("解析出的 appVersion 必须等于 VersionGate 适配版本",
                VersionGate.SUPPORTED_VERSION, table.getAppVersion());
        assertEquals("解析出的 versionCode 必须等于 VersionGate 适配版本",
                VersionGate.SUPPORTED_VERSION_CODE, table.getVersionCode());
        assertTrue("适配版本自身必须被判定为受支持",
                table.isSupportedVersion(VersionGate.SUPPORTED_VERSION));
        assertFalse("非适配版本必须被判定为不受支持",
                table.isSupportedVersion("0.0.0-not-adapted"));
    }

    /**
     * 行为断言（F-xxx）：发版流程必须实际执行 tools/verify-apk.sh 对构建出的 APK
     * 做真包校验（门禁真的被接线，而不是只在脚本里定义了校验逻辑）。
     */
    @Test
    public void releaseFlowExecutesRealApkVerificationGate() throws Exception {
        String release = read(findRepoFile("tools/release.sh"));
        assertTrue("发版流程必须执行 verify-apk.sh 真包校验", release.contains("verify-apk.sh"));
        assertTrue("verify-apk.sh 必须接收构建出的真实 APK 路径（\"$APK\"）",
                release.contains("\"$APK\""));
        assertTrue("真包校验必须传入适配版本号做比对",
                release.contains("--expected-version"));
    }
}
