package com.quietbox.app.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.quietbox.app.TestPaths;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ForceSaveHooks 纯函数的 JVM 单测。
 *
 * 9.52.0 dexsql 核实：FunctionSwitch 的 a()=enable、c()=type；type 取值来自
 * 服务端权限数组（image_download / video_download 等）。只应对下载类 type 强制
 * enable，其它 type（评论、AI 配音等）必须保持宿主原值。
 */
public class ForceSaveHooksTest {

    @Test
    public void imageDownloadIsForcedEnabled() {
        assertTrue(ForceSaveHooks.shouldForceEnable("image_download"));
    }

    @Test
    public void videoDownloadIsForcedEnabled() {
        assertTrue(ForceSaveHooks.shouldForceEnable("video_download"));
    }

    @Test
    public void otherTypesKeepHostValue() {
        assertFalse(ForceSaveHooks.shouldForceEnable("comment"));
        assertFalse(ForceSaveHooks.shouldForceEnable("ai_dubbing"));
        assertFalse(ForceSaveHooks.shouldForceEnable(""));
        assertFalse(ForceSaveHooks.shouldForceEnable(null));
    }

    @Test
    public void downloadItemTypesAreMatched() {
        // 长按/分享面板条目的 type 是 "Download"（首字母大写），与 FunctionSwitch 的
        // "image_download" 命名不同，两种都要认。
        assertTrue(ForceSaveHooks.isDownloadItem("Download"));
        assertTrue(ForceSaveHooks.isDownloadItem("image_download"));
        assertTrue(ForceSaveHooks.isDownloadItem("video_download"));
    }

    @Test
    public void nonDownloadItemsKeepHostValue() {
        assertFalse(ForceSaveHooks.isDownloadItem("Dislike"));
        assertFalse(ForceSaveHooks.isDownloadItem("Report"));
        assertFalse(ForceSaveHooks.isDownloadItem(""));
        assertFalse(ForceSaveHooks.isDownloadItem(null));
    }

    @Test
    public void typeMatchIsExactNotPrefix() {
        // 防止将来改成 startsWith/contains 时把 video_download_x 之类误放行
        assertFalse(ForceSaveHooks.shouldForceEnable("image_download_extra"));
        assertFalse(ForceSaveHooks.shouldForceEnable(" video_download"));
    }

    /**
     * F404 部分护栏：EN-04 门 2 的 Hooker 是 install() 内的匿名类（ForceSaveHooks.java
     * 不在本簇可改范围，无法提为工厂方法做行为测试）。这里用源码门禁钉住三条
     * 变异风险：去掉 String 守卫、无条件返回 TRUE、非下载类不回落原值。
     */
    @Test
    public void hookerGuardsTypeBeforeForcingTrue() throws Exception {
        String source = new String(Files.readAllBytes(locateForceSaveHooks()),
                StandardCharsets.UTF_8);
        assertTrue("type 必须同时满足 instanceof String 与下载白名单",
                source.contains("typeValue instanceof String")
                        && source.contains("&& shouldForceEnable((String) typeValue)"));
        assertTrue("命中下载类才返回 TRUE", source.contains("return Boolean.TRUE;"));
        assertTrue("其它情况必须回落宿主原值（fail-open）",
                source.contains("return originalOrFalse(original);"));
    }

    /**
     * EN-04 必须同时挂旧门与新模型门：9.53.0 的保存判定已迁到 arch 模型
     * （旧实体只是 xq6.i 从新模型拷贝构造的兼容结构），只挂旧门会出现
     * 「诊断页有 hit、保存仍被拦」。这里钉住新模型锚点与挂载同时存在，
     * 防止将来删锚点或删挂载只删一半。
     */
    @Test
    public void installsArchModelGate() throws Exception {
        String source = new String(Files.readAllBytes(locateForceSaveHooks()),
                StandardCharsets.UTF_8);
        assertTrue("必须引用 arch 模型锚点常量",
                source.contains("AnchorKeys.FORCE_SAVE_MEDIA_CONFIG_V2"));
        assertTrue("新模型门必须固定返回 false（允许保存）",
                source.contains("\"MediaSaveConfig#getDisableSave(allow-save)\"")
                        && source.contains("HookKit.constBool(module, f, "
                                + "\"MediaSaveConfig#getDisableSave\", false)"));
    }

    /**
     * 9.53.0 真机复核：按钮置灰的调用方直读 {@code disableSaveMedia} 字段（iget），
     * getter hook 拿不到采纳。必须有一条「构造后改字段」的路径，这里钉住它存在。
     */
    @Test
    public void rewritesDisableSaveMediaOnConstruct() throws Exception {
        String source = new String(Files.readAllBytes(locateForceSaveHooks()),
                StandardCharsets.UTF_8);
        assertTrue("必须用 getDeclaredConstructor 解析构造器（getDeclaredMethod 不认 <init>）",
                source.contains("getDeclaredConstructor("));
        assertTrue("必须改写 disableSaveMedia 字段",
                source.contains("\"disableSaveMedia\""));
        assertTrue("改写在 proceed 之后、且只在原值为 true 时执行",
                source.contains("Object result = chain.proceed();")
                        && source.contains("if (before)"));
    }

    private static Path locateForceSaveHooks() {
        return TestPaths.resolveFromCwd(
                "src/main/java/com/quietbox/app/hooks/ForceSaveHooks.java",
                "app/src/main/java/com/quietbox/app/hooks/ForceSaveHooks.java",
                "project/app/src/main/java/com/quietbox/app/hooks/ForceSaveHooks.java",
                "QuietBox/project/app/src/main/java/com/quietbox/app/hooks/ForceSaveHooks.java");
    }
}

