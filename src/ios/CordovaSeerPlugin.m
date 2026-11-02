#import "CordovaSeerPlugin.h"
#import "SeerImageAlbum.h"

#import <UIKit/UIKit.h>

@implementation CordovaSeerPlugin

- (void)getPhotoAlbumPermissionStatus:(CDVInvokedUrlCommand *)command
{
    CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                         messageAsDictionary:[SeerImageAlbum permissionStatus]];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (void)openAppSettings:(CDVInvokedUrlCommand *)command
{
    dispatch_async(dispatch_get_main_queue(), ^{
        [UIApplication.sharedApplication openURL:[NSURL URLWithString:UIApplicationOpenSettingsURLString]
                                        options:@{} completionHandler:^(BOOL success) {
            CDVPluginResult *result = success
                ? [CDVPluginResult resultWithStatus:CDVCommandStatus_OK]
                : [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                              messageAsDictionary:@{@"code": @"OPEN_SETTINGS_FAILED", @"message": @"无法打开应用设置"}];
            [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
        }];
    });
}

- (void)saveImageToAlbum:(CDVInvokedUrlCommand *)command
{
    id source = [command argumentAtIndex:0];
    id name = [command argumentAtIndex:1];
    [SeerImageAlbum saveSource:source fileName:name completion:^(NSDictionary *value, BOOL success) {
        CDVPluginResult *result = [CDVPluginResult resultWithStatus:success ? CDVCommandStatus_OK : CDVCommandStatus_ERROR
                                              messageAsDictionary:value];
        [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
    }];
}

- (void)isPrimaryAppIcon:(CDVInvokedUrlCommand *_Nonnull)command
{
    CDVPluginResult *result;
    NSString *iconName = [UIApplication sharedApplication].alternateIconName;

    if (iconName == nil) {
        NSLog(@"当前使用的是包体主图标");
        result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsBool:true];
    } else {
        NSLog(@"当前是其他图标：%@", iconName);
        result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsBool:false];
    }
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (void)setAppIcon:(CDVInvokedUrlCommand *_Nonnull)command
{
    id arg = nil;
    if (command.arguments.count > 0) {
        arg = [command.arguments objectAtIndex:0];
    }
    
    NSString *appIconName = nil;
    
    if ([arg isKindOfClass:[NSString class]]) {
        appIconName = (NSString *)arg;
    } else if ([arg respondsToSelector:@selector(stringValue)]) {
        appIconName = [arg stringValue];
    }

    NSString *primaryIconName = [CordovaSeerPlugin getPrimaryIconName];
    if (appIconName == nil
        || appIconName.length == 0
        || [appIconName caseInsensitiveCompare:primaryIconName] == NSOrderedSame)
    {
        appIconName = nil;
    } else if ([appIconName caseInsensitiveCompare:@"Default"] == NSOrderedSame) {
        appIconName = @"AppIcon";
    } else {
        appIconName = [NSString stringWithFormat:@"AppIcon_%@", appIconName];
    }
    NSString *currentIcon = [UIApplication sharedApplication].alternateIconName;
    if (appIconName == currentIcon ||
        (appIconName != nil && currentIcon != nil && [appIconName caseInsensitiveCompare:currentIcon] == NSOrderedSame)) {
        CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsBool:false];
        [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
        return;
    }
    UIApplication* app = [UIApplication sharedApplication];
    [app setAlternateIconName:appIconName
                completionHandler:^(NSError * _Nullable error) {
        CDVPluginResult *result;
        if (error) {
            NSLog(@"切换失败：%@", error.localizedDescription);
            result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:error.localizedDescription];
        } else {
            NSLog(@"切换为图标：%@", appIconName);
            result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsBool:true];
        }
        [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
    }];
}

