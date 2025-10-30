#import <Cordova/CDV.h>

@interface CordovaSeerPlugin : CDVPlugin

- (void)isPrimaryAppIcon:(CDVInvokedUrlCommand *_Nonnull)command;
- (void)setAppIcon:(CDVInvokedUrlCommand *_Nonnull)command;
- (void)getCurrentAppIconName:(CDVInvokedUrlCommand *_Nonnull)command;
@end
