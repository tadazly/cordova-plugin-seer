# cordova-plugin-seer

cordova app 通用接口

## 安装

### 命令行安装

- npm

``` shell
cordova plugin add cordova-plugin-seer

cordova plugin rm cordova-plugin-seer
```

- git

``` shell
cordova plugin add https://github.com/tadazly/cordova-plugin-seer.git

cordova plugin rm cordova-plugin-seer
```

- 本地调试

```shell
cordova plugin add /path/to/cordova-plugin-seer \
  --link

cordova plugin rm cordova-plugin-seer
```

## 工具

### 保存图片到系统相册

#### 保存前查询权限

```ts
const permission = await CordovaSeer.getPhotoAlbumPermissionStatus();
if (permission.canSave || permission.canRequest) {
    // 显示或启用保存按钮；实际保存仍须由用户点击触发，并处理保存接口的错误。
} else if (permission.status === "denied") {
    // 展示“去设置”按钮，不在查询后自动跳转。
}

// “去设置”按钮的点击回调：
function onGoToSettingsClick() {
    return CordovaSeer.openAppSettings();
}
```

查询不会弹授权框、写入测试图片或修改权限。返回 `{ status, canSave, canRequest, reason? }`：

| status | 含义 | canSave | canRequest |
| --- | --- | --- | --- |
| `notRequired` | Android 10+ 保存本 App 生成的图片无需存储授权 | true | false |
| `granted` | 当前已获得保存所需权限 | true | false |
| `notDetermined` | iOS 14+ 尚未选择是否允许添加 | false | true |
| `denied` | 已拒绝；旧 Android 若仍可再次询问，canRequest 为 true | false | 按系统状态 |
| `restricted` | iOS 家长控制或设备策略限制 | false | false |
| `unavailable` | 安装包缺少必须的权限声明或用途说明 | false | false |
| `unknown` | 旧系统无法可靠查询“仅添加”或此前申请状态 | false | true |

- `canSave` 仅表示当前权限条件满足，不能保证磁盘有空间或图片写入一定成功；`canRequest` 表示保存时可以尝试申请，不保证弹框。
- `unavailable.reason` 为 `MISSING_PERMISSION`（旧 Android 缺少存储读写声明）或 `MISSING_USAGE_DESCRIPTION`（iOS 缺少添加用途说明），需修正原生包。
- iOS 14+ 查询 `PHAccessLevelAddOnly`，不会因没有相册读取权限而误判。iOS 13 没有等价公开查询，返回 `unknown` 并允许尝试保存。
- 旧 Android 通过实际写入授权、系统 rationale 和本插件申请记录判断；未记录且无法区分首次申请与其他组件造成的永久拒绝时返回 `unknown`。授权结果不缓存，每次查询重新读取系统状态。
- `openAppSettings()` 只打开本 App 的系统设置页，不修改授权；从设置返回后重新查询。系统限制或包配置缺失不应靠反复申请解决。
- 接入游戏时不以旧 `PermissionsManager.canWriteExternalStorage` 作为保存前置条件；保存被拒绝只提示当前功能受限，不退出或重登游戏。权限说明应写明“用户主动保存截图、攻略到相册”，无需为保存申请读取整个相册的权限。

#### 执行保存

在 `deviceready` 后调用，返回 Promise。两端共用同一接口，无需淘米 SDK：

```ts
const jpg = renderTexture.toDataURL("image/jpeg", undefined, 0.9);
try {
    const saved = await CordovaSeer.saveImageToAlbum(jpg, {
        fileName: "精灵攻略.jpg"
    });
    console.log("已保存到相册", saved.uri, saved.fileName, saved.mimeType);
} catch (error) {
    const failure = error as CordovaSeer.SaveImageError;
    console.error(failure.code, failure.message);
}
```

也可以传已经写好的文件：

```ts
await CordovaSeer.saveImageToAlbum(fileEntry.nativeURL, { fileName: "精灵攻略.png" });
```

