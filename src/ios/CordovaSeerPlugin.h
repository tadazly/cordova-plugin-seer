#import <Cordova/CDV.h>

@interface CordovaSeerPlugin : CDVPlugin

- (void)saveImageToAlbum:(CDVInvokedUrlCommand *_Nonnull)command;
- (void)getPhotoAlbumPermissionStatus:(CDVInvokedUrlCommand *_Nonnull)command;
- (void)openAppSettings:(CDVInvokedUrlCommand *_Nonnull)command;

- (void)isPrimaryAppIcon:(CDVInvokedUrlCommand *_Nonnull)command;
- (void)setAppIcon:(CDVInvokedUrlCommand *_Nonnull)command;
- (void)getCurrentAppIconName:(CDVInvokedUrlCommand *_Nonnull)command;
- (void)getSafeAreaXywh:(CDVInvokedUrlCommand *_Nonnull)command;
@end