- (void)getCurrentAppIconName:(CDVInvokedUrlCommand *)command
{
    NSString *currentIcon = [UIApplication sharedApplication].alternateIconName;
    if (currentIcon) {
        NSLog(@"当前使用图标：%@", currentIcon);
    } else {
        NSDictionary *info = [[NSBundle mainBundle] infoDictionary];
        NSDictionary *icons = info[@"CFBundleIcons"];
        NSDictionary *primary = icons[@"CFBundlePrimaryIcon"];
        NSArray *iconFiles = primary[@"CFBundleIconFiles"];
        if (iconFiles.count == 0) {
            currentIcon = @"AppIcon";
        } else {
            currentIcon = [iconFiles lastObject];
        }
        NSLog(@"当前主图标资源名：%@", currentIcon);
    }
    CDVPluginResult *result;
    if (currentIcon) {
        NSString *resultName = [CordovaSeerPlugin normalizedIconShortName:currentIcon];
        result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:resultName];
    } else {
        result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"unknown"];
    }
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (void)getSafeAreaXywh:(CDVInvokedUrlCommand *_Nonnull)command
{
    UIViewController *vc = self.viewController;
    UIView *view = nil;
    if (vc && vc.view) {
        view = vc.view;
    } else if (self.webView && self.webView.superview) {
        // Fallback: 用 webview 的父容器视图计算
        view = self.webView.superview;
    } else {
        view = self.webView;
    }

    if (!view) {
        CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"view is null"];
        [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
        return;
    }

    CGFloat scale = [UIScreen mainScreen].scale;
    CGRect bounds = view.bounds;

    UIEdgeInsets safeInsets = UIEdgeInsetsZero;
    if (@available(iOS 11.0, *)) {
        safeInsets = view.safeAreaInsets;
    }

    BOOL usedInsets = !UIEdgeInsetsEqualToEdgeInsets(safeInsets, UIEdgeInsetsZero);

    // iOS 没有直接等价 Android DisplayCutout 的 API，这里用经验阈值推断“刘海/挖孔”安全区域
    // safeAreaInsets.top/bottom 在带刘海设备上通常显著大于纯状态栏/底栏的最小值。
    BOOL hasCutout = safeInsets.top > 20.0f || safeInsets.bottom > 0.0f;
    BOOL usedCutout = hasCutout;
    NSString *source = @"fallback-none";
    if (usedCutout) {
        source = @"cutout+insets";
    } else if (usedInsets) {
        source = @"insets";
    }

    CGFloat xPts = safeInsets.left;
    CGFloat yPts = safeInsets.top;
    CGFloat wPts = bounds.size.width - safeInsets.left - safeInsets.right;
    CGFloat hPts = bounds.size.height - safeInsets.top - safeInsets.bottom;

    if (wPts <= 0.0f || hPts <= 0.0f) {
        // 对应 Android 的兜底：避免返回 0 宽高导致前端无法计算布局
        xPts = 0.0f;
        yPts = 0.0f;
        wPts = bounds.size.width;
        hPts = bounds.size.height;
        source = @"fallback-fullscreen";
    }

    CGFloat xPx = xPts * scale;
    CGFloat yPx = yPts * scale;
    CGFloat wPx = wPts * scale;
    CGFloat hPx = hPts * scale;

    int screenWidthPx = (int)llround(bounds.size.width * scale);
    int screenHeightPx = (int)llround(bounds.size.height * scale);

    // orientation / rotation：尽量对齐安卓字段含义（1=portrait, 2=landscape）
    UIInterfaceOrientation io = vc ? vc.interfaceOrientation : UIInterfaceOrientationPortrait;
    BOOL isLandscape = (io == UIInterfaceOrientationLandscapeLeft || io == UIInterfaceOrientationLandscapeRight);
    int orientation = isLandscape ? 2 : 1;
    int rotation = 0;
    if (io == UIInterfaceOrientationLandscapeLeft) {
        rotation = 1;
    } else if (io == UIInterfaceOrientationLandscapeRight) {
        rotation = 3;
    } else {
        rotation = 0;
    }

    BOOL isFullscreen = (safeInsets.top <= 0.0f && safeInsets.bottom <= 0.0f);
    BOOL statusBarLikelyHidden = safeInsets.top <= 0.0f;
    BOOL navigationBarLikelyHidden = safeInsets.bottom <= 0.0f;

    NSMutableDictionary *out = [NSMutableDictionary dictionary];
    [out setObject:@((int)llround(xPx)) forKey:@"x"];
    [out setObject:@((int)llround(yPx)) forKey:@"y"];
    [out setObject:@((int)llround(wPx)) forKey:@"w"];
    [out setObject:@((int)llround(hPx)) forKey:@"h"];

    // 机型/系统信息（尽量对齐安卓字段）
    NSString *model = [UIDevice currentDevice].model;
    [out setObject:model ?: @"" forKey:@"model"];
    [out setObject:@"Apple" forKey:@"brand"];
    [out setObject:@"Apple" forKey:@"manufacturer"];

    NSString *systemVersion = [[UIDevice currentDevice] systemVersion];
    int sdkInt = (int)systemVersion.intValue;
    [out setObject:@(sdkInt) forKey:@"sdkInt"];
    [out setObject:source forKey:@"source"];

    [out setObject:@(screenWidthPx) forKey:@"screenWidth"];
    [out setObject:@(screenHeightPx) forKey:@"screenHeight"];
    [out setObject:@(rotation) forKey:@"rotation"];
    [out setObject:@(orientation) forKey:@"orientation"];

    // iOS 没有等价 systemUiVisibility 字段，给 0 用于兼容结构
    [out setObject:@(0) forKey:@"systemUiVisibility"];
    [out setObject:@(isFullscreen) forKey:@"isFullscreen"];
    [out setObject:@(usedInsets) forKey:@"usedInsets"];
    [out setObject:@(usedCutout) forKey:@"usedCutout"];
    [out setObject:@(hasCutout) forKey:@"hasCutout"];
    [out setObject:@(statusBarLikelyHidden) forKey:@"statusBarLikelyHidden"];
    [out setObject:@(navigationBarLikelyHidden) forKey:@"navigationBarLikelyHidden"];

    if (hasCutout) {
        NSDictionary *cutoutSafeInsets = @{
            @"safeLeft": @((int)llround(safeInsets.left * scale)),
            @"safeTop": @((int)llround(safeInsets.top * scale)),
            @"safeRight": @((int)llround(safeInsets.right * scale)),
            @"safeBottom": @((int)llround(safeInsets.bottom * scale))
        };
        [out setObject:cutoutSafeInsets forKey:@"cutoutSafeInsets"];
    } else {
        [out setObject:[NSNull null] forKey:@"cutoutSafeInsets"];
    }

    NSDictionary *insets = @{
        @"left": @((int)llround(safeInsets.left * scale)),
        @"top": @((int)llround(safeInsets.top * scale)),
        @"right": @((int)llround(safeInsets.right * scale)),
        @"bottom": @((int)llround(safeInsets.bottom * scale))
    };
    [out setObject:insets forKey:@"insets"];

    CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:out];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

