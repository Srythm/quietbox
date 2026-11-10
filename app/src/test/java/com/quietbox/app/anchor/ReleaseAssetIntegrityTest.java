package com.quietbox.app.anchor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Release 全部资产完整性校验契约（纯 JVM）。
 *
 * <p>F123：release.sh 原来先跑 verify-apk.sh 再拷 mapping.txt / release-notes.md，
 * SHA256SUMS 只含 APK 一项；官方仓回验又只用 {@code --pattern '*.apk'} + head -1
 * 比对一个标量哈希，导致 mapping.txt / release-notes.md 发布后零校验。现在：
 * 1) 先归档全部内容资产、再跑 verify-apk.sh；
 * 2) 把 mapping.txt / release-notes.md 追加进 SHA256SUMS（本仓 verify-apk.sh 仍
 *    只写 APK 单项，清单在 release.sh 内补齐）；
 * 3) 回验下载全部资产、cmp 锁死清单本身、再用 sha256sum -c 按文件名逐项比对。
 */
public class ReleaseAssetIntegrityTest {

    private static final String RELEASE = "tools/release.sh";

    @Test
    public void stagingCreatesAllArtifactsBeforeChecksumsAreWritten() throws Exception {
        String script = read(findRepoFile(RELEASE));
        int verify = script.indexOf("bash \"$SCRIPT_DIR/verify-apk.sh\"");
        int apkCopy = script.indexOf("cp \"$APK\" \"$ARTIFACT_DIR/$(basename \"$APK\")\"");
        int mappingCopy = script.indexOf("cp \"$MAPPING\" \"$ARTIFACT_DIR/mapping.txt\"");
        int notes = script.indexOf("printf '%s\\n' \"$NOTES\" > \"$ARTIFACT_DIR/release-notes.md\"");
        assertTrue("release.sh 必须调用 verify-apk.sh 写清单", verify >= 0);
        assertTrue("APK / mapping / notes 必须在 verify-apk 之前就归档好",
                apkCopy >= 0 && mappingCopy >= 0 && notes >= 0
                        && apkCopy < verify && mappingCopy < verify && notes < verify);
    }

    @Test
    public void manifestCoversMappingAndReleaseNotes() throws Exception {
        String script = read(findRepoFile(RELEASE));
        assertTrue("mapping.txt 与 release-notes.md 必须逐个计入哈希清单",
                script.contains("for name in mapping.txt release-notes.md; do")
                        && script.contains("\"$ARTIFACT_DIR/$name\""));
        assertTrue("清单行必须是 sha256sum 可解析的 `<hash>  <basename>` 格式",
                script.contains("printf '%s  %s\\n' \"$sum\" \"$name\""));
        assertTrue("逐个产物计算哈希必须走 sha256_of()",
                script.contains("sha256_of() {")
                        && script.contains("sha256_of \"$ARTIFACT_DIR/$name\""));
    }

    @Test
    public void releaseVerifiesEveryDownloadedAssetByFileName() throws Exception {
        String script = read(findRepoFile(RELEASE));
        String sync = functionBlock(script, "sync_official_repo");
        assertTrue("下载必须覆盖全部资产（不再限定 *.apk）",
                sync.contains("--dir \"$dl_dir\" --clobber")
                        && !sync.contains("--pattern '*.apk'"));
        assertFalse("回验段不得再用 glob + head -1 只取首个 APK 哈希",
                sync.contains("sha256sum \"$dl_dir\"/*.apk | awk '{print $1}' | head -1"));
        assertFalse("不得再把回验缩减为两个标量哈希字符串比对",
                sync.contains("remote_sha=\"$(sha256sum"));
        assertTrue("回验前必须 cmp 锁死 SHA256SUMS 清单本身（防删行/替换）",
                sync.contains("cmp -s \"$ARTIFACT_DIR/SHA256SUMS\" \"$dl_dir/SHA256SUMS\""));
        assertTrue("必须按清单逐项 sha256sum -c 比对全部资产",
                sync.contains("sha256sum -c \"$ARTIFACT_DIR/SHA256SUMS\""));
        assertTrue("比对失败必须硬失败",
                sync.contains("die \"官方仓资产 SHA-256 不一致"));
    }

    /** 从 `name() {` 到下一个行首 `}`，避免断言跨到相邻函数。 */
    private static String functionBlock(String script, String name) {
        String marker = name + "() {";
        int start = script.indexOf(marker);
        if (start < 0) {
            fail(RELEASE + " 缺少函数 " + name);
        }
        int end = script.indexOf("\n}\n", start);
        if (end < 0) {
            fail(RELEASE + " 的函数 " + name + " 没有闭合");
        }
        return script.substring(start, end + 3);
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
}
