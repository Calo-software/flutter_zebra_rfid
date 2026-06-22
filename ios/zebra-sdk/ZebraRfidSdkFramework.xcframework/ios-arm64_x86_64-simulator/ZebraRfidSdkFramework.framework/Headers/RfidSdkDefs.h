/******************************************************************************
 *
 *       Copyright Zebra Technologies, Inc. 2014 - 2015
 *
 *       The copyright notice above does not evidence any
 *       actual or intended publication of such source code.
 *       The code contains Zebra Technologies
 *       Confidential Proprietary Information.
 *
 *
 *  Description:  RfidSdkDefs.h
 *
 *  Notes:
 *
 ******************************************************************************/

#ifndef __RFID_SDK_DEFS__
#define __RFID_SDK_DEFS__

/* return values */
typedef enum {
    SRFID_RESULT_SUCCESS                   = 0x00,
    SRFID_RESULT_FAILURE                   = 0x01,
    SRFID_RESULT_READER_NOT_AVAILABLE      = 0x02,
    SRFID_RESULT_INVALID_PARAMS            = 0x04,
    SRFID_RESULT_RESPONSE_TIMEOUT          = 0x05,
    SRFID_RESULT_NOT_SUPPORTED             = 0x06,
    SRFID_RESULT_RESPONSE_ERROR            = 0x07,
    SRFID_RESULT_WRONG_ASCII_PASSWORD      = 0x08,
    SRFID_RESULT_ASCII_CONNECTION_REQUIRED = 0x09,
} SRFID_RESULT;

/* operating modes of SDK */
enum {
    SRFID_OPMODE_MFI                       = 0x01,
    SRFID_OPMODE_BTLE                      = 0x02,
    SRFID_OPMODE_ALL                       = 0x03
};

/* connection/device types */
enum {
    SRFID_CONNTYPE_INVALID                 = 0x00,
    SRFID_CONNTYPE_MFI                     = 0x01,
    SRFID_CONNTYPE_BTLE                    = 0x02
};

/* Batch Mode */

typedef enum
{
    SRFID_BATCHMODECONFIG_DISABLE                       = 0x00,
    SRFID_BATCHMODECONFIG_AUTO                         = 0x01,
    SRFID_BATCHMODECONFIG_ENABLE                      = 0x02,
} SRFID_BATCHMODECONFIG;


/* notifications/events masks */
enum {
    SRFID_EVENT_READER_APPEARANCE           = (0x01 << 1),
    SRFID_EVENT_READER_DISAPPEARANCE        = (0x01 << 2),
    SRFID_EVENT_SESSION_ESTABLISHMENT       = (0x01 << 3),
    SRFID_EVENT_SESSION_TERMINATION         = (0x01 << 4),
    SRFID_EVENT_MASK_READ                   = (0x01 << 5),
    SRFID_EVENT_MASK_STATUS                 = (0x01 << 6),
    SRFID_EVENT_MASK_PROXIMITY              = (0x01 << 7),
    SRFID_EVENT_MASK_TRIGGER                = (0x01 << 8),
    SRFID_EVENT_MASK_BATTERY                = (0x01 << 9),
    SRFID_EVENT_MASK_STATUS_OPERENDSUMMARY  = (0x01 << 10),
    SRFID_EVENT_MASK_TEMPERATURE            = (0x01 << 11),
    SRFID_EVENT_MASK_POWER                  = (0x01 << 12),
    SRFID_EVENT_MASK_DATABASE               = (0x01 << 13),
    SRFID_EVENT_MASK_RADIOERROR             = (0x01 << 14),
    SRFID_EVENT_MASK_MULTI_PROXIMITY        = (0x01 << 15),
    SRFID_EVENT_MASK_WLAN_SCAN              = (0x01 << 16),
    SRFID_EVENT_MASK_IOT_STATUS             = (0x01 << 17),
	SRFID_EVENT_MASK_CONNECTED_INTERFACE    = (0x01 << 18)
};

/* supported device models */
enum {
    SRFID_DEVMODEL_INVALID                 = 0x00,
    SRFID_DEVMODEL_RFID_RFD8500            = 0x01,
};

/* invalid device id */
#define SRFID_DEVICE_ID_INVALID             0x00

