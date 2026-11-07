package com.quietbox.app.anchor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 发版脚本幂等重入契约（纯 JVM）：发布顺序是源码 tag → GitHub Release → 官方仓，
 * 任何一步成功后失败都必须能原样重跑，而不是先死在「tag 已存在」。
 *
 * <p>与 CloudMusicTool 的 ReleaseIdempotencyTest 保持同一断言：四仓 release.sh
 * 必须同构，否则一处改了、三处漏改，重跑策略就会静默分叉。
 * 覆盖三处状态检查：源码 tag、源码仓 Release、官方仓 tag/Release。
 * 冲突（tag 指向其它提交）仍必须硬失败，避免覆盖既有发布。
 */
public class ReleaseIdempotencyTest {

    private static final String SCRIPT_RELATIVE = "tools/release.sh";

    private static String script() throws Exception {
        return readText(findRepoFile(SCRIPT_RELATIVE));
    }

    @Test
    public void sourceTagRerunSkipsWhenTagPointsAtHead() throws Exception {
        String block = functionBlock(script(), "publish_source_tag");
        assertTrue("源码 tag 已存在时必须比较 tag 提交与 HEAD",
                block.contains("git rev-list -n1") && block.contains("git rev-parse HEAD"));
        assertTrue("tag 指向其它提交时必须硬失败（避免覆盖既有发布）",
                block.contains("if [ \"$tag_commit\" != \"$head_commit\" ]; then")
                        && block.contains("已存在且指向"));
        assertTrue("tag 指向 HEAD 时必须跳过创建并继续后续发布步骤",
                block.contains("tag_exists=1")
                        && block.contains("if [ \"$tag_exists\" -eq 0 ]; then"));
        assertFalse("不得再对已存在的 tag 无条件 die（会让重跑无法继续）",
                block.contains("die \"源码 tag v${VERSION} 已存在\""));
        assertTrue("重跑路径必须有明确日志", block.contains("可安全重跑"));
    }

    @Test
    public void githubReleaseRerunUploadsAssetsInsteadOfFailing() throws Exception {
        String block = functionBlock(script(), "publish_gh_release");
        int view = block.indexOf("gh release view \"v${VERSION}\"");
        int upload = block.indexOf("gh release upload \"v${VERSION}\"");
        int create = block.indexOf("gh release create \"v${VERSION}\"");
        assertTrue("Release 创建前必须先检查是否已存在", view >= 0);
        assertTrue("已存在的 Release 必须覆盖上传资产（幂等重入）", upload > view);
        assertTrue("覆盖上传必须带 --clobber", block.contains("--clobber"));
        assertTrue("只有 Release 不存在时才 create", create > upload);
    }

    @Test
    public void officialRepoRerunToleratesExistingTagAndRelease() throws Exception {
        String block = functionBlock(script(), "sync_official_repo");
        assertTrue("官方仓 tag 已存在时必须比较 tag 提交与当前 HEAD",
                block.contains("refs/tags/${official_tag}")
                        && block.contains("rev-list -n1")
                        && block.contains("rev-parse HEAD"));
        assertTrue("官方仓 tag 指向其它提交时必须硬失败",
                block.contains("if [ \"$tag_commit\" != \"$head_commit\" ]; then"));
        assertTrue("官方仓已存在的 tag 必须跳过创建而不是 die",
                block.contains("已存在且指向当前提交，跳过创建"));
        assertTrue("官方仓已存在的 Release 必须覆盖上传资产",
                block.contains("gh release upload \"$official_tag\"")
                        && block.contains("--clobber"));
    }

    /** 从 `name() {` 到行首 `}`，避免断言跨到相邻函数。 */
    private static String functionBlock(String script, String name) {
        String marker = name + "() {";
        int start = script.indexOf(marker);
        if (start < 0) {
            fail("release.sh 缺少函数 " + name);
        }
        int end = script.indexOf("\n}\n", start);
        if (end < 0) {
            fail("release.sh 的函数 " + name + " 没有闭合");
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

    private static String readText(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
