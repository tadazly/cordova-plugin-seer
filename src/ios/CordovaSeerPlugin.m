#import "CordovaSeerPlugin.h"

@implementation CordovaSeerPlugin

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