/* return values */
typedef enum {
    SRFID_EVENT_STATUS_OPERATION_START       = 0x00,
    SRFID_EVENT_STATUS_OPERATION_STOP        = 0x01,
    SRFID_EVENT_STATUS_OPERATION_BATCHMODE   = 0x02,
    SRFID_EVENT_STATUS_OPERATION_END_SUMMARY = 0x03,
    SRFID_EVENT_STATUS_TEMPERATURE           = 0x04,
    SRFID_EVENT_STATUS_POWER                 = 0x05,
    SRFID_EVENT_STATUS_DATABASE              = 0x06,
    SRFID_EVENT_STATUS_RADIOERROR            = 0x07,
    SRFID_EVENT_STATUS_WLAN_START            = 0x08,
    SRFID_EVENT_STATUS_WLAN_STOP             = 0x09,
    SRFID_EVENT_STATUS_WLAN_CONNECT          = 0x10,
    SRFID_EVENT_STATUS_WLAN_DISCONNECT       = 0x11,
    SRFID_EVENT_STATUS_OPERATION_FAILED      = 0x12,
    SRFID_EVENT_STATUS_IOT_STATUS            = 0x13
} SRFID_EVENT_STATUS;


typedef enum {
    SRFID_MEMORYBANK_EPC                    = 0x01,
    SRFID_MEMORYBANK_TID                    = 0x02,
    SRFID_MEMORYBANK_USER                   = 0x04,
    SRFID_MEMORYBANK_RESV                   = 0x08,
    SRFID_MEMORYBANK_NONE                   = 0x10,
    SRFID_MEMORYBANK_ACCESS                 = 0x20,
    SRFID_MEMORYBANK_KILL                   = 0x40,
    SRFID_MEMORYBANK_TAMPER                 = 0x60,
    SRFID_MEMORYBANK_ALL                    = 0x67,
} SRFID_MEMORYBANK;

typedef enum
{
    SRFID_ACCESSOPERATIONCODE_READ                         = 0,
    SRFID_ACCESSOPERATIONCODE_WRITE                        = 1,
    SRFID_ACCESSOPERATIONCODE_LOCK                         = 2,
    SRFID_ACCESSOPERATIONCODE_KILL                         = 3,
    SRFID_ACCESSOPERATIONCODE_BLOCK_WRITE                  = 4,
    SRFID_ACCESSOPERATIONCODE_BLOCK_ERASE                  = 5,
    SRFID_ACCESSOPERATIONCODE_RECOMMISSION                 = 6,
    SRFID_ACCESSOPERATIONCODE_BLOCK_PERMALOCK              = 7,
    SRFID_ACCESSOPERATIONCODE_NXP_SET_EAS                  = 8,
    SRFID_ACCESSOPERATIONCODE_NXP_READ_PROTECT             = 9,
    SRFID_ACCESSOPERATIONCODE_NXP_RESET_READ_PROTECT       = 10,
    SRFID_ACCESSOPERATIONCODE_NXP_CHANGE_CONFIG            = 22,
    SRFID_ACCESSOPERATIONCODE_IMPINJ_QT_READ               = 21,
    SRFID_ACCESSOPERATIONCODE_IMPINJ_QT_WRITE              = 20,
    SRFID_ACCESSOPERATIONCODE_NONE                         = 0xFF,
} SRFID_ACCESSOPERATIONCODE;

typedef enum
{
    SRFID_ACCESSOPERATIONSTATUS_SUCCESS                             = 0,
    SRFID_ACCESSOPERATIONSTATUS_TAG_NON_SPECIFIC_ERROR              = 1,
    SRFID_ACCESSOPERATIONSTATUS_READER_NON_SPECIFIC_ERROR           = 2,
    SRFID_ACCESSOPERATIONSTATUS_NO_RESPONSE_FROM_TAG                = 3,
    SRFID_ACCESSOPERATIONSTATUS_INSUFFICIENT_POWER                  = 4,
    SRFID_ACCESSOPERATIONSTATUS_TAG_MEMORY_LOCKED_ERROR             = 5,
    SRFID_ACCESSOPERATIONSTATUS_TAG_MEMORY_OVERRUN_ERROR            = 6,
    SRFID_ACCESSOPERATIONSTATUS_ZERO_KILL_PASSWORD_ERROR            = 7,
    SRFID_ACCESSOPERATIONSTATUS_ERROR                               = 8,
} SRFID_ACCESSOPERATIONSTATUS;

typedef enum
{
    SRFID_DIVIDERATIO_DR_8                  = 0,
    SRFID_DIVIDERATIO_DR_64_3               = 1,
} SRFID_DIVIDERATIO;

typedef enum
{
    SRFID_MODULATION_MV_FM0                 = 0,
    SRFID_MODULATION_MV_2                   = 1,
    SRFID_MODULATION_MV_4                   = 2,
    SRFID_MODULATION_MV_8                   = 3,
} SRFID_MODULATION;

typedef enum
{
    SRFID_FORWARDLINKMODULATION_PR_ASK      = 0,
    SRFID_FORWARDLINKMODULATION_SSB_ASK     = 1,
    SRFID_FORWARDLINKMODULATION_DSB_ASK     = 2,
} SRFID_FORWARDLINKMODULATION;

