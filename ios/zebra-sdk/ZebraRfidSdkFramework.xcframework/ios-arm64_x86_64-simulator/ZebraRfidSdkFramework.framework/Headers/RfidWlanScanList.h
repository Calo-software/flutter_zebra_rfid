//
//  RfidWlanScanList.h
//  symbolrfid-sdk
//
//  Created by Madesan Venkatraman on 20/06/23.
//  Copyright © 2023 Motorola Solutions. All rights reserved.
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface srfidWlanScanList : NSObject
{
    NSString *wlanSSID;
    NSString *wlanProtocol;
    NSString *wlanLevel;
    NSString *wlanMacAddress;
  
}

- (NSString*)getWlanMacAddress;
- (void)setWlanMacAddress:(NSString*)val;

- (NSString*)getWlanSSID;
- (void)setWlanSSID:(NSString*)val;

- (NSString*)getWlanProtocol;
- (void)setWlanProtocol:(NSString*)val;
   
- (NSString*)getWlanLevel;
- (void)setWlanLevel:(NSString*)val;



@end

NS_ASSUME_NONNULL_END
