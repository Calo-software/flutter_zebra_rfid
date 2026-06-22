//
//  RfidGetActiveEnpoints.h
//  SymbolRfidSdk
//
//  Created by Madesan Venkatraman on 29/10/24.
//  Copyright © 2024 Motorola Solutions. All rights reserved.
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface srfidGetActiveEnpoints : NSObject
{
    NSString *activemgmtep;
    NSString *activemgmtevtep;
    NSString *activectrlep;
    NSString *activedat1ep;
    NSString *activedat2ep;
    NSString *backupmgmtep;
    NSString *backupmgmtevtep;
    NSString *backupctrlep;
    NSString *backupdat1ep;
    NSString *backupdat2ep;
}
- (NSString*)getActivemgmtep;
- (void)setActivemgmtep:(NSString*)val;
- (NSString*)getActivemgmtevtep;
- (void)setActivemgmtevtep:(NSString*)val;
- (NSString*)getActivectrlep;
- (void)setActivectrlep:(NSString*)val;
- (NSString*)getActivedat1ep;
- (void)setActivedat1ep:(NSString*)val;
- (NSString*)getActivedat2ep;
- (void)setActivedat2ep:(NSString*)val;
- (NSString*)getBackupmgmtep;
- (void)setBackupmgmtep:(NSString*)val;
- (NSString*)getBackupmgmtevtep;
- (void)setBackupmgmtevtep:(NSString*)val;
- (NSString*)getBackupctrlep;
- (void)setBackupctrlep:(NSString*)val;
- (NSString*)getBackupdat1ep;
- (void)setBackupdat1ep:(NSString*)val;
- (NSString*)getBackupdat2ep;
- (void)setBackupdat2ep:(NSString*)val;

@end

NS_ASSUME_NONNULL_END
