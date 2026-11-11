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

    private static Path locateForceSaveHooks() {
        return TestPaths.resolveFromCwd(
                "src/main/java/com/quietbox/app/hooks/ForceSaveHooks.java",
                "app/src/main/java/com/quietbox/app/hooks/ForceSaveHooks.java",
                "project/app/src/main/java/com/quietbox/app/hooks/ForceSaveHooks.java",
                "QuietBox/project/app/src/main/java/com/quietbox/app/hooks/ForceSaveHooks.java");
    }
}

