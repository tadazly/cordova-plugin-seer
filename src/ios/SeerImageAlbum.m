#import "SeerImageAlbum.h"
#import <Photos/Photos.h>
#import <ImageIO/ImageIO.h>

@implementation SeerImageAlbum

+ (NSDictionary *)permissionStatus
{
    NSString *usage = [NSBundle.mainBundle objectForInfoDictionaryKey:@"NSPhotoLibraryAddUsageDescription"];
    if (![usage isKindOfClass:NSString.class] || !usage.length) {
        return @{@"status": @"unavailable", @"canSave": @NO, @"canRequest": @NO, @"reason": @"MISSING_USAGE_DESCRIPTION"};
    }
    NSString *state = @"unknown";
    if (@available(iOS 14, *)) {
        switch ([PHPhotoLibrary authorizationStatusForAccessLevel:PHAccessLevelAddOnly]) {
            case PHAuthorizationStatusAuthorized:
            case PHAuthorizationStatusLimited: state = @"granted"; break;
            case PHAuthorizationStatusNotDetermined: state = @"notDetermined"; break;
            case PHAuthorizationStatusDenied: state = @"denied"; break;
            case PHAuthorizationStatusRestricted: state = @"restricted"; break;
        }
    }
    // iOS 13 的旧 authorizationStatus 查询的是读写权限，不能代替仅添加权限。
    // 不为查询触发授权或扩权，允许调用方在点击保存后尝试写入。
    return @{@"status": state,
             @"canSave": @([state isEqualToString:@"granted"]),
             @"canRequest": @((BOOL)([state isEqualToString:@"notDetermined"] || [state isEqualToString:@"unknown"]))};
}

