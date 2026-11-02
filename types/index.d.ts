declare namespace CordovaSeer {
    interface PhotoAlbumPermissionStatus {
        status: 'notRequired' | 'granted' | 'notDetermined' | 'denied' | 'restricted' | 'unavailable' | 'unknown';
        /** 当前授权条件允许保存；不保证磁盘空间、图片内容或实际写入成功。 */
        canSave: boolean;
        /** 保存时可尝试申请授权；不保证系统一定弹框。unknown 时也为 true。 */
        canRequest: boolean;
        /** unavailable 时表示原生包配置缺失。 */
        reason?: 'MISSING_PERMISSION' | 'MISSING_USAGE_DESCRIPTION';
    }

    /**
     * 无弹框、无写入地查询相册添加权限，每次读取实时状态，不缓存授权结果。
     * iOS 13 返回 unknown；旧 Android 无法可靠区分首次申请与其他代码造成的永久拒绝时也返回 unknown。
     * 保存入口应允许 canSave || canRequest，不能仅以 canSave 决定是否显示按钮。
     */
    function getPhotoAlbumPermissionStatus(): Promise<PhotoAlbumPermissionStatus>;

    /** 用户点击“去设置”后调用。只打开本 App 设置，不修改权限；返回 App 后重新查询。 */
    function openAppSettings(): Promise<void>;

    interface SaveImageOptions {
        /** 相册中的文件名；不含路径，最多 120 字符。扩展名按实际 PNG/JPEG 自动修正。 */
        fileName?: string;
    }

    interface SaveImageResult {
        /** Android 为 content:// URI，iOS 为 ph:// + Photos localIdentifier；不是通用文件路径。 */
        uri: string;
        fileName: string;
        mimeType: 'image/png' | 'image/jpeg';
    }

    interface SaveImageError {
        code: 'INVALID_ARGUMENT' | 'INVALID_SOURCE' | 'FILE_NOT_FOUND' | 'INVALID_IMAGE' |
            'PERMISSION_DENIED' | 'MISSING_PERMISSION' | 'MISSING_USAGE_DESCRIPTION' | 'SAVE_FAILED' | 'BUSY' | 'OPEN_SETTINGS_FAILED';
        message: string;
    }

    /**
     * 保存 PNG/JPEG 到系统相册，原样导入，不缩放或重新编码。在 deviceready 后调用。
     * 支持 base64 dataURL、绝对路径、file://；cdvfile:// 和 FileEntry.toURL() 需要 File 插件。
     * Android 还支持已有读取权限的 content://。不下载 HTTP(S) 图片。
     * 大图优先传本地路径，避免 base64 跨桥内存开销。失败 reject SaveImageError。
     */
    function saveImageToAlbum(source: string, options?: SaveImageOptions): Promise<SaveImageResult>;

    /**
     * 检查当前图标是否为包体主图标
     * iOS: BuildSettings -> Primary App Icon Set Name
     * Android: activities-alias -> enabled: true
     */
    function isPrimaryAppIcon(): Promise<boolean>;

    /**
     * 设置icon
     * @param iconName 图标集名称 Default | Anniversary
     */
    function setAppIcon(iconName: string): Promise<boolean>;

    /**
     * 获取当前icon集名称
     */
    function getCurrentAppIconName(): Promise<string>;

    /**
     * 获取安全区 xywh（像素坐标系，左上角为原点）
     */
    function getSafeAreaXywh(): Promise<{
        x: number;
        y: number;
        w: number;
        h: number;
        model: string;
        brand: string;
        manufacturer: string;
        sdkInt: number;
        source: string;
        insets: { left: number; top: number; right: number; bottom: number };
        screenWidth: number;
        screenHeight: number;
        rotation: number;
        orientation: number;
        systemUiVisibility: number;
        isFullscreen: boolean;
        usedInsets: boolean;
        usedCutout: boolean;
        hasCutout: boolean;
        statusBarLikelyHidden: boolean;
        navigationBarLikelyHidden: boolean;
        cutoutSafeInsets: { safeLeft: number; safeTop: number; safeRight: number; safeBottom: number } | null;
    }>;
}