typedef enum
{
    SRFID_SPECTRALMASKINDICATOR_SI          = 1,
    SRFID_SPECTRALMASKINDICATOR_MI          = 2,
    SRFID_SPECTRALMASKINDICATOR_DI          = 3,
} SRFID_SPECTRALMASKINDICATOR;

typedef enum
{
    SRFID_SLFLAG_ASSERTED                   = 0,
    SRFID_SLFLAG_DEASSERTED                 = 1,
    SRFID_SLFLAG_ALL                        = 2,
} SRFID_SLFLAG;

typedef enum
{
    SRFID_SESSION_S0                        = 0,
    SRFID_SESSION_S1                        = 1,
    SRFID_SESSION_S2                        = 2,
    SRFID_SESSION_S3                        = 3,
} SRFID_SESSION;

typedef enum
{
    SRFID_INVENTORYSTATE_A                  = 0,
    SRFID_INVENTORYSTATE_B                  = 1,
    SRFID_INVENTORYSTATE_AB_FLIP            = 2,
} SRFID_INVENTORYSTATE;

typedef enum {
    SRFID_TRIGGERTYPE_PRESS                 = 0x00,
    SRFID_TRIGGERTYPE_RELEASE               = 0x01,
} SRFID_TRIGGERTYPE;

typedef enum {
    SRFID_SELECTTARGET_S0                   = 0x00,
    SRFID_SELECTTARGET_S1                   = 0x01,
    SRFID_SELECTTARGET_S2                   = 0x02,
    SRFID_SELECTTARGET_S3                   = 0x03,
    SRFID_SELECTTARGET_SL                   = 0x04,
} SRFID_SELECTTARGET;

typedef enum {
    SRFID_SELECTACTION_INV_A_NOT_INV_B__OR__ASRT_SL_NOT_DSRT_SL              = 0x00,
    SRFID_SELECTACTION_INV_A__OR__ASRT_SL                                    = 0x01,
    SRFID_SELECTACTION_NOT_INV_B__OR__NOT_DSRT_SL                            = 0x02,
    SRFID_SELECTACTION_INV_A2BB2A_NOT_INV_A__OR__NEG_SL_NOT_ASRT_SL          = 0x03,
    SRFID_SELECTACTION_INV_B_NOT_INV_A__OR__DSRT_SL_NOT_ASRT_SL              = 0x04,
    SRFID_SELECTACTION_INV_B__OR__DSRT_SL                                    = 0x05,
    SRFID_SELECTACTION_NOT_INV_A__OR__NOT_ASRT_SL                            = 0x06,
    SRFID_SELECTACTION_NOT_INV_A2BB2A__OR__NOT_NEG_SL                        = 0x07,
} SRFID_SELECTACTION;

typedef enum {
    SRFID_ACCESSPERMISSION_ACCESSIBLE                  = 0x00,
    SRFID_ACCESSPERMISSION_ACCESSIBLE_PERM             = 0x01,
    SRFID_ACCESSPERMISSION_ACCESSIBLE_SECURED          = 0x02,
    SRFID_ACCESSPERMISSION_ALWAYS_NOT_ACCESSIBLE       = 0x03,
} SRFID_ACCESSPERMISSION;

typedef enum {
    SRFID_BEEPERCONFIG_HIGH                            = 0x00,
    SRFID_BEEPERCONFIG_MEDIUM                          = 0x01,
    SRFID_BEEPERCONFIG_LOW                             = 0x02,
    SRFID_BEEPERCONFIG_QUIET                           = 0x03,
} SRFID_BEEPERCONFIG;

typedef enum {
    SRFID_TRIGGEREVENT_PRESSED                         = 0x00,
    SRFID_TRIGGEREVENT_RELEASED                        = 0x01,
    SRFID_TRIGGEREVENT_SCAN_PRESSED                    = 0x02,
    SRFID_TRIGGEREVENT_SCAN_RELEASED                   = 0x03,
} SRFID_TRIGGEREVENT;

typedef enum {
    SRFID_WLAN_START                        = 0x00,
    SRFID_WLAN_STOP                         = 0x01
} SRFID_WLAN_SCAN_STATUS;

typedef enum {
    SRFID_HOPPINGCONFIG_DEFAULT                        = 0x00,
    SRFID_HOPPINGCONFIG_ENABLED                        = 0x01,
    SRFID_HOPPINGCONFIG_DISABLED                       = 0x02,
} SRFID_HOPPINGCONFIG;

typedef enum {
    SRFID_TID_SHOW                          = 0,
    SRFID_TID_HIDE_SOME                     = 1,
    SRFID_TID_HIDE_ALL                      = 2
} SRFID_CONFIG_TID;

typedef enum {
    SRFID_RANGE_NONE                        = 0,
    SRFID_RANGE_TOGGLE                      = 1,
    SRFID_RANGE_REDUCE                      = 2
} SRFID_CONFIG_RANGE;