+ (void)saveSource:(id)source fileName:(id)fileName completion:(void (^)(NSDictionary *, BOOL))completion
{
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        NSFileManager *manager = [NSFileManager defaultManager];
        __block NSURL *staged = [NSURL fileURLWithPath:[NSTemporaryDirectory() stringByAppendingPathComponent:
            [NSString stringWithFormat:@"seer-album-%@.tmp", NSUUID.UUID.UUIDString]]];
        void (^fail)(NSString *, NSString *) = ^(NSString *code, NSString *message) {
            [manager removeItemAtURL:staged error:nil];
            completion(@{@"code": code, @"message": message ?: code}, NO);
        };
        if (![source isKindOfClass:NSString.class] || ![source length] ||
            ![fileName isKindOfClass:NSString.class] || [fileName length] > 120 ||
            [fileName rangeOfCharacterFromSet:[NSCharacterSet characterSetWithCharactersInString:@"/\\"]].location != NSNotFound ||
            [fileName rangeOfCharacterFromSet:NSCharacterSet.controlCharacterSet].location != NSNotFound) {
            fail(@"INVALID_ARGUMENT", @"source 或 fileName 无效");
            return;
        }
        NSDictionary *permission = [self permissionStatus];
        if ([permission[@"status"] isEqualToString:@"unavailable"]) {
            fail(@"MISSING_USAGE_DESCRIPTION", @"安装包缺少 NSPhotoLibraryAddUsageDescription，请更新原生包");
            return;
        }
        if ([permission[@"status"] isEqualToString:@"denied"] || [permission[@"status"] isEqualToString:@"restricted"]) {
            fail(@"PERMISSION_DENIED", @"未获得相册添加权限，请在系统设置中允许后重试");
            return;
        }
        NSError *error = nil;
        if ([source hasPrefix:@"data:"]) {
            NSRange comma = [source rangeOfString:@","];
            NSString *header = comma.location == NSNotFound ? @"" : [[source substringToIndex:comma.location] lowercaseString];
            if (![@[@"data:image/png;base64", @"data:image/jpeg;base64", @"data:image/jpg;base64"] containsObject:header]) {
                fail(@"INVALID_SOURCE", @"仅支持 PNG/JPEG base64 dataURL");
                return;
            }
            NSData *data = [[NSData alloc] initWithBase64EncodedString:[source substringFromIndex:comma.location + 1] options:0];
            if (!data.length) {
                fail(@"INVALID_IMAGE", @"图片 base64 数据无效");
                return;
            }
            [data writeToURL:staged options:NSDataWritingAtomic error:&error];
        } else {
            NSURL *url = [source hasPrefix:@"/"] ? [NSURL fileURLWithPath:source] : [NSURL URLWithString:source];
            if (!url.isFileURL) {
                fail(@"INVALID_SOURCE", @"仅支持本地图片文件，不下载远程图片");
                return;
            }
            if (![manager isReadableFileAtPath:url.path]) {
                fail(@"FILE_NOT_FOUND", @"本地图片不存在或不可读取");
                return;
            }
            [manager copyItemAtURL:url toURL:staged error:&error];
        }
        if (error) {
            fail(@"SAVE_FAILED", error.localizedDescription);
            return;
        }
        // 只读取格式和尺寸元数据，不创建 UIImage 或整图像素缓冲。
        CGImageSourceRef image = CGImageSourceCreateWithURL((__bridge CFURLRef)staged,
            (__bridge CFDictionaryRef)@{(__bridge NSString *)kCGImageSourceShouldCache: @NO});
        NSString *type = image ? (__bridge NSString *)CGImageSourceGetType(image) : nil;
        NSDictionary *properties = image ? CFBridgingRelease(CGImageSourceCopyPropertiesAtIndex(image, 0, NULL)) : nil;
        BOOL png = [type isEqualToString:@"public.png"];
        BOOL jpeg = [type isEqualToString:@"public.jpeg"];
        if (image) CFRelease(image);
        if ((!png && !jpeg) || [properties[(__bridge NSString *)kCGImagePropertyPixelWidth] integerValue] <= 0 ||
            [properties[(__bridge NSString *)kCGImagePropertyPixelHeight] integerValue] <= 0) {
            fail(@"INVALID_IMAGE", @"文件不是有效的 PNG/JPEG 图片");
            return;
        }
        NSString *stem = [fileName stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
        if ([@[@"png", @"jpg", @"jpeg"] containsObject:stem.pathExtension.lowercaseString]) stem = stem.stringByDeletingPathExtension;
        if (!stem.length) stem = [@"seer-" stringByAppendingString:NSUUID.UUID.UUIDString];
        NSString *name = [stem stringByAppendingString:png ? @".png" : @".jpg"];
        NSString *mime = png ? @"image/png" : @"image/jpeg";
        NSURL *typedFile = [staged URLByAppendingPathExtension:png ? @"png" : @"jpg"];
        if (![manager moveItemAtURL:staged toURL:typedFile error:&error]) {
            fail(@"SAVE_FAILED", error.localizedDescription);
            return;
        }
        staged = typedFile;
        void (^save)(void) = ^{
            __block NSString *identifier;
            [PHPhotoLibrary.sharedPhotoLibrary performChanges:^{
                PHAssetCreationRequest *request = [PHAssetCreationRequest creationRequestForAsset];
                PHAssetResourceCreationOptions *options = [PHAssetResourceCreationOptions new];
                options.originalFilename = name;
                // 明确原始格式，Photos 直接导入编码后的资源。
                options.uniformTypeIdentifier = png ? @"public.png" : @"public.jpeg";
                options.shouldMoveFile = NO;
                [request addResourceWithType:PHAssetResourceTypePhoto fileURL:staged options:options];
                identifier = request.placeholderForCreatedAsset.localIdentifier;
            } completionHandler:^(BOOL success, NSError *saveError) {
                [manager removeItemAtURL:staged error:nil];
                if (success && identifier.length) {
                    completion(@{@"uri": [@"ph://" stringByAppendingString:identifier], @"fileName": name, @"mimeType": mime}, YES);
                } else {
                    BOOL denied = NO;
                    if (@available(iOS 15, *)) {
                        denied = [saveError.domain isEqualToString:PHPhotosErrorDomain] &&
                            (saveError.code == PHPhotosErrorAccessUserDenied || saveError.code == PHPhotosErrorAccessRestricted);
                    } else {
                        PHAuthorizationStatus status = PHPhotoLibrary.authorizationStatus;
                        denied = status == PHAuthorizationStatusDenied || status == PHAuthorizationStatusRestricted;
                    }
                    fail(denied ? @"PERMISSION_DENIED" : @"SAVE_FAILED", saveError.localizedDescription ?: @"保存相册失败");
                }
            }];
        };
        if (@available(iOS 14, *)) {
            dispatch_async(dispatch_get_main_queue(), ^{
                [PHPhotoLibrary requestAuthorizationForAccessLevel:PHAccessLevelAddOnly handler:^(PHAuthorizationStatus status) {
                    if (status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited) save();
                    else fail(@"PERMISSION_DENIED", @"未获得相册添加权限，请在系统设置中允许后重试");
                }];
            });
        } else {
            // iOS 11–13 在首次创建资源时请求“仅添加”，不请求读取整个相册。
            save();
        }
    });
}
@end
