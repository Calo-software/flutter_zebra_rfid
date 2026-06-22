//
//  RfidGetEndPointConfig.h
//  SymbolRfidSdk
//
//  Created by Madesan Venkatraman on 07/10/24.
//  Copyright © 2024 Motorola Solutions. All rights reserved.
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface srfidGetEndPointConfig : NSObject
{
    NSString *epname;
    NSString *type;
    NSString *protocol;
    NSString *url;
    NSString *port;
    NSString *keepalive;
    NSString *tenantid;
    Boolean encleanss;
    Boolean dscleanss;
    NSString *rcdelaymin;
    NSString *rcdelaymax;
    NSString *hostvfy;
    NSString *username;
    NSString *password;
    // MDM Support
    NSString *subname;
    NSString *pub1name;
    NSString *pub2name;
    NSString *cacertname;
    NSString *certname;
    NSString *keyname;
}

- (NSString*)getepname;
- (void)setepname:(NSString*)val;
- (NSString*)getType;
- (void)setType:(NSString*)val;
- (NSString*)getProtocol;
- (void)setProtocol:(NSString*)val;
- (NSString*)getURL;
- (void)setURL:(NSString*)val;
- (NSString*)getPort;
- (void)setPort:(NSString*)val;
- (NSString*)getKeepalive;
- (void)setKeepalive:(NSString*)val;
- (NSString*)getTenantid;
- (void)setTenantid:(NSString*)val;
- (Boolean)getEncleanss;
- (void)setEncleanss:(Boolean)val;
- (Boolean)getDscleanss;
- (void)setDscleanss:(Boolean)val;
- (NSString*)getRcdelaymin;
- (void)setRcdelaymin:(NSString*)val;
- (NSString*)getRcdelaymax;
- (void)setRcdelaymax:(NSString*)val;
- (NSString*)getHostvfy;
- (void)setHostvfy:(NSString*)val;
- (NSString*)getUserName;
- (void)setUserName:(NSString*)val;
- (NSString*)getPassword;
- (void)setPassword:(NSString*)val;
// MDM Support
- (NSString*)getSubname;
- (void)setSubname:(NSString*)val;
- (NSString*)getPub1name;
- (void)setPub1name:(NSString*)val;
- (NSString*)getPub2name;
- (void)setPub2name:(NSString*)val;
- (NSString*)getCACertname;
- (void)setCACertname:(NSString*)val;
- (NSString*)getCertname;
- (void)setCertname:(NSString*)val;
- (NSString*)getKeyname;
- (void)setKeyname:(NSString*)val;
@end

NS_ASSUME_NONNULL_END
