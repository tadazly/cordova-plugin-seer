package com.tadazly.seer;

import android.util.Log;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONException;

public class CordovaSeerPlugin extends CordovaPlugin {
    public final static String LOG_TAG = "plugin.CordovaSeer";

    @Override
    protected void pluginInitialize() {
        super.pluginInitialize();
        Log.d(LOG_TAG, "CordovaSeer pluginInitialize");
    }

    @Override
    public boolean execute(String action, CordovaArgs args, CallbackContext callbackContext) throws JSONException {
        Log.d(LOG_TAG, "CordovaSeer Action " + action);

        if (action.equals("isDefaultAppIcon")) {
            return this.isDefaultAppIcon(args, callbackContext);
        }
        else if (action.equals("setAppIcon")) {
            return this.setAppIcon(args, callbackContext);
        }
        return false;
    }


    private boolean isDefaultAppIcon(CordovaArgs args, CallbackContext callbackContext) {
        boolean result = false;
        if (result) {
            callbackContext.success();
        } else {
            callbackContext.error("isDefaultAppIcon failed");
        }
        return true;
    }

    private boolean setAppIcon(CordovaArgs args, CallbackContext callbackContext) throws JSONException {
        String appIconName = args.getString(0);
        boolean result = false;
        if (result) {
            callbackContext.success();
        } else {
            callbackContext.error("setAppIcon failed");
        }
        return true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }
}