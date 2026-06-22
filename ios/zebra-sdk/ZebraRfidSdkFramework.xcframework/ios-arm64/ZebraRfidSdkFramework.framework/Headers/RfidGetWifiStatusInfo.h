//
//  RfidGetWifiStatusInfo.h
//  symbolrfid-sdk
//
//  Created by Dhanushka Adrian on 2023-06-16.
//  Copyright © 2023 Motorola Solutions. All rights reserved.
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface srfidGetWifiStatusInfo : NSObject
{
    NSMutableDictionary *statusDictionary;
}

- (NSMutableDictionary*)getStatusDictionary;
- (void)setStatusDictionary:(NSMutableDictionary*)val;


@end

NS_ASSUME_NONNULL_END
