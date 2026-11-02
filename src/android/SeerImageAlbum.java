package com.tadazly.seer;

import android.content.ContentValues;
import android.content.Context;
import android.Manifest;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaResourceApi;
import org.apache.cordova.CordovaInterface;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;

/** 仅复制编码后的文件；不创建整张 Bitmap，不重新压缩长图。 */
final class SeerImageAlbum {
    static final String[] LEGACY_PERMISSIONS = {Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};
    private static final String PERMISSION_PREFS = "seer_album_permission";
    private static final String REQUESTED = "write_requested";
    private final Context context;
    private final CordovaResourceApi resources;
    private final CallbackContext callback;
    private File staged;
    private String fileName;
    private String mimeType;

    SeerImageAlbum(Context context, CordovaResourceApi resources, CallbackContext callback) {
        this.context = context;
        this.resources = resources;
        this.callback = callback;
    }

    static JSONObject permissionStatus(CordovaInterface cordova) throws JSONException {
        // Android 10+ 新增本 App 的 MediaStore 图片不依赖存储或相册读取权限。
        if (Build.VERSION.SDK_INT >= 29) return status("notRequired", true, false, null);
        Context context = cordova.getContext();
        java.util.Set<String> declared = new java.util.HashSet<>();
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions != null) {
                for (String permission : info.requestedPermissions) {
                    declared.add(permission);
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) { }
        for (String permission : LEGACY_PERMISSIONS) {
            if (!declared.contains(permission)) return status("unavailable", false, false, "MISSING_PERMISSION");
        }
        if (hasLegacyPermissions(cordova)) return status("granted", true, false, null);
        boolean requested = context.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE).getBoolean(REQUESTED, false);
        boolean unknown = false;
        boolean canRequest = true;
        for (String permission : LEGACY_PERMISSIONS) {
            if (cordova.hasPermission(permission)) continue;
            boolean rationale = cordova.getActivity().shouldShowRequestPermissionRationale(permission);
            if (!requested && !rationale) unknown = true;
            if (requested && !rationale) canRequest = false;
        }
        // Android 不提供可供普通 App 查询的“是否曾由其他组件申请”状态。
        // 无本插件记录且无 rationale 时保留未知，不能错误禁用首次保存。
        return status(unknown ? "unknown" : "denied", false, canRequest, null);
    }

    static boolean hasLegacyPermissions(CordovaInterface cordova) {
        for (String permission : LEGACY_PERMISSIONS) {
            if (!cordova.hasPermission(permission)) return false;
        }
        return true;
    }

    private static JSONObject status(String state, boolean canSave, boolean canRequest, String reason) throws JSONException {
        JSONObject result = new JSONObject().put("status", state).put("canSave", canSave).put("canRequest", canRequest);
        if (reason != null) result.put("reason", reason);
        return result;
    }

    static void markPermissionRequested(Context context) {
        context.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE).edit().putBoolean(REQUESTED, true).apply();
    }

    boolean prepare(String source, String requestedName) {
        if (source == null || source.isEmpty() || requestedName == null || requestedName.length() > 120 ||
            requestedName.matches(".*[\\\\/\\x00-\\x1f].*") || requestedName.matches("\\.+")) {
            fail("INVALID_ARGUMENT", "source 或 fileName 无效");
            return false;
        }
        try {
            staged = File.createTempFile("seer-album-", ".tmp", context.getCacheDir());
            try (InputStream input = openSource(source); OutputStream output = new FileOutputStream(staged)) {
                copy(input, output);
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(staged.getAbsolutePath(), bounds);
            mimeType = bounds.outMimeType;
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                !("image/png".equals(mimeType) || "image/jpeg".equals(mimeType))) {
                fail("INVALID_IMAGE", "文件不是有效的 PNG/JPEG 图片");
                return false;
            }
            String stem = requestedName.trim().replaceFirst("(?i)\\.(png|jpe?g)$", "");
            if (stem.isEmpty()) stem = "seer-" + UUID.randomUUID();
            fileName = stem + ("image/png".equals(mimeType) ? ".png" : ".jpg");
            return true;
        } catch (IllegalArgumentException e) {
            fail("INVALID_SOURCE", e.getMessage());
        } catch (java.io.FileNotFoundException e) {
            fail("FILE_NOT_FOUND", "本地图片不存在或不可读取");
        } catch (SecurityException e) {
            fail("PERMISSION_DENIED", "没有读取该本地图片的权限");
        } catch (Exception e) {
            fail("SAVE_FAILED", "读取图片失败：" + e.getMessage());
        }
        return false;
    }

    private InputStream openSource(String source) throws Exception {
        if (source.startsWith("data:")) {
            int comma = source.indexOf(',');
            String header = comma < 0 ? "" : source.substring(0, comma).toLowerCase(Locale.ROOT);
            if (!header.matches("data:image/(png|jpe?g);base64")) {
                throw new IllegalArgumentException("仅支持 PNG/JPEG base64 dataURL");
            }
            return new ByteArrayInputStream(Base64.decode(source.substring(comma + 1), Base64.DEFAULT));
        }
        Uri uri = source.startsWith("/") ? Uri.fromFile(new File(source)) : Uri.parse(source);
        if (!("file".equals(uri.getScheme()) || "content".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("仅支持本地文件，不下载远程图片");
        }
        return resources.openForRead(resources.remapUri(uri)).inputStream;
    }

    void write() {
        if (Build.VERSION.SDK_INT >= 29) writeMediaStore();
        else writeLegacy();
    }

    private void writeMediaStore() {
        Uri uri = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Seer");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new java.io.IOException("无法创建相册记录");
            try (InputStream input = new FileInputStream(staged);
                 OutputStream output = context.getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new java.io.IOException("无法打开相册文件");
                copy(input, output);
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (context.getContentResolver().update(uri, values, null, null) != 1) {
                throw new java.io.IOException("无法发布相册记录");
            }
            // MediaStore 可能为同名文件重命名，返回实际文件名。
            try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                new String[]{MediaStore.Images.Media.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) fileName = cursor.getString(0);
            }
            succeed(uri);
        } catch (Exception e) {
            if (uri != null) {
                try { context.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
            }
            fail(e instanceof SecurityException ? "PERMISSION_DENIED" : "SAVE_FAILED", "保存相册失败：" + e.getMessage());
        }
    }

    private void writeLegacy() {
        File target = null;
        try {
            File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Seer");
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) throw new java.io.IOException("无法创建图片目录");
            File candidate = new File(directory, fileName);
            // 不覆盖已有图片，包括并发保存同名图片。
            if (!candidate.createNewFile()) {
                int dot = fileName.lastIndexOf('.');
                target = File.createTempFile("seer-" + fileName.substring(0, dot) + "-", fileName.substring(dot), directory);
            } else {
                target = candidate;
            }
            fileName = target.getName();
            try (InputStream input = new FileInputStream(staged); OutputStream output = new FileOutputStream(target)) {
                copy(input, output);
            }
            final File saved = target;
            MediaScannerConnection.scanFile(context, new String[]{target.getAbsolutePath()}, new String[]{mimeType}, (path, uri) -> {
                if (uri == null) {
                    saved.delete();
                    fail("SAVE_FAILED", "图片未被系统相册索引，请重试");
                } else {
                    succeed(uri);
                }
            });
        } catch (Exception e) {
            if (target != null) target.delete();
            fail(e instanceof SecurityException ? "PERMISSION_DENIED" : "SAVE_FAILED", "保存相册失败：" + e.getMessage());
        }
    }

    private static void copy(InputStream input, OutputStream output) throws java.io.IOException {
        byte[] buffer = new byte[64 * 1024];
        int length;
        while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
    }

    private void succeed(Uri uri) {
        cleanup();
        try {
            callback.success(new JSONObject().put("uri", uri.toString()).put("fileName", fileName).put("mimeType", mimeType));
        } catch (JSONException e) {
            fail("SAVE_FAILED", e.getMessage());
        }
    }

    void fail(String code, String message) {
        cleanup();
        try { callback.error(new JSONObject().put("code", code).put("message", message == null ? code : message)); }
        catch (JSONException ignored) { callback.error(code); }
    }

    private void cleanup() {
        if (staged != null) staged.delete();
    }
}
