var exec = require('cordova/exec');

module.exports = {
    execAsync(func, param) {
        var args = Array.prototype.slice.call(arguments);
        return new Promise(function(resolve, reject) {
            args.unshift('CordovaSeer');
            args.unshift(function(err) {
                reject(err);
            });
            args.unshift(function(result) {
                resolve(result);
            });
            exec.apply(this, args);
        });
    },

    isPrimaryAppIcon() {
        return this.execAsync('isPrimaryAppIcon');
    },

    setAppIcon(iconName) {
        return this.execAsync('setAppIcon', [iconName]);
    },

    getCurrentAppIconName() {
        return this.execAsync('getCurrentAppIconName');
    },

    /**
     * 获取安全区 xywh（像素坐标系，左上角为原点）
     * @returns {Promise<{x:number,y:number,w:number,h:number}>}
     */
    getSafeAreaXywh() {
        return this.execAsync('getSafeAreaXywh');
    },

    /** 只查询相册添加权限，不弹框、不写文件。 */
    getPhotoAlbumPermissionStatus() {
        return this.execAsync('getPhotoAlbumPermissionStatus', []);
    },

    /** 由用户点击“去设置”后调用；打开本 App 的系统设置页。 */
    openAppSettings() {
        return this.execAsync('openAppSettings', []).then(function() {});
    },

    /** 保存已生成的 PNG/JPEG，保留原始编码；须在 deviceready 后调用。 */
    saveImageToAlbum(source, options) {
        function invalid(message) {
            return Promise.reject({ code: 'INVALID_ARGUMENT', message: message });
        }
        if (typeof source !== 'string' || !source.length) {
            return invalid('source 必须是 PNG/JPEG dataURL 或本地图片路径');
        }
        options = options === undefined ? {} : options;
        if (!options || typeof options !== 'object' || Array.isArray(options)) {
            return invalid('options 必须是对象');
        }
        var name = options.fileName;
        if (name !== undefined && (typeof name !== 'string' || !name.trim() ||
            name.length > 120 || /[\\/\x00-\x1f]/.test(name) || /^\.+$/.test(name))) {
            return invalid('fileName 必须是不含路径的文件名（最多 120 字符）');
        }
        var self = this;
        function save(path) {
            return self.execAsync('saveImageToAlbum', [path, name || '']);
        }
        // FileEntry.toURL() 在两端可能返回 cdvfile:// 或 WebView 虚拟地址。
        // 通过已安装的 File 插件转换为 nativeURL，原生层不下载网络 URL。
        if (/^cdvfile:/i.test(source) || /^https?:\/\/(localhost|127\.0\.0\.1)([:/]|$)/i.test(source)) {
            return new Promise(function(resolve, reject) {
                if (typeof resolveLocalFileSystemURL !== 'function') {
                    reject({ code: 'INVALID_SOURCE', message: '该路径需要 cordova-plugin-file' });
                    return;
                }
                resolveLocalFileSystemURL(source, function(entry) {
                    if (!entry.isFile || !entry.nativeURL || !/^file:/i.test(entry.nativeURL)) {
                        reject({ code: 'INVALID_SOURCE', message: 'source 不是可读取的本地文件' });
                        return;
                    }
                    resolve(save(entry.nativeURL));
                }, function() {
                    reject({ code: 'FILE_NOT_FOUND', message: '无法读取本地图片' });
                });
            });
        }
        if (!/^(data:image\/(png|jpe?g);base64,|file:\/\/|content:\/\/|\/)/i.test(source)) {
            return invalid('仅支持 PNG/JPEG base64 dataURL 或本地文件，不支持远程 URL');
        }
        return save(source);
    },
}
