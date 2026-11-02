package com.tadazly.seer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.util.DisplayMetrics;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsets;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

public class CordovaSeerPlugin extends CordovaPlugin {
    private static final String PREF_NAME = "icon_pref";
    private static final String KEY_PENDING_ALIAS = "pending_alias";
    private static final int SAVE_IMAGE_PERMISSION = 8204;
    private SeerImageAlbum pendingAlbumSave;
    public final static String LOG_TAG = "plugin.CordovaSeer";

    @Override
    protected void pluginInitialize() {
        super.pluginInitialize();
        // 使用更高的日志级别，避免在 logcat 默认过滤下看不到。
        Log.i(LOG_TAG, "CordovaSeer pluginInitialize");
    }

    @Override
    public boolean execute(String action, CordovaArgs args, CallbackContext callbackContext) throws JSONException {
        Log.i(LOG_TAG, "CordovaSeer Action " + action);

        if (action.equals("isPrimaryAppIcon")) {
            return this.isPrimaryAppIcon(args, callbackContext);
        }
        else if (action.equals("setAppIcon")) {
            return this.setAppIcon(args, callbackContext);
        }
        else if (action.equals("getCurrentAppIconName")) {
            return this.getCurrentAppIconName(args, callbackContext);
        }
        else if (action.equals("getSafeAreaXywh")) {
            return this.getSafeAreaXywh(args, callbackContext);
        }
        else if (action.equals("getPhotoAlbumPermissionStatus")) {
            cordova.getActivity().runOnUiThread(() -> {
                try {
                    callbackContext.success(SeerImageAlbum.permissionStatus(cordova));
                } catch (Exception e) {
                    albumError(callbackContext, "SAVE_FAILED", "查询相册权限失败：" + e.getMessage());
                }
            });
            return true;
        }
        else if (action.equals("openAppSettings")) {
            cordova.getActivity().runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + cordova.getContext().getPackageName()));
                    cordova.getActivity().startActivity(intent);
                    callbackContext.success();
                } catch (Exception e) {
                    albumError(callbackContext, "OPEN_SETTINGS_FAILED", "无法打开应用设置：" + e.getMessage());
                }
            });
            return true;
        }
        else if (action.equals("saveImageToAlbum")) {
            final String source = args.optString(0);
            final String name = args.optString(1);
            cordova.getThreadPool().execute(() -> {
                SeerImageAlbum save = new SeerImageAlbum(cordova.getContext(), webView.getResourceApi(), callbackContext);
                if (!save.prepare(source, name)) return;
                if (Build.VERSION.SDK_INT <= 28 && !SeerImageAlbum.hasLegacyPermissions(cordova)) {
                    synchronized (this) {
                        if (pendingAlbumSave != null) {
                            save.fail("BUSY", "另一个保存请求正在等待相册权限");
                            return;
                        }
                        pendingAlbumSave = save;
                    }
                    cordova.getActivity().runOnUiThread(() -> {
                        synchronized (this) {
                            if (pendingAlbumSave != save) return;
                        }
                        try {
                            JSONObject status = SeerImageAlbum.permissionStatus(cordova);
                            if ("unavailable".equals(status.getString("status"))) {
                                throw new IllegalStateException("安装包缺少旧版存储读写权限声明");
                            }
                            cordova.requestPermissions(this, SAVE_IMAGE_PERMISSION, SeerImageAlbum.LEGACY_PERMISSIONS);
                        } catch (Exception e) {
                            synchronized (this) {
                                if (pendingAlbumSave == save) pendingAlbumSave = null;
                            }
                            save.fail(e instanceof IllegalStateException ? "MISSING_PERMISSION" : "SAVE_FAILED", e.getMessage());
                        }
                    });
                } else {
                    save.write();
                }
            });
            return true;
        }
        return false;
    }

    private void albumError(CallbackContext callback, String code, String message) {
        try { callback.error(new JSONObject().put("code", code).put("message", message)); }
        catch (JSONException ignored) { callback.error(code); }
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        if (requestCode != SAVE_IMAGE_PERMISSION) return;
        final SeerImageAlbum save;
        synchronized (this) {
            save = pendingAlbumSave;
            pendingAlbumSave = null;
        }
        // 只有系统给出结果才记为已询问；进程在弹框中被关闭不等于永久拒绝。
        if (grantResults.length > 0) SeerImageAlbum.markPermissionRequested(cordova.getContext());
        if (save == null) return;
        boolean granted = grantResults.length == SeerImageAlbum.LEGACY_PERMISSIONS.length;
        for (int result : grantResults) granted &= result == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            cordova.getThreadPool().execute(save::write);
        } else {
            save.fail("PERMISSION_DENIED", "未获得相册写入权限，请在系统设置中允许后重试");
        }
    }

    @Override
    public void onReset() {
        cancelPendingAlbumSave();
        super.onReset();
    }

    private void cancelPendingAlbumSave() {
        synchronized (this) {
            if (pendingAlbumSave != null) {
                pendingAlbumSave.fail("SAVE_FAILED", "页面已重载，请重新保存");
                pendingAlbumSave = null;
            }
        }
    }

    private boolean isPrimaryAppIcon(CordovaArgs args, CallbackContext callbackContext) {
        Context context = cordova.getContext();
        String currentName = getEnabledAppIconName(context);
        String primaryIconName = getIconNameFromAlias(getManifestDefaultAlias(context));
        if (primaryIconName.equals(currentName)) {
            callbackContext.success(1);
        } else {
            callbackContext.success(0);
        }
        return true;
    }

    private boolean setAppIcon(CordovaArgs args, CallbackContext callbackContext) throws JSONException {
        String appIconName = args.getString(0);

        Context context = cordova.getContext();
        if (appIconName != null && appIconName.equals(getEnabledAppIconName(context))) {
            callbackContext.success(0);
            return true;
        }

        if (appIconName == null || appIconName.isEmpty()) {
            appIconName = getIconNameFromAlias(getManifestDefaultAlias(context));
        }

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_PENDING_ALIAS, appIconName).apply();

        // 不立即切换，只返回成功
        callbackContext.success(1);
        return true;
    }

    private boolean getCurrentAppIconName(CordovaArgs args, CallbackContext callbackContext) throws JSONException {
        Context context = cordova.getContext();
        String name = getEnabledAppIconName(context);
        callbackContext.success(name);
        return true;
    }

    /**
     * 获取安全区并返回像素坐标系下的 xywh。
     * x/y：从屏幕左上角开始的安全起点
     * w/h：安全区宽高（不含系统栏/刘海安全区域）
     */
    private boolean getSafeAreaXywh(CordovaArgs args, CallbackContext callbackContext) throws JSONException {
        Activity activity = cordova.getActivity();
        if (activity == null) {
            callbackContext.error("Activity is null");
            return true;
        }

        DisplayMetrics metrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(metrics);
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;

        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        int orientation = activity.getResources().getConfiguration().orientation;

        View decorView = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        int systemUiVisibility = decorView != null ? decorView.getSystemUiVisibility() : 0;
        boolean isFullscreen = false;
        try {
            if (activity.getWindow() != null) {
                isFullscreen = (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0;
            }
        } catch (Exception ignored) {}

        int left = 0;
        int top = 0;
        int right = 0;
        int bottom = 0;

        boolean usedInsets = false;
        boolean usedCutout = false;
        boolean hasCutout = false;
        String source = "fallback-none";

        int cutoutSafeLeft = 0;
        int cutoutSafeTop = 0;
        int cutoutSafeRight = 0;
        int cutoutSafeBottom = 0;

        // RootWindowInsets 需要 API 23+，否则退化为全屏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            WindowInsets windowInsets = decorView != null ? decorView.getRootWindowInsets() : null;

            if (windowInsets != null) {
                usedInsets = true;
                left = Math.max(0, windowInsets.getSystemWindowInsetLeft());
                top = Math.max(0, windowInsets.getSystemWindowInsetTop());
                right = Math.max(0, windowInsets.getSystemWindowInsetRight());
                bottom = Math.max(0, windowInsets.getSystemWindowInsetBottom());

                // 刘海（DisplayCutout）安全区域取最大值，避免系统栏不足以覆盖刘海区域
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    DisplayCutout cutout = windowInsets.getDisplayCutout();
                    if (cutout != null) {
                        usedCutout = true;
                        hasCutout = true;
                        cutoutSafeLeft = cutout.getSafeInsetLeft();
                        cutoutSafeTop = cutout.getSafeInsetTop();
                        cutoutSafeRight = cutout.getSafeInsetRight();
                        cutoutSafeBottom = cutout.getSafeInsetBottom();
                        left = Math.max(left, cutout.getSafeInsetLeft());
                        top = Math.max(top, cutout.getSafeInsetTop());
                        right = Math.max(right, cutout.getSafeInsetRight());
                        bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                    }
                }
            }
        }

        if (usedCutout) {
            source = "cutout+insets";
        } else if (usedInsets) {
            source = "insets";
        }

        int x = left;
        int y = top;
        int w = Math.max(0, screenWidth - left - right);
        int h = Math.max(0, screenHeight - top - bottom);

        // 部分机型/场景下 insets 可能异常，防止返回 0 宽高导致前端无法计算布局
        if (w <= 0 || h <= 0) {
            x = 0;
            y = 0;
            w = screenWidth;
            h = screenHeight;
            source = "fallback-fullscreen";
        }

        JSONObject out = new JSONObject();
        out.put("x", x);
        out.put("y", y);
        out.put("w", w);
        out.put("h", h);

        // 机型信息：便于前端/业务按机型特殊处理
        out.put("model", Build.MODEL);
        out.put("brand", Build.BRAND);
        out.put("manufacturer", Build.MANUFACTURER);
        out.put("sdkInt", Build.VERSION.SDK_INT);
        out.put("source", source);

        // 与系统/窗口状态相关的环境信息（用于排查“有的机型拿不到/异常”的情况）
        out.put("screenWidth", screenWidth);
        out.put("screenHeight", screenHeight);
        out.put("rotation", rotation);
        out.put("orientation", orientation);
        out.put("systemUiVisibility", systemUiVisibility);
        out.put("isFullscreen", isFullscreen);
        out.put("usedInsets", usedInsets);
        out.put("usedCutout", usedCutout);
        out.put("hasCutout", hasCutout);
        out.put("statusBarLikelyHidden", top <= 0);
        out.put("navigationBarLikelyHidden", bottom <= 0);

        if (hasCutout) {
            JSONObject cutoutSafeInsets = new JSONObject();
            cutoutSafeInsets.put("safeLeft", cutoutSafeLeft);
            cutoutSafeInsets.put("safeTop", cutoutSafeTop);
            cutoutSafeInsets.put("safeRight", cutoutSafeRight);
            cutoutSafeInsets.put("safeBottom", cutoutSafeBottom);
            out.put("cutoutSafeInsets", cutoutSafeInsets);
        } else {
            out.put("cutoutSafeInsets", JSONObject.NULL);
        }

        JSONObject insets = new JSONObject();
        insets.put("left", left);
        insets.put("top", top);
        insets.put("right", right);
        insets.put("bottom", bottom);
        out.put("insets", insets);

        Log.i(LOG_TAG, "safeAreaXywh: " + out);
        callbackContext.success(out);
        return true;
    }

    /**
     * 从alias名中截取icon名
     * @param alias
     * @return
     */
    private String getIconNameFromAlias(String alias) {
        if (alias != null && !alias.isEmpty()) {
            return alias.replaceFirst("^.*\\.Launcher", "");
        }
        return "Unknown";
    }

    /**
     * 获取当前启用的Icon名
     * @param context
     * @return
     */
    private String getEnabledAppIconName(Context context) {
        String currentAlias = getEnabledLauncherAlias(cordova.getContext());
        return getIconNameFromAlias(currentAlias);
    }

    /**
     * 获取当前启用的Activity-alias
     * @param context
     * @return
     */
    private String getEnabledLauncherAlias(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setPackage(context.getPackageName());

        List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
        if (list == null || list.isEmpty()) return null;

        for (ResolveInfo info : list) {
            // 只会返回当前启用的 launcher 组件（alias 或 Activity）
            String name = info.activityInfo.name;
            return name; // e.g. com.taomee.monster.seer.LauncherAnniversary
        }
        return null;
    }

    /**
     * 获取包体默认启用的activity-alias
     * @param context
     * @return
     */
    private String getManifestDefaultAlias(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pkgInfo = pm.getPackageArchiveInfo(
                    context.getApplicationInfo().sourceDir,
                    PackageManager.GET_ACTIVITIES | PackageManager.MATCH_DISABLED_COMPONENTS
            );
            if (pkgInfo == null) return null;

            for (ActivityInfo info : pkgInfo.activities) {
                if (info.targetActivity != null && info.enabled) {
                    // enabled=true 的那个 alias 就是 Manifest 默认入口
                    return info.name;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public void onDestroy() {
        cancelPendingAlbumSave();
        super.onDestroy();
        applyPendingAliasChange();
    }

    /**
     * 延迟到正常退出时生效，避免游戏中直接退出app
     */
    private void applyPendingAliasChange() {
        Context context = cordova.getContext();
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String alias = prefs.getString(KEY_PENDING_ALIAS, null);
        if (alias == null) return;

        String pkg = context.getPackageName();
        PackageManager pm = context.getPackageManager();

        // 预先拼出目标别名的全路径名
        String targetFullName = pkg + ".Launcher" + alias;

        try {
            PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES | PackageManager.MATCH_DISABLED_COMPONENTS);
            for (ActivityInfo activity : info.activities) {
                if (!activity.name.contains("Launcher")) continue;

                boolean shouldEnable = activity.name.equals(targetFullName);
                ComponentName component = new ComponentName(pkg, activity.name);

                pm.setComponentEnabledSetting(
                        component,
                        shouldEnable
                                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                );
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // 切换完清除记录
        prefs.edit().remove(KEY_PENDING_ALIAS).apply();
    }
}
