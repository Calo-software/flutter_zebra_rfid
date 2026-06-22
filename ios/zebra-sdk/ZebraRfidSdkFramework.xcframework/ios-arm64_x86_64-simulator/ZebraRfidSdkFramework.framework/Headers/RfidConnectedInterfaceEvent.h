//
//  RfidConnectedInterfaceEvent.h
//  RFIDDemoApp
//
//  Created by Dhanushka, Adrian on 2025-05-02.
//  Copyright © 2025 Zebra Technologies Corp. and/or its affiliates. All rights reserved. All rights reserved.
//

#import <Foundation/Foundation.h>
#import "RfidSdkDefs.h"

NS_ASSUME_NONNULL_BEGIN

@interface sfidConnectedInterfaceEvent : NSObject{
    SRFID_CONNECTED_INTERFACE_TYPE m_Conneted_Interface_Type;
}

- (SRFID_CONNECTED_INTERFACE_TYPE)getConneted_Interface_Type;
- (void)setConneted_Interface_Type:(SRFID_CONNECTED_INTERFACE_TYPE)val;
@end

NS_ASSUME_NONNULL_END