- `source`：PNG/JPEG 的 base64 dataURL、`file://` 或绝对路径。安装 `cordova-plugin-file` 后也支持 `cdvfile://` 和 `FileEntry.toURL()` 的 localhost 地址。Android 额外支持已有读取权限的 `content://`。HTTP(S) 远程图片需先下载为本地文件；不支持裸 base64。
- `options.fileName`：可省略，默认生成唯一文件名；最多 120 字符，不含目录。扩展名按文件实际格式修正；Android 同名文件不会覆盖。
- 返回 `{ uri, fileName, mimeType }`。Android 的 `uri` 为 MediaStore `content://`；iOS 为 `ph://` 加 Photos 的 localIdentifier。这些是相册资源标识，不能作为普通文件路径交给 File 插件。
- 原样复制 PNG/JPEG，不缩放、不重新编码，也不删除调用方文件。大图优先使用本地路径，减少 base64 跨 Cordova 桥接的内存占用。此接口不参与 Egret 绘图，不能绕过图片生成时的纹理和内存限制。
- Android 10+ 使用 MediaStore，保存至 `Pictures/Seer`，无需相册读取或存储权限。Android 7–9 首次保存按需请求旧版存储读写权限；插件声明 `READ_EXTERNAL_STORAGE`、`WRITE_EXTERNAL_STORAGE` 且都限定 `maxSdkVersion=28`，构建脚本须保留这些带版本限制的声明。Lite 项目的共享 `after_prepare` 脚本 `remove_permissions.js` 会合并各插件的同名存储声明：如 DYGame 需要无限制读取权限，则保留该声明；多个有限上限取最大值。卸载渠道插件后，下一次 `prepare` 按当前插件清单恢复相册的受限声明，无需临时修改脚本。
- iOS 使用 Photos，iOS 14+ 仅申请添加权限；iOS 13 在首次创建图片时由系统申请添加权限。插件自动补充 `NSPhotoLibraryAddUsageDescription`，不为保存功能请求相册读取权限。
- 错误为 `{ code, message }`：`INVALID_ARGUMENT`、`INVALID_SOURCE`、`FILE_NOT_FOUND`、`INVALID_IMAGE`、`PERMISSION_DENIED`、`MISSING_PERMISSION`、`MISSING_USAGE_DESCRIPTION`、`SAVE_FAILED`、`BUSY`。设置页打开失败返回 `OPEN_SETTINGS_FAILED`。拒绝授权后由用户在系统设置中恢复权限，再重试即可。

TypeScript 项目可通过 `/// <reference types="cordova-plugin-seer" />` 引入声明，或将 `types/index.d.ts` 纳入 `tsconfig.json`。也可在 JS 中用别名 `cordova.plugins.Seer.saveImageToAlbum(...)` 调用。

新增原生代码和权限声明需要重新构建、安装 App；仅替换网页 JS 无法让旧包获得此接口。插件更新后，已有平台需重新安装插件，再 prepare/build：

```sh
# 相对于各 Cordova 项目根目录；本地插件路径按实际工作区调整
cordova plugin rm cordova-plugin-seer
cordova plugin add /Users/luyi/luyispace/plugins/cordova-plugin-seer --link
cordova prepare
```

桥接参数检查可在插件根目录执行 `npm test`；原生相册写入还需要模拟器或真机验收。

### 切换 App 图标

#### iOS 准备工作

- 图标集：默认的为 AppIcon ，其他的为 AppIcon_XXX

- Build Settings -> Include All App Icon Assets: **YES**

- Build Settings -> Primary App Icon Set Name: **图标集名称**

#### Android 准备工作

- 把 `<activity android:name="MainActivity" ...>` 部分改成 `<activity-alias android:name=".LauncherDefault"` 和 `<activity-alias android:name=".LauncherXXX"`

#### 代码接口

- 判断当前图标是否为包体主图标
  - iOS: BuildSettings -> Primary App Icon Set Name
  - Android: activities-alias -> enabled: true

  ```ts
  CordovaSeer.isPrimaryAppIcon()
    .then(isPrimary => {
        console.log('当前图标是否为包体主图标:', isPrimary);
    });
  ```

- 设置 AppIconSetName

  ```ts
  CordovaSeer.setAppIcon('Anniversary')
      .then(success => {
          console.log('设置图标成功:', success);
      })
      .catch(err => {
        console.log('设置图标失败:', err);
      })
  ```

- 获取当前 AppIconSetName

  ```ts
  CordovaSeer.getCurrentAppIconName()
    .then(iconName => {
        console.log('当前图标名称:', iconName);
    });
  ```

## 安全区（SafeArea）

获取安全区并返回 `xywh`（像素坐标系，左上角为原点）

```ts
CordovaSeer.getSafeAreaXywh()
  .then(({ x, y, w, h, model, source, rotation, systemUiVisibility, isFullscreen }) => {
    console.log('safeArea xywh:', { x, y, w, h });
    console.log('device model/source:', { model, source });
    console.log('window env:', { rotation, systemUiVisibility, isFullscreen });
  });
```
