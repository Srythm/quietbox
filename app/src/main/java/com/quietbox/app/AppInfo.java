package com.quietbox.app;

import android.content.Context;
import android.content.pm.PackageInfo;

/**
 * 模块 App 的版本信息与目标应用包名的唯一来源（包级工具）。
 *
 * 以前 versionName() 在 MainActivity / SettingsFragment / DiagnosticsFragment /
 * AboutFragment 各复制一份，目标包名又在六个类里各硬编码一次，版本与包名升级时
 * 容易漏改、关于页/工具栏/导出文本格式漂移。这里统一收口，改一处即可。
 *
 * <p>查询失败一律 fail-open 返回占位符或 null，绝不向外抛异常（DESIGN §14 容错口径）。
 * 不写任何用户可见中文文案：展示用文案由调用方走 strings.xml。
 */
final class AppInfo {

    /** 目标应用（小红书）包名；唯一允许写入/注入的宿主。 */
    static final String TARGET_PACKAGE = "com.xingin.xhs";

    private static final String UNKNOWN = "?";

    private AppInfo() {
    }

    /** 模块自身 versionName；查询失败返回 "?"。 */
    static String versionName(Context context) {
        try {
            String v = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return v != null ? v : UNKNOWN;
        } catch (Throwable t) {
            return UNKNOWN;
        }
    }

    /** 模块自身 versionCode（longVersionCode 截成 int）；查询失败返回 0。 */
    static int versionCode(Context context) {
        try {
            PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return (int) pi.getLongVersionCode();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 目标应用 versionName；未安装或查询失败返回 null（供版本门禁比较）。 */
    static String targetVersionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(TARGET_PACKAGE, 0).versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 目标应用版本串，形如 "9.53.0 (9530807)"；未安装或查询失败返回 null，
     * 由调用方决定用 strings.xml 里的哪个提示文案展示。
     */
    static String targetVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(TARGET_PACKAGE, 0);
            String version = info.versionName != null ? info.versionName : UNKNOWN;
            // 与 versionCode() 同口径：versionCode 字段自 API 28 起废弃，完整值走
            // getLongVersionCode()，避免两处输出截断/0 值时口径漂移。
            return version + " (" + info.getLongVersionCode() + ")";
        } catch (Throwable t) {
            return null;
        }
    }
}
