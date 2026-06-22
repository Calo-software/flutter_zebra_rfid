//
//  RfidCertificatesList.h
//  symbolrfid-sdk
//
//  Created by Madesan Venkatraman on 21/08/24.
//  Copyright © 2024 Motorola Solutions. All rights reserved.
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface srfidCertificatesList : NSObject
{
    NSString * certName;
    NSString * certSize;
    NSString * certType;
    NSString * certIssuerName;
    NSString * certKeyAlg;
    NSString * certKey;
    NSString * certSerialNo;
    NSString * certSubjectName;
    NSString * certValidFrom;
    NSString * certValidTill;
}

- (NSString*)getCertName;
- (void)setCertName:(NSString*)val;
- (NSString*)getCertSize;
- (void)setCertSize:(NSString*)val;
- (NSString*)getCertType;
- (void)setCertType:(NSString*)val;
- (NSString*)getCertIssuerName;
- (void)setCertIssuerName:(NSString*)val;
- (NSString*)getCertKeyAlg;
- (void)setCertKeyAlg:(NSString*)val;
- (NSString*)getCertKey;
- (void)setCertKey:(NSString*)val;
- (NSString*)getCertSerialNo;
- (void)setCertSerialNo:(NSString*)val;
- (NSString*)getCertSubjectName;
- (void)setCertSubjectName:(NSString*)val;
- (NSString*)getCertValidFrom;
- (void)setCertValidFrom:(NSString*)val;
- (NSString*)getCertValidTill;
- (void)setCertValidTill:(NSString*)val;
@end

NS_ASSUME_NONNULL_END
