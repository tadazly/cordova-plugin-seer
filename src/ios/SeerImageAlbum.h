#import <Foundation/Foundation.h>

@interface SeerImageAlbum : NSObject
+ (NSDictionary *)permissionStatus;
+ (void)saveSource:(id)source fileName:(id)fileName completion:(void (^)(NSDictionary *value, BOOL success))completion;
@end