/* Trigger mapping legacy*/
typedef enum{
    SRFID_UPPER_TRIGGER_FOR_RFID                              = 0,
    SRFID_UPPER_TRIGGER_FOR_SCAN                              = 1,
    SRFID_LOWER_TRIGGER_FOR_SLED_SCAN                         = 2,
    SRFID_UPPER_TRIGGER_FOR_SLED_SCAN                         = 3,
} SRFID_ENUM_KEYLAYOUT_TYPE;

/* Trigger mapping new */
typedef enum{
    RFID_SCAN           = 0,
    SLED_SCAN           = 1,
    TERMINAL_SCAN       = 2,
    SCAN_NOTIFICATION   = 3,
    NO_ACTION           = 4,
} SRFID_NEW_ENUM_KEYLAYOUT_TYPE;

typedef enum
{
    SRFID_EP_PROTO_TYP_MQTT = 0,
    SRFID_EP_PROTO_TYP_MQTT_WS = 1,
    SRFID_EP_PROTO_TYP_MQTT_WSS = 2,
    SRFID_EP_PROTO_TYP_MQTT_TLS = 3,
    SRFID_EP_PROTO_TYP_TCPIP = 4,
    SRFID_EP_PROTO_TYP_HTTP = 5,
    SRFID_EP_PROTO_TYP_HTTPS = 6,
    SRFID_EP_PROTO_TYP_WS = 7,
    SRFID_EP_PROTO_TYP_WSS = 8,
    SRFID_EP_PROTO_TYP_AWS = 9,
    SRFID_EP_PROTO_TYP_AZURE = 10,
    SRFID_EP_PROTO_TYP_HID = 11,
    SRFID_EP_PROTO_TYP_MAX = 12,
} SRFID_ENUM_EP_PROTOCOLS;

typedef enum
{
    SRFID_QOS0 = 0,
    SRFID_QOS1 = 1,
} SRFID_ENUM_EP_QOS;

typedef enum
{
    SRFID_SOTI = 0,
    SRFID_MDM = 1,
    SRFID_MGMT = 2,
    SRFID_MGMT_EVENTS = 3,
    SRFID_CONTROL = 4,
    SRFID_DATA1 = 5,
    SRFID_DATA2 = 6,
} SRFID_ENUM_EP_TYPE;

typedef enum
{
    SRFID_NONE                     = 0,
    SRFID_PEER                     = 1,
    SRFID_HOST                     = 2,
    SRFID_HOST_AND_PEER            = 3,
    SRFID_INVALID_OPTION           = 5,
} SRFID_ENUM_HOST_VERIFY;

typedef enum
{
    SRFID_WPAPSK                              = 0,
    SRFID_IEEE8021X                           = 1,
    SRFID_No_Encryption                       = 2,
    SRFID_WPA_Personal_TKIP                   = 3,
    SRFID_WPA2_Personal_CCMP                  = 4,
    SRFID_WPA3_Personal_SAE                   = 5,
    SRFID_WPA2_Enterprise_CCMP                = 6,
    SRFID_WPA3_Enterprise_CCMP                = 7,
    SRFID_WPA3_Enterprise_CCMP_256            = 8,
    SRFID_WPA3_Enterprise_GCMP_128            = 9,
    SRFID_WPA3_Enterprise_GCMP_256_SHA256     = 10,
    SRFID_WPA3_Enterprise_GCMP_256_SUITEB_192 = 11,
    SRFID_UNSUPPORTED                         = 12,
} SRFID_ENUM_WIFI_PROTOCOL_TYPE;

typedef enum {
    SRFID_CONNECTION_TYPE_NO_INTERFACE                      = 0x00,
    SRFID_CONNECTION_TYPE_TERMINAL                          = 0x01,
    SRFID_CONNECTION_TYPE_USB                               = 0x02,
    SRFID_CONNECTION_TYPE_ETHERNET                          = 0x03,
    SRFID_CONNECTION_TYPE_BLUETOOTH                         = 0x04,
} SRFID_CONNECTED_INTERFACE_TYPE;


typedef NS_ENUM(NSInteger, SRFID_ENUM_TAGQUIET_MASK) {
    
    ENUM_TAGQUIET_MASK_S0B,
    ENUM_TAGQUIET_MASK_S2B,
    ENUM_TAGQUIET_MASK_S3B,
    ENUM_TAGQUIET_MASK_SL_ASSERT,
    ENUM_TAGQUIET_MASK_S0A,
    ENUM_TAGQUIET_MASK_S2A,
    ENUM_TAGQUIET_MASK_S3A,
    ENUM_TAGQUIET_MASK_SL_DEASSERT,
    ENUM_TAGQUIET_MASK_UNKNOWN
    
};
#endif /* __RFID_SDK_DEFS__ */

