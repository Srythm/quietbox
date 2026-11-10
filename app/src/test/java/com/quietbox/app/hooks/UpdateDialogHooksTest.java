package com.quietbox.app.hooks;

import static org.junit.Assert.assertTrue;

import com.quietbox.app.TestPaths;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * F-107：UI-04 两个 critical 挂载点（update_dialog_on_create / update_dialog_he）的
 * 失败必须真正落进 HookState 的 critical 记账，让失败横幅可见。
 *
 * <p>纯 JVM 不构造 AnchorTable/native：这里用源码护栏固定三条接线——
 * 两个锚点判空后各补一次 markNotFoundCritical、install 整段 catch 也补 critical。
 * findAnchorMethod(..., true) 与 install 的普通 skip 记账由 HookKit/HookDiagnosisTest
 * 覆盖，这里只钉住「critical=true 的语义真正落到横幅」这一新增部分。
 */
public class UpdateDialogHooksTest {

    private static String source() throws Exception {
        Path path = TestPaths.resolveFromCwd(
                "app/src/main/java/com/quietbox/app/hooks/UpdateDialogHooks.java",
                "QuietBox/project/app/src/main/java/com/quietbox/app/hooks/UpdateDialogHooks.java",
                "src/main/java/com/quietbox/app/hooks/UpdateDialogHooks.java",
                "project/app/src/main/java/com/quietbox/app/hooks/UpdateDialogHooks.java");
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    public void onCreateAndHeMissingAnchorsAreRecordedCritical() throws Exception {
        String src = source();
        int body = src.indexOf("static void install(");
        assertTrue("install 必须存在", body >= 0);

        int onCreateNull = src.indexOf("if (onCreate == null) {", body);
        assertTrue("onCreate 判空必须存在", onCreateNull >= 0);
        assertTrue("onCreate 缺失必须补 markNotFoundCritical",
                src.indexOf("HookState.markNotFoundCritical(f,", onCreateNull)
                        < src.indexOf("}", onCreateNull));

        int heNull = src.indexOf("if (he == null) {", body);
        assertTrue("he 判空必须存在", heNull >= 0);
        assertTrue("he 缺失必须补 markNotFoundCritical",
                src.indexOf("HookState.markNotFoundCritical(f,", heNull)
                        < src.indexOf("}", heNull));
    }

    @Test
    public void installCatchRecordsCriticalInsteadOfStayingSilent() throws Exception {
        String src = source();
        int body = src.indexOf("static void install(");
        int catchBlock = src.indexOf("catch (Throwable t) {", body);
        assertTrue("install 的 catch 必须存在", catchBlock >= 0);
        int critical = src.indexOf("HookState.markNotFoundCritical(f,", catchBlock);
        int close = src.indexOf("\n    }", catchBlock);
        assertTrue("整段安装抛错也必须补 critical 记账（失败横幅可见）",
                critical >= 0 && (close < 0 || critical < close));
    }
}