+ (NSString *)getPrimaryIconName {
    NSDictionary *info = [[NSBundle mainBundle] infoDictionary];
    NSDictionary *icons = info[@"CFBundleIcons"];
    NSDictionary *primary = icons[@"CFBundlePrimaryIcon"];
    NSArray *iconFiles = primary[@"CFBundleIconFiles"];
    
    if (iconFiles.count == 0) return @"Default";
    
    NSString *rawName = iconFiles.lastObject;
   
    return [CordovaSeerPlugin normalizedIconShortName:rawName];
}

+ (NSString *)normalizedIconShortName:(NSString *)rawName {
    if (rawName == nil || rawName.length == 0) {
        return @"Default";
    }
    
    NSString *result = @"Default";
    
    NSRegularExpression *regex = [NSRegularExpression regularExpressionWithPattern:@"AppIcon_([A-Za-z0-9_]+)" options:0 error:nil];
    NSTextCheckingResult *match = [regex firstMatchInString:rawName options:0 range:NSMakeRange(0, rawName.length)];
    
    if (match && match.numberOfRanges > 1) {
        NSString *name = [rawName substringWithRange:[match rangeAtIndex:1]];
        // 去掉可能跟在后面的尺寸数字
        NSRange digitRange = [name rangeOfCharacterFromSet:[NSCharacterSet decimalDigitCharacterSet]];
        if (digitRange.location != NSNotFound) {
            name = [name substringToIndex:digitRange.location];
        }
        result = name.length > 0 ? name : @"Default";
    } else if ([rawName hasPrefix:@"AppIcon"]) {
        result = @"Default";
    }
    
    return result;
}

@end
