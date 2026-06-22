//
//  RfidIOTStatusEvent.h
//  symbolrfid-sdk
//
//  Created by Madesan Venkatraman on 28/04/25.
//  Copyright © 2025 Motorola Solutions. All rights reserved.
//

#import <Foundation/Foundation.h>
#import "RfidSdkDefs.h"
NS_ASSUME_NONNULL_BEGIN

@interface srfidIOTStatusEvent : NSObject
{
    NSMutableString *m_Cause;
    NSMutableString *m_EpType;
    NSMutableString *m_EpName;
    NSMutableString *m_Status;
    NSMutableString *m_Reason;
}

- (NSString*)getCause;
- (void)setCause:(NSString*)val;
- (NSString*)getEpType;
- (void)setEpType:(NSString*)val;
- (NSString*)getEpName;
- (void)setEpName:(NSString*)val;
- (NSString*)getStatus;
- (void)setStatus:(NSString*)val;
- (NSString*)getReason;
- (void)setReason:(NSString*)val;
@end

NS_ASSUME_NONNULL_END
