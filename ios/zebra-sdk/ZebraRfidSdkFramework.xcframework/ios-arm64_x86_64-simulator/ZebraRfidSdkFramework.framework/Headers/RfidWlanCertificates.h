//
//  RfidWlanCertificates.h
//  RFIDDemoApp
//
//  Created by Madesan Venkatraman on 29/02/24.
//  Copyright © 2024 Zebra Technologies Corp. and/or its affiliates. All rights reserved. All rights reserved.
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface srfidWlanCertificates : NSObject
{
    NSString *wlanFile;
}

- (NSString*)getWlanFile;
- (void)setWlanFile:(NSString*)val;
@end

NS_ASSUME_NONNULL_END

