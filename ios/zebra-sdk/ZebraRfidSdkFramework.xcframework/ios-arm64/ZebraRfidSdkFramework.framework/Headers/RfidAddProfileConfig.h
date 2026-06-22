//
//  RfidAddProfileConfig.h
//  RFIDDemoApp
//
//  Created by Madesan Venkatraman on 07/03/24.
//  Copyright © 2024 Zebra Technologies Corp. and/or its affiliates. All rights reserved. All rights reserved.
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface sRfidAddProfileConfig : NSObject
{
    NSString *SSID;
    NSString *protocol;
    NSString *EAP;
    NSString *CA_Certificate;
    NSString *identity;
    NSString *anonimous_Identity;
    NSString *private_Key;
    NSString *password;
    NSString *private_Password;
    NSString *client_Certificate;
    BOOL isHidden;
}
- (NSString*)getSSID;
- (void)setSSID:(NSString*)val;
- (NSString*)getProtocol;
- (void)setProtocol:(NSString*)val;
- (NSString*)getEAP;
- (void)setEAP:(NSString*)val;
- (NSString*)getCa_Certificate;
- (void)setCa_Certificate:(NSString*)val;
- (NSString*)getIdentity;
- (void)setIdentity:(NSString*)val;
- (NSString*)getAnonyIdentity;
- (void)setAnonyIdentity:(NSString*)val;
- (NSString*)getPrivateKey;
- (void)setPrivateKey:(NSString*)val;
- (NSString*)getPrivatePassword;
- (NSString*)getPassword;
- (void)setPassword:(NSString*)val;
- (void)setPrivatePassword:(NSString*)val;
- (NSString*)getClientCertificate;
- (void)setClientCertificate:(NSString*)val;
- (BOOL)getisHiddenSSID;
- (void)setisHiddenSSID:(BOOL)val;
@end

NS_ASSUME_NONNULL_END
