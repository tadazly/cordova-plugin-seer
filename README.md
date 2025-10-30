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