package com.tadazly.seer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONException;

import java.util.List;

public class CordovaSeerPlugin extends CordovaPlugin {
    private static final String PREF_NAME = "icon_pref";
    private static final String KEY_PENDING_ALIAS = "pending_alias";
    public final static String LOG_TAG = "plugin.CordovaSeer";

    @Override
    protected void pluginInitialize() {
        super.pluginInitialize();
        Log.d(LOG_TAG, "CordovaSeer pluginInitialize");
    }

    @Override
    public boolean execute(String action, CordovaArgs args, CallbackContext callbackContext) throws JSONException {
        Log.d(LOG_TAG, "CordovaSeer Action " + action);

        if (action.equals("isPrimaryAppIcon")) {
            return this.isPrimaryAppIcon(args, callbackContext);
        }
        else if (action.equals("setAppIcon")) {
            return this.setAppIcon(args, callbackContext);
        }
        else if (action.equals("getCurrentAppIconName")) {
            return this.getCurrentAppIconName(args, callbackContext);
        }
        return false;
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