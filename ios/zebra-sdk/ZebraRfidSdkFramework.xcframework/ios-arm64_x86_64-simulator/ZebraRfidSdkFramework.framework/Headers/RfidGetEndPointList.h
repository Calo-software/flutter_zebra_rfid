//
//  RfidGetEndPointList.h
//  SymbolRfidSdk
//
//  Created by Madesan Venkatraman on 12/09/24.
//  Copyright © 2024 Motorola Solutions. All rights reserved.
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface RfidGetEndPointList : NSObject
{
    NSString *endPointName;
}

- (NSString*)getEndPointName;
- (void)setEndPointName:(NSString*)val;
@end

NS_ASSUME_NONNULL_END
