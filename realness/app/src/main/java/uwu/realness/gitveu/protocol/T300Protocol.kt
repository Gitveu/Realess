package uwu.realness.gitveu.protocol

import androidx.annotation.StringRes
import uwu.realness.gitveu.R
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Описание протокола TL TWS для Realme Buds T300.
 *
 * Формат транспортного кадра OPPOv1:
 * Byte 0: SOF (0xAA)
 * Byte 1..K: Длина пакета (varint 7-bit, для <128 байт — 1 байт = TL_LEN + 2)
 * Byte K+1: Флаги (0x00 = Single package)
 * Byte K+2: Зарезервировано (0x00)
 * Затем внутренний кадр TL (5+N байт):
 *   Offset 0..1: Command ID (little-endian)
 *   Offset 2:    Device Address (0x00)
 *   Offset 3..4: Payload Length (little-endian)
 *   Offset 5..N: Payload
 */
object T300Protocol {

    const val DEVICE_ADDRESS_DEFAULT: Byte = 0x00
    const val OPPO_SOF: Byte = 0xAA.toByte()

    // Команды TL-протокола
    const val CMD_BATTERY_REQ: Int = 0x0106      // 262 decimal
    const val CMD_BATTERY_RSP: Int = 0x8106      // 33030 decimal (CMD | 0x8000)
    const val CMD_ANC_STATE_REQ: Int = 0x010C    // 268 decimal
    const val CMD_ANC_STATE_RSP: Int = 0x810C    // 33036 decimal
    const val CMD_SWITCH_FEATURE_REQ: Int = 0x010D // 269 decimal (Запрос переключателей/функций)
    const val CMD_SWITCH_FEATURE_RSP: Int = 0x810D // 33037 decimal
    const val CMD_EQ_REQ: Int = 0x010F           // 271 decimal (Запрос пресета эквалайзера)
    const val CMD_EQ_RSP: Int = 0x810F           // 33039 decimal
    const val CMD_MULTI_CONNECT_INFO_REQ: Int = 0x0112 // 274 decimal (Запрос списка подключённых устройств)
    const val CMD_MULTI_CONNECT_INFO_RSP: Int = 0x8112 // 33042 decimal
    const val CMD_ALL_EQ_REQ: Int = 0x0122       // 290 decimal (Запрос пользовательских кривых EQ)
    const val CMD_ALL_EQ_RSP: Int = 0x8122       // 33058 decimal
    const val CMD_USE_TIME_REQ: Int = 0x0128     // 296 decimal (Запрос статистики использования L/R)
    const val CMD_USE_TIME_RSP: Int = 0x8128     // 33064 decimal
    const val CMD_KEY_FUNCTION_REQ: Int = 0x0108     // 264 decimal (Запрос настроек кнопок)
    const val CMD_KEY_FUNCTION_RSP: Int = 0x8108     // 33032 decimal
    const val CMD_KEY_FUNCTION_SET: Int = 0x0401     // 1025 decimal (Установка настроек кнопок)
    const val CMD_KEY_FUNCTION_SET_RSP: Int = 0x8401 // 33793 decimal
    const val CMD_ANC_SET: Int = 0x0404          // 1028 decimal
    const val CMD_ANC_RSP: Int = 0x8404          // 33796 decimal
    const val CMD_SWITCH_FEATURE_SET: Int = 0x0403 // 1027 decimal (Установка переключателей/функций)
    const val CMD_SWITCH_FEATURE_SET_RSP: Int = 0x8403 // 33795 decimal
    const val CMD_EQ_SET: Int = 0x0406           // 1030 decimal (Установка пресета эквалайзера)
    const val CMD_EQ_SET_RSP: Int = 0x8406       // 33798 decimal
    const val CMD_NOTIFICATION_REGISTER: Int = 0x0201 // 513 decimal (Регистрация на события)
    const val CMD_NOTIFICATION_REGISTER_RSP: Int = 0x8201 // 33281 decimal
    const val CMD_EVENT: Int = 0x0204            // 516 decimal (Асинхронные события)
    const val CMD_NOTIFICATION: Int = 0x8400     // Асинхронные уведомления от устройства

    // Идентификаторы переключателей (SwitchFeature)
    const val FEATURE_ID_AUTO_PLAY_PAUSE: Byte = 0x04    // 4 decimal (Автовоспроизведение/пауза, Wear Detection)
    const val FEATURE_ID_GAME_MODE: Byte = 0x06          // 6 decimal (Игровой режим)
    const val FEATURE_ID_AUTO_ANSWER: Byte = 0x08        // 8 decimal (Автоответ на вызов)
    const val FEATURE_ID_DUAL_CONNECTION: Byte = 0x11    // 17 decimal (Подключение к двум устройствам)
    const val FEATURE_ID_HIGH_QUALITY_AUDIO: Byte = 0x18 // 24 decimal (Звук высокой четкости: LDAC / LHDC 5.0)
    const val FEATURE_ID_SPATIAL_AUDIO: Byte = 0x1B      // 27 decimal (Пространственное аудио / 3D Sound)
    const val FEATURE_ID_DYNAMIC_AUDIO: Byte = 0x1D      // 29 decimal (Динамическое аудио)
    const val EVENT_TYPE_GAME_MODE: Byte = 0x05
    const val EVENT_TYPE_MULTI_CONNECT: Byte = 0x06

    // CCCD UUID для включения notify/indicate
    const val CCCD_UUID_STRING = "00002902-0000-1000-8000-00805f9b34fb"

    enum class ProtocolFamily {
        NONE,
        REALME_TL,
        HUAWEI_SPP,
        HONOR_CHOICE_SPP,
        NOTHING_SPP,
        GALAXY_BUDS_SPP,
        SONY_MDR_SPP,
        BOSE_BMAP_SPP,
        SOUNDCORE_SPP,
        XIAOMI_SPP,
        SENNHEISER_GAIA_SPP,
        JABRA_SPP,
        PIXEL_BUDS_SPP,
        JBL_GATT
    }

    enum class Brand(val title: String) {
        UNKNOWN(""),
        REALME("realme / DIZO"),
        ONEPLUS("OnePlus"),
        OPPO("OPPO"),
        HUAWEI("HUAWEI"),
        HONOR("HONOR"),
        NOTHING("Nothing / CMF"),
        SAMSUNG("Samsung"),
        SONY("Sony"),
        BOSE("Bose"),
        SOUNDCORE("Anker Soundcore"),
        XIAOMI("Xiaomi / Redmi"),
        SENNHEISER("Sennheiser"),
        JABRA("Jabra"),
        GOOGLE("Google Pixel Buds"),
        JBL("JBL")
    }

    enum class BudsModel(
        val protocolFamily: ProtocolFamily,
        val brand: Brand,
        val displayName: String
    ) {
        UNKNOWN(ProtocolFamily.NONE, Brand.UNKNOWN, "Unknown"),

        // realme Buds & DIZO (TL + OPPOv1 — realme Link & QuickBuds full whiteList)
        BUDS_T300(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds T300"),
        BUDS_T200(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds T200"),
        BUDS_T200_LITE(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds T200 Lite / T200x"),
        BUDS_T310(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds T310"),
        BUDS_T500(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds T500"),
        BUDS_T500_PRO(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds T500 Pro"),
        BUDS_T110(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds T110 / T100 / T01"),
        BUDS_AIR_8_PRO(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds Air 8 Pro"),
        BUDS_AIR_8(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds Air 8"),
        BUDS_AIR_7_PRO(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds Air 7 Pro"),
        BUDS_AIR_7(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds Air 7"),
        BUDS_AIR_6_PRO(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds Air 6 Pro"),
        BUDS_AIR_6(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds Air 6"),
        BUDS_AIR_5_PRO(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds Air 5 Pro"),
        BUDS_AIR_5(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds Air 5"),
        BUDS_AIR_3(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds Air 3 / 3S / 3 Neo"),
        BUDS_AIR_2(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds Air / 2 / Pro / Neo"),
        BUDS_N1(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds N1 / N1 Pro"),
        BUDS_CLIP(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds Clip"),
        BUDS_Q2(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds Q2 / Q2s"),
        BUDS_WIRELESS(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds Wireless 2 / 3 / 5 / 6"),
        DIZO_BUDS(ProtocolFamily.REALME_TL, Brand.REALME, "DIZO GoPods / Wireless"),
        REALME_GENERIC(ProtocolFamily.REALME_TL, Brand.REALME, "realme Buds (Generic)"),

        // OnePlus Buds (TL + OPPOv1 — QuickBuds full whiteList)
        ONEPLUS_BUDS(ProtocolFamily.REALME_TL, Brand.ONEPLUS, "OnePlus Buds"),
        ONEPLUS_BUDS_3(ProtocolFamily.REALME_TL, Brand.ONEPLUS, "OnePlus Buds 3"),
        ONEPLUS_BUDS_3V(ProtocolFamily.REALME_TL, Brand.ONEPLUS, "OnePlus Buds 3V"),
        ONEPLUS_BUDS_4(ProtocolFamily.REALME_TL, Brand.ONEPLUS, "OnePlus Buds 4"),
        ONEPLUS_BUDS_ACE(ProtocolFamily.REALME_TL, Brand.ONEPLUS, "OnePlus Buds Ace"),
        ONEPLUS_BUDS_ACE_2(ProtocolFamily.REALME_TL, Brand.ONEPLUS, "OnePlus Buds Ace 2"),
        ONEPLUS_BUDS_ACE_3(ProtocolFamily.REALME_TL, Brand.ONEPLUS, "OnePlus Buds Ace 3"),
        ONEPLUS_BUDS_CLIP(ProtocolFamily.REALME_TL, Brand.ONEPLUS, "OnePlus Buds Clip / Open / Flow"),
        ONEPLUS_BUDS_N(ProtocolFamily.REALME_TL, Brand.ONEPLUS, "OnePlus Buds N / V"),
        ONEPLUS_BUDS_PRO(ProtocolFamily.REALME_TL, Brand.ONEPLUS, "OnePlus Buds Pro"),
        ONEPLUS_BUDS_PRO_2(ProtocolFamily.REALME_TL, Brand.ONEPLUS, "OnePlus Buds Pro 2 / 2R"),
        ONEPLUS_BUDS_PRO_3(ProtocolFamily.REALME_TL, Brand.ONEPLUS, "OnePlus Buds Pro 3"),
        ONEPLUS_BUDS_PRO_4(ProtocolFamily.REALME_TL, Brand.ONEPLUS, "OnePlus Buds Pro 4"),
        ONEPLUS_BUDS_Z(ProtocolFamily.REALME_TL, Brand.ONEPLUS, "OnePlus Buds Z / Z2"),
        ONEPLUS_NORD_BUDS(ProtocolFamily.REALME_TL, Brand.ONEPLUS, "OnePlus Nord Buds / CE / 2 / 2r"),
        ONEPLUS_NORD_BUDS_3(ProtocolFamily.REALME_TL, Brand.ONEPLUS, "OnePlus Nord Buds 3 / 3r"),
        ONEPLUS_NORD_BUDS_3_PRO(ProtocolFamily.REALME_TL, Brand.ONEPLUS, "OnePlus Nord Buds 3 Pro"),
        ONEPLUS_NORD_BUDS_4(ProtocolFamily.REALME_TL, Brand.ONEPLUS, "OnePlus Nord Buds 4 / 4 Pro"),
        ONEPLUS_BULLETS(ProtocolFamily.REALME_TL, Brand.ONEPLUS, "OnePlus Bullets Wireless Z2 / Z3"),
        ONEPLUS_GENERIC(ProtocolFamily.REALME_TL, Brand.ONEPLUS, "OnePlus Buds (Generic)"),

        // OPPO Enco (TL + OPPOv1 — QuickBuds full whiteList)
        OPPO_ENCO_AIR(ProtocolFamily.REALME_TL, Brand.OPPO, "OPPO Enco Air / Lite / Air2 / 2i / 2 Pro"),
        OPPO_ENCO_AIR_3(ProtocolFamily.REALME_TL, Brand.OPPO, "OPPO Enco Air3 / 3i / 3s / 3 Pro"),
        OPPO_ENCO_AIR_4(ProtocolFamily.REALME_TL, Brand.OPPO, "OPPO Enco Air4 / 4i / 4s / 4 Pro"),
        OPPO_ENCO_AIR_5(ProtocolFamily.REALME_TL, Brand.OPPO, "OPPO Enco Air5 / 5s / 5 Pro"),
        OPPO_ENCO_BUDS(ProtocolFamily.REALME_TL, Brand.OPPO, "OPPO Enco Buds / 2 / 2 Pro"),
        OPPO_ENCO_BUDS_3(ProtocolFamily.REALME_TL, Brand.OPPO, "OPPO Enco Buds3 / 3s / 3 Pro / 3 Pro+"),
        OPPO_ENCO_CLIP(ProtocolFamily.REALME_TL, Brand.OPPO, "OPPO Enco Clip / Clip2"),
        OPPO_ENCO_FREE_2(ProtocolFamily.REALME_TL, Brand.OPPO, "OPPO Enco Free / 2 / 2i / 3 / 4"),
        OPPO_ENCO_X(ProtocolFamily.REALME_TL, Brand.OPPO, "OPPO Enco X"),
        OPPO_ENCO_X2(ProtocolFamily.REALME_TL, Brand.OPPO, "OPPO Enco X2"),
        OPPO_ENCO_X3(ProtocolFamily.REALME_TL, Brand.OPPO, "OPPO Enco X3 / X3i / X3s"),
        OPPO_ENCO_X4(ProtocolFamily.REALME_TL, Brand.OPPO, "OPPO Enco X4"),
        OPPO_ENCO_R(ProtocolFamily.REALME_TL, Brand.OPPO, "OPPO Enco R / R2 / R3 / R4 / R5"),
        OPPO_ENCO_W(ProtocolFamily.REALME_TL, Brand.OPPO, "OPPO Enco W11 / W31 / W51 / M31..M33"),
        OPPO_GENERIC(ProtocolFamily.REALME_TL, Brand.OPPO, "OPPO Enco (Generic)"),

        // HUAWEI FreeBuds / FreeClip / FreeLace (Huawei SPP — OpenFreebuds)
        HUAWEI_FREEBUDS_3(ProtocolFamily.HUAWEI_SPP, Brand.HUAWEI, "HUAWEI FreeBuds 3"),
        HUAWEI_FREEBUDS_4I(ProtocolFamily.HUAWEI_SPP, Brand.HUAWEI, "HUAWEI FreeBuds 4i"),
        HUAWEI_FREEBUDS_5I(ProtocolFamily.HUAWEI_SPP, Brand.HUAWEI, "HUAWEI FreeBuds 5i"),
        HUAWEI_FREEBUDS_6I(ProtocolFamily.HUAWEI_SPP, Brand.HUAWEI, "HUAWEI FreeBuds 6i"),
        HUAWEI_FREEBUDS_PRO(ProtocolFamily.HUAWEI_SPP, Brand.HUAWEI, "HUAWEI FreeBuds Pro"),
        HUAWEI_FREEBUDS_PRO_2(ProtocolFamily.HUAWEI_SPP, Brand.HUAWEI, "HUAWEI FreeBuds Pro 2"),
        HUAWEI_FREEBUDS_PRO_3(ProtocolFamily.HUAWEI_SPP, Brand.HUAWEI, "HUAWEI FreeBuds Pro 3 / 4"),
        HUAWEI_FREEBUDS_PRO_5(ProtocolFamily.HUAWEI_SPP, Brand.HUAWEI, "HUAWEI FreeBuds Pro 5"),
        HUAWEI_FREECLIP(ProtocolFamily.HUAWEI_SPP, Brand.HUAWEI, "HUAWEI FreeClip"),
        HUAWEI_FREECLIP_2(ProtocolFamily.HUAWEI_SPP, Brand.HUAWEI, "HUAWEI FreeClip 2"),
        HUAWEI_FREEBUDS_SE(ProtocolFamily.HUAWEI_SPP, Brand.HUAWEI, "HUAWEI FreeBuds SE"),
        HUAWEI_FREEBUDS_SE_2(ProtocolFamily.HUAWEI_SPP, Brand.HUAWEI, "HUAWEI FreeBuds SE 2"),
        HUAWEI_FREEBUDS_SE_4(ProtocolFamily.HUAWEI_SPP, Brand.HUAWEI, "HUAWEI FreeBuds SE 4"),
        HUAWEI_FREEBUDS_STUDIO(ProtocolFamily.HUAWEI_SPP, Brand.HUAWEI, "HUAWEI FreeBuds Studio"),
        HUAWEI_FREELACE_PRO(ProtocolFamily.HUAWEI_SPP, Brand.HUAWEI, "HUAWEI FreeLace Pro"),
        HUAWEI_FREELACE_PRO_2(ProtocolFamily.HUAWEI_SPP, Brand.HUAWEI, "HUAWEI FreeLace Pro 2"),
        HUAWEI_GENERIC(ProtocolFamily.HUAWEI_SPP, Brand.HUAWEI, "HUAWEI FreeBuds (Generic)"),

        // HONOR Earbuds (Huawei SPP & HONOR CHOICE SPP — OpenFreebuds & noctalia/honor-earbuds)
        HONOR_EARBUDS_S2(ProtocolFamily.HUAWEI_SPP, Brand.HONOR, "HONOR Earbuds S2"),
        HONOR_CHOICE_X8(ProtocolFamily.HONOR_CHOICE_SPP, Brand.HONOR, "HONOR CHOICE Earbuds X8"),
        HONOR_CHOICE_X5_PRO(ProtocolFamily.HONOR_CHOICE_SPP, Brand.HONOR, "HONOR CHOICE Earbuds X5 Pro"),
        HONOR_CHOICE_X5(ProtocolFamily.HONOR_CHOICE_SPP, Brand.HONOR, "HONOR CHOICE Earbuds X5"),
        HONOR_CHOICE_X3(ProtocolFamily.HONOR_CHOICE_SPP, Brand.HONOR, "HONOR CHOICE Earbuds X3 / X3 Lite"),
        HONOR_CHOICE_X2(ProtocolFamily.HONOR_CHOICE_SPP, Brand.HONOR, "HONOR CHOICE Earbuds X2 / X"),
        HONOR_CHOICE_GENERIC(ProtocolFamily.HONOR_CHOICE_SPP, Brand.HONOR, "HONOR CHOICE (Generic)"),

        // Nothing / CMF (ALL 23 models from swift-nothing-ear / omarchy / kde-headphones)
        NOTHING_EAR_1(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "Nothing Ear (1)"),
        NOTHING_EAR_2(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "Nothing Ear (2)"),
        NOTHING_EAR(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "Nothing Ear"),
        NOTHING_EAR_A(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "Nothing Ear (a)"),
        NOTHING_EAR_3(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "Nothing Ear (3)"),
        NOTHING_EAR_3A(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "Nothing Ear (3a)"),
        NOTHING_EAR_STICK(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "Nothing Ear (stick)"),
        NOTHING_EAR_OPEN(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "Nothing Ear (open)"),
        NOTHING_HEADPHONE_1(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "Nothing Headphone (1)"),
        NOTHING_HEADPHONE_1_PRO(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "Nothing Headphone (1) Pro"),
        NOTHING_HEADPHONE_A(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "Nothing Headphone (a)"),
        CMF_BUDS(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "CMF Buds"),
        CMF_BUDS_NEO(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "CMF Buds Neo"),
        CMF_BUDS_PRO(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "CMF Buds Pro"),
        CMF_BUDS_PRO_2(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "CMF Buds Pro 2"),
        CMF_BUDS_2(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "CMF Buds 2"),
        CMF_BUDS_2A(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "CMF Buds 2a"),
        CMF_BUDS_2_PLUS(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "CMF Buds 2 Plus"),
        CMF_NECKBAND_PRO(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "CMF Neckband Pro"),
        CMF_HEADPHONE_PRO(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "CMF Headphone Pro"),
        CMF_CLIP_PRO(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "CMF Clip Pro"),
        CMF_SPEAKER(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "CMF Speaker S / M"),
        NOTHING_GENERIC(ProtocolFamily.NOTHING_SPP, Brand.NOTHING, "Nothing / CMF (Generic)"),

        // Samsung Galaxy Buds (Galaxy Buds SPP — kde-headphones)
        GALAXY_BUDS(ProtocolFamily.GALAXY_BUDS_SPP, Brand.SAMSUNG, "Galaxy Buds (2019)"),
        GALAXY_BUDS_PLUS(ProtocolFamily.GALAXY_BUDS_SPP, Brand.SAMSUNG, "Galaxy Buds+"),
        GALAXY_BUDS_LIVE(ProtocolFamily.GALAXY_BUDS_SPP, Brand.SAMSUNG, "Galaxy Buds Live"),
        GALAXY_BUDS_PRO(ProtocolFamily.GALAXY_BUDS_SPP, Brand.SAMSUNG, "Galaxy Buds Pro"),
        GALAXY_BUDS_2(ProtocolFamily.GALAXY_BUDS_SPP, Brand.SAMSUNG, "Galaxy Buds2"),
        GALAXY_BUDS_2_PRO(ProtocolFamily.GALAXY_BUDS_SPP, Brand.SAMSUNG, "Galaxy Buds2 Pro"),
        GALAXY_BUDS_FE(ProtocolFamily.GALAXY_BUDS_SPP, Brand.SAMSUNG, "Galaxy Buds FE"),
        GALAXY_BUDS_3(ProtocolFamily.GALAXY_BUDS_SPP, Brand.SAMSUNG, "Galaxy Buds3 / 3 FE"),
        GALAXY_BUDS_3_PRO(ProtocolFamily.GALAXY_BUDS_SPP, Brand.SAMSUNG, "Galaxy Buds3 Pro"),
        GALAXY_BUDS_CORE(ProtocolFamily.GALAXY_BUDS_SPP, Brand.SAMSUNG, "Galaxy Buds Core"),
        GALAXY_BUDS_4(ProtocolFamily.GALAXY_BUDS_SPP, Brand.SAMSUNG, "Galaxy Buds4 / 4 Pro"),

        // Sony MDR V1 / V2 (kde-headphones / SonyHeadphonesClient)
        SONY_WH_1000XM5(ProtocolFamily.SONY_MDR_SPP, Brand.SONY, "Sony WH-1000XM5 / XM4 / XM3"),
        SONY_WF_1000XM5(ProtocolFamily.SONY_MDR_SPP, Brand.SONY, "Sony WF-1000XM5 / XM4 / XM3"),
        SONY_LINKBUDS(ProtocolFamily.SONY_MDR_SPP, Brand.SONY, "Sony LinkBuds / S / Fit / Open"),
        SONY_ULT_CH(ProtocolFamily.SONY_MDR_SPP, Brand.SONY, "Sony ULT WEAR / WH-CH720N / WF-C700N"),
        SONY_GENERIC(ProtocolFamily.SONY_MDR_SPP, Brand.SONY, "Sony MDR (Generic)"),

        // Bose BMAP (kde-headphones)
        BOSE_QC_ULTRA_HEADPHONES(ProtocolFamily.BOSE_BMAP_SPP, Brand.BOSE, "Bose QC Ultra / QC Headphones"),
        BOSE_QC_45_35(ProtocolFamily.BOSE_BMAP_SPP, Brand.BOSE, "Bose QC 45 / QC 35 / QC 35 II"),
        BOSE_NC_700(ProtocolFamily.BOSE_BMAP_SPP, Brand.BOSE, "Bose NC 700"),
        BOSE_QC_EARBUDS(ProtocolFamily.BOSE_BMAP_SPP, Brand.BOSE, "Bose QC Earbuds / II / Ultra"),
        BOSE_SPORT_OPEN(ProtocolFamily.BOSE_BMAP_SPP, Brand.BOSE, "Bose Ultra Open / Sport Earbuds"),

        // Anker Soundcore (kde-headphones)
        SOUNDCORE_LIBERTY_4_NC(ProtocolFamily.SOUNDCORE_SPP, Brand.SOUNDCORE, "Soundcore Liberty 4 / 4 NC / 4 Pro"),
        SOUNDCORE_SPACE_Q45(ProtocolFamily.SOUNDCORE_SPP, Brand.SOUNDCORE, "Soundcore Space Q45 / Space One"),
        SOUNDCORE_SPACE_A40(ProtocolFamily.SOUNDCORE_SPP, Brand.SOUNDCORE, "Soundcore Space A40"),
        SOUNDCORE_LIFE_Q30(ProtocolFamily.SOUNDCORE_SPP, Brand.SOUNDCORE, "Soundcore Life Q30 / Q35 / Tune Pro"),
        SOUNDCORE_LIFE_NOTE_3(ProtocolFamily.SOUNDCORE_SPP, Brand.SOUNDCORE, "Soundcore Life Note 3 / 3S / Dot 2 NC"),
        SOUNDCORE_P30I(ProtocolFamily.SOUNDCORE_SPP, Brand.SOUNDCORE, "Soundcore P30i / P40i / R50i"),
        SOUNDCORE_GENERIC(ProtocolFamily.SOUNDCORE_SPP, Brand.SOUNDCORE, "Soundcore (Generic)"),

        // Xiaomi / Redmi Buds (kde-headphones)
        XIAOMI_REDMI_BUDS_6_PRO(ProtocolFamily.XIAOMI_SPP, Brand.XIAOMI, "Redmi Buds 6 / 6 Pro"),
        XIAOMI_REDMI_BUDS_6_ACTIVE(ProtocolFamily.XIAOMI_SPP, Brand.XIAOMI, "Redmi Buds 6 Active / 6 Lite"),
        XIAOMI_REDMI_BUDS_5_PRO(ProtocolFamily.XIAOMI_SPP, Brand.XIAOMI, "Redmi Buds 5 / 5 Pro"),
        XIAOMI_REDMI_BUDS_4_ACTIVE(ProtocolFamily.XIAOMI_SPP, Brand.XIAOMI, "Redmi Buds 4 / 4 Active"),
        XIAOMI_REDMI_BUDS_3_PRO(ProtocolFamily.XIAOMI_SPP, Brand.XIAOMI, "Redmi Buds 3 Pro"),
        XIAOMI_REDMI_BUDS_8_ACTIVE(ProtocolFamily.XIAOMI_SPP, Brand.XIAOMI, "Redmi Buds 8 Active"),
        XIAOMI_BUDS_GENERIC(ProtocolFamily.XIAOMI_SPP, Brand.XIAOMI, "Xiaomi / Redmi Buds (Generic)"),

        // Sennheiser GAIA (kde-headphones)
        SENNHEISER_MOMENTUM_4(ProtocolFamily.SENNHEISER_GAIA_SPP, Brand.SENNHEISER, "Sennheiser MOMENTUM 4 / 5"),
        SENNHEISER_MOMENTUM_TW(ProtocolFamily.SENNHEISER_GAIA_SPP, Brand.SENNHEISER, "Sennheiser MOMENTUM TW 3 / 4"),
        SENNHEISER_ACCENTUM(ProtocolFamily.SENNHEISER_GAIA_SPP, Brand.SENNHEISER, "Sennheiser ACCENTUM / CX Plus"),

        // Jabra (kde-headphones)
        JABRA_ELITE_PRO(ProtocolFamily.JABRA_SPP, Brand.JABRA, "Jabra Elite 7 / 8 / 10 / 85t / 85h"),
        JABRA_ELITE_CLASSIC(ProtocolFamily.JABRA_SPP, Brand.JABRA, "Jabra Elite 4 / 5 / 65t / 75t"),
        JABRA_EVOLVE(ProtocolFamily.JABRA_SPP, Brand.JABRA, "Jabra Evolve2 65 / 75 / 85 / Buds"),

        // Google Pixel Buds (kde-headphones)
        PIXEL_BUDS_PRO_2(ProtocolFamily.PIXEL_BUDS_SPP, Brand.GOOGLE, "Google Pixel Buds Pro 2"),
        PIXEL_BUDS_PRO(ProtocolFamily.PIXEL_BUDS_SPP, Brand.GOOGLE, "Google Pixel Buds Pro"),
        PIXEL_BUDS_A(ProtocolFamily.PIXEL_BUDS_SPP, Brand.GOOGLE, "Google Pixel Buds A-Series / 2a"),

        // JBL GATT (kde-headphones)
        JBL_TOUR_LIVE(ProtocolFamily.JBL_GATT, Brand.JBL, "JBL Tour Pro 2/3 / Live Pro 2 / Buds 3"),
        JBL_TUNE_WAVE(ProtocolFamily.JBL_GATT, Brand.JBL, "JBL Tune / Wave / Reflect / Club");

        val isSupported: Boolean get() = this != UNKNOWN
        val isRealme: Boolean get() = protocolFamily == ProtocolFamily.REALME_TL
        val isOppoOrOnePlus: Boolean get() = brand == Brand.ONEPLUS || brand == Brand.OPPO
        val isHuawei: Boolean get() = protocolFamily == ProtocolFamily.HUAWEI_SPP
        val isHonorChoice: Boolean get() = protocolFamily == ProtocolFamily.HONOR_CHOICE_SPP
        val isNothing: Boolean get() = protocolFamily == ProtocolFamily.NOTHING_SPP
        val isGalaxy: Boolean get() = protocolFamily == ProtocolFamily.GALAXY_BUDS_SPP
        val isSony: Boolean get() = protocolFamily == ProtocolFamily.SONY_MDR_SPP
        val isBose: Boolean get() = protocolFamily == ProtocolFamily.BOSE_BMAP_SPP
        val isSoundcore: Boolean get() = protocolFamily == ProtocolFamily.SOUNDCORE_SPP
        val isXiaomi: Boolean get() = protocolFamily == ProtocolFamily.XIAOMI_SPP
        val isSennheiser: Boolean get() = protocolFamily == ProtocolFamily.SENNHEISER_GAIA_SPP
        val isJabra: Boolean get() = protocolFamily == ProtocolFamily.JABRA_SPP
        val isPixelBuds: Boolean get() = protocolFamily == ProtocolFamily.PIXEL_BUDS_SPP
        val isJbl: Boolean get() = protocolFamily == ProtocolFamily.JBL_GATT

        val prefersHuaweiPrivateUuid: Boolean
            get() = this in setOf(
                HUAWEI_FREEBUDS_4I, HUAWEI_FREEBUDS_5I, HUAWEI_FREEBUDS_SE, HUAWEI_FREELACE_PRO
            )

        val nothingUsesListeningModeEq: Boolean
            get() = this in setOf(
                CMF_BUDS, CMF_BUDS_NEO, CMF_BUDS_PRO_2, CMF_BUDS_2, CMF_BUDS_2A,
                CMF_BUDS_2_PLUS, CMF_HEADPHONE_PRO, CMF_CLIP_PRO
            )

        val supportsAnc: Boolean
            get() = when {
                !isSupported -> false
                isRealme -> this !in setOf(
                    BUDS_T110, BUDS_CLIP, BUDS_Q2, ONEPLUS_BUDS_CLIP, ONEPLUS_BUDS_Z,
                    OPPO_ENCO_BUDS, OPPO_ENCO_CLIP
                )
                isHuawei -> this !in setOf(
                    HUAWEI_FREEBUDS_3, HUAWEI_FREEBUDS_SE, HUAWEI_FREEBUDS_SE_2,
                    HUAWEI_FREECLIP, HUAWEI_FREECLIP_2
                )
                isHonorChoice -> this != HONOR_CHOICE_X2
                isNothing -> this !in setOf(NOTHING_EAR_STICK, NOTHING_EAR_OPEN, CMF_CLIP_PRO, CMF_SPEAKER)
                isBose -> this != BOSE_SPORT_OPEN
                isXiaomi -> this !in setOf(XIAOMI_REDMI_BUDS_4_ACTIVE, XIAOMI_REDMI_BUDS_6_ACTIVE, XIAOMI_REDMI_BUDS_8_ACTIVE)
                isPixelBuds -> this != PIXEL_BUDS_A
                else -> true
            }

        val supportsAncLevel: Boolean
            get() = when {
                isHuawei -> supportsAnc && this != HUAWEI_FREEBUDS_4I
                isNothing -> supportsAnc
                isXiaomi -> supportsAnc && this != XIAOMI_REDMI_BUDS_6_ACTIVE
                else -> false
            }

        val supportsAncDynamic: Boolean
            get() = when {
                isNothing -> supportsAnc && this != NOTHING_EAR_1 && this != CMF_BUDS && this != CMF_BUDS_NEO
                isXiaomi -> this in setOf(XIAOMI_REDMI_BUDS_3_PRO, XIAOMI_REDMI_BUDS_5_PRO, XIAOMI_REDMI_BUDS_6_PRO, XIAOMI_BUDS_GENERIC)
                isHuawei -> this in setOf(
                    HUAWEI_FREEBUDS_5I, HUAWEI_FREEBUDS_6I, HUAWEI_FREEBUDS_PRO,
                    HUAWEI_FREEBUDS_PRO_3, HUAWEI_FREEBUDS_PRO_5, HUAWEI_FREEBUDS_SE_4,
                    HUAWEI_FREEBUDS_STUDIO, HUAWEI_FREELACE_PRO_2, HUAWEI_GENERIC
                )
                else -> false
            }

        val supportsEq: Boolean
            get() = when {
                !isSupported -> false
                isRealme || isHonorChoice || isNothing || isGalaxy || isSony || isXiaomi || isJbl -> true
                isHuawei -> this in setOf(
                    HUAWEI_FREEBUDS_5I, HUAWEI_FREEBUDS_6I, HUAWEI_FREEBUDS_PRO_2,
                    HUAWEI_FREEBUDS_PRO_3, HUAWEI_FREEBUDS_PRO_5, HUAWEI_FREECLIP,
                    HUAWEI_FREECLIP_2, HUAWEI_FREEBUDS_SE_2, HUAWEI_FREEBUDS_SE_4,
                    HUAWEI_FREEBUDS_STUDIO, HUAWEI_FREELACE_PRO_2, HUAWEI_GENERIC
                )
                else -> false
            }

        val huaweiEqUsesPro3Ids: Boolean
            get() = this in setOf(HUAWEI_FREEBUDS_PRO_3, HUAWEI_FREEBUDS_PRO_5, HUAWEI_FREECLIP)

        val supportsDoubleTap: Boolean
            get() = isRealme || this in setOf(
                HUAWEI_FREEBUDS_3, HUAWEI_FREEBUDS_4I, HUAWEI_FREEBUDS_5I, HUAWEI_FREEBUDS_6I,
                HUAWEI_FREEBUDS_PRO_3, HUAWEI_FREEBUDS_PRO_5, HUAWEI_FREECLIP, HUAWEI_FREECLIP_2,
                HUAWEI_FREEBUDS_SE, HUAWEI_FREEBUDS_SE_2, HUAWEI_FREEBUDS_SE_4, HUAWEI_GENERIC
            )

        val supportsTripleTap: Boolean
            get() = isRealme || this in setOf(
                HUAWEI_FREEBUDS_5I, HUAWEI_FREEBUDS_6I, HUAWEI_FREECLIP_2,
                HUAWEI_FREEBUDS_SE_2, HUAWEI_FREEBUDS_SE_4, HUAWEI_GENERIC
            )

        val supportsLongPress: Boolean
            get() = isRealme || this in setOf(
                HUAWEI_FREEBUDS_4I, HUAWEI_FREEBUDS_5I, HUAWEI_FREEBUDS_6I,
                HUAWEI_FREEBUDS_PRO, HUAWEI_FREEBUDS_PRO_2, HUAWEI_FREEBUDS_PRO_3,
                HUAWEI_FREEBUDS_PRO_5, HUAWEI_FREECLIP, HUAWEI_FREECLIP_2,
                HUAWEI_FREEBUDS_SE_4, HUAWEI_FREELACE_PRO_2, HUAWEI_GENERIC
            )

        val supportsTouchSettings: Boolean
            get() = supportsDoubleTap || supportsTripleTap || supportsLongPress

        val supportsSpatialAudio: Boolean
            get() = isRealme || isSennheiser || isJbl

        val supportsGameMode: Boolean
            get() = isRealme || isNothing || isSoundcore || this in setOf(
                HUAWEI_FREEBUDS_5I, HUAWEI_FREEBUDS_6I, HUAWEI_FREEBUDS_PRO_3,
                HUAWEI_FREEBUDS_PRO_5, HUAWEI_FREECLIP, HUAWEI_FREECLIP_2,
                HUAWEI_FREEBUDS_SE_2, HUAWEI_FREEBUDS_SE_4, HUAWEI_FREELACE_PRO_2,
                HUAWEI_GENERIC
            )

        val supportsHighQualityAudio: Boolean
            get() = this in setOf(
                BUDS_T200, BUDS_T500_PRO, BUDS_AIR_5_PRO, BUDS_AIR_6, BUDS_AIR_6_PRO,
                BUDS_AIR_7, BUDS_AIR_7_PRO, BUDS_AIR_8, BUDS_AIR_8_PRO,
                ONEPLUS_BUDS_3, ONEPLUS_BUDS_4, ONEPLUS_BUDS_PRO_2, ONEPLUS_BUDS_PRO_3, ONEPLUS_BUDS_PRO_4,
                OPPO_ENCO_X, OPPO_ENCO_X2, OPPO_ENCO_X3, OPPO_ENCO_X4, OPPO_ENCO_AIR_4, OPPO_ENCO_AIR_5,
                HUAWEI_FREEBUDS_5I, HUAWEI_FREEBUDS_6I, HUAWEI_FREEBUDS_PRO_2,
                HUAWEI_FREEBUDS_PRO_3, HUAWEI_FREEBUDS_PRO_5, HUAWEI_FREECLIP,
                HUAWEI_FREECLIP_2, HUAWEI_FREELACE_PRO_2, HUAWEI_GENERIC
            )

        val usesLhdc: Boolean
            get() = this in setOf(
                BUDS_AIR_7_PRO, BUDS_AIR_8_PRO, ONEPLUS_BUDS_3, ONEPLUS_BUDS_4,
                ONEPLUS_BUDS_PRO_2, ONEPLUS_BUDS_PRO_3, ONEPLUS_BUDS_PRO_4,
                OPPO_ENCO_X2, OPPO_ENCO_X3, OPPO_ENCO_X4
            )

        val supportsDynamicAudio: Boolean
            get() = this in setOf(
                BUDS_T200, BUDS_T310, BUDS_T500_PRO, BUDS_AIR_5_PRO, BUDS_AIR_6, BUDS_AIR_6_PRO,
                BUDS_AIR_7, BUDS_AIR_7_PRO, BUDS_AIR_8, BUDS_AIR_8_PRO
            ) || (isNothing && this != NOTHING_EAR_1 && this != NOTHING_EAR_STICK)

        val supportsDualConnection: Boolean
            get() = this in setOf(
                BUDS_T200, BUDS_T310, BUDS_T500, BUDS_T500_PRO, BUDS_AIR_5_PRO, BUDS_AIR_6, BUDS_AIR_6_PRO,
                BUDS_AIR_7, BUDS_AIR_7_PRO, BUDS_AIR_8, BUDS_AIR_8_PRO,
                ONEPLUS_BUDS_3, ONEPLUS_BUDS_4, ONEPLUS_BUDS_PRO_2, ONEPLUS_BUDS_PRO_3, ONEPLUS_BUDS_PRO_4,
                ONEPLUS_NORD_BUDS_3_PRO, ONEPLUS_NORD_BUDS_4,
                OPPO_ENCO_X, OPPO_ENCO_X2, OPPO_ENCO_X3, OPPO_ENCO_X4,
                OPPO_ENCO_AIR_3, OPPO_ENCO_AIR_4, OPPO_ENCO_AIR_5, OPPO_ENCO_BUDS_3,
                HUAWEI_FREEBUDS_5I, HUAWEI_FREEBUDS_6I, HUAWEI_FREEBUDS_PRO,
                HUAWEI_FREEBUDS_PRO_2, HUAWEI_FREEBUDS_PRO_3, HUAWEI_FREEBUDS_PRO_5,
                HUAWEI_FREECLIP, HUAWEI_FREECLIP_2, HUAWEI_FREELACE_PRO_2, HUAWEI_GENERIC,
                GALAXY_BUDS_PRO, GALAXY_BUDS_2, GALAXY_BUDS_2_PRO, GALAXY_BUDS_FE,
                GALAXY_BUDS_3, GALAXY_BUDS_3_PRO, GALAXY_BUDS_4,
                XIAOMI_REDMI_BUDS_3_PRO, XIAOMI_REDMI_BUDS_5_PRO, XIAOMI_REDMI_BUDS_6_PRO, XIAOMI_REDMI_BUDS_8_ACTIVE, XIAOMI_BUDS_GENERIC
            ) || isBose || isSoundcore || isPixelBuds

        val supportsAutoPlayPause: Boolean
            get() = isNothing || isBose || isSoundcore || isSennheiser || isPixelBuds || isJbl || this in setOf(
                BUDS_AIR_5_PRO, BUDS_AIR_6, BUDS_AIR_6_PRO, BUDS_AIR_7, BUDS_AIR_7_PRO, BUDS_AIR_8, BUDS_AIR_8_PRO,
                ONEPLUS_BUDS_3, ONEPLUS_BUDS_4, ONEPLUS_BUDS_PRO, ONEPLUS_BUDS_PRO_2, ONEPLUS_BUDS_PRO_3, ONEPLUS_BUDS_PRO_4,
                OPPO_ENCO_X, OPPO_ENCO_X2, OPPO_ENCO_X3, OPPO_ENCO_X4,
                HUAWEI_FREEBUDS_4I, HUAWEI_FREEBUDS_5I, HUAWEI_FREEBUDS_6I,
                HUAWEI_FREEBUDS_PRO_2, HUAWEI_FREEBUDS_PRO_3, HUAWEI_FREEBUDS_PRO_5,
                HUAWEI_FREECLIP, HUAWEI_FREECLIP_2, HUAWEI_FREEBUDS_STUDIO, HUAWEI_GENERIC,
                XIAOMI_REDMI_BUDS_3_PRO, XIAOMI_REDMI_BUDS_5_PRO, XIAOMI_REDMI_BUDS_6_PRO, XIAOMI_BUDS_GENERIC
            )

        val supportsAutoAnswer: Boolean
            get() = this in setOf(
                BUDS_AIR_7_PRO, BUDS_AIR_8_PRO,
                XIAOMI_REDMI_BUDS_3_PRO, XIAOMI_REDMI_BUDS_5_PRO, XIAOMI_REDMI_BUDS_6_PRO, XIAOMI_BUDS_GENERIC
            ) || isBose || isSennheiser

        val supportsUseTime: Boolean get() = isRealme

        companion object {
            val selectableModels: List<BudsModel>
                get() = entries.filter { it != UNKNOWN }

            val modelsByBrand: List<Pair<Brand, List<BudsModel>>>
                get() = Brand.entries
                    .filter { it != Brand.UNKNOWN }
                    .map { brand -> brand to selectableModels.filter { it.brand == brand } }
                    .filter { it.second.isNotEmpty() }

            fun fromNameOrNull(enumName: String?): BudsModel? {
                if (enumName.isNullOrBlank()) return null
                return entries.firstOrNull { it.name == enumName }
            }

            fun fromDeviceName(name: String?): BudsModel {
                if (name.isNullOrBlank()) return UNKNOWN
                val n = name.lowercase().replace(Regex("\\s+"), " ").trim()

                // 1. Sony MDR
                if (n.contains("wh-1000") || n.contains("wf-1000") || n.contains("linkbuds") ||
                    n.contains("ult wear") || n.contains("wh-ch") || n.contains("wf-c") || n.startsWith("sony ")
                ) {
                    return when {
                        n.contains("wf-1000") -> SONY_WF_1000XM5
                        n.contains("wh-1000") -> SONY_WH_1000XM5
                        n.contains("linkbuds") -> SONY_LINKBUDS
                        n.contains("ult") || n.contains("wh-ch") || n.contains("wf-c") -> SONY_ULT_CH
                        else -> SONY_GENERIC
                    }
                }

                // 2. Bose BMAP
                if (n.contains("bose") || n.contains("quietcomfort") || n.contains("qc35") || n.contains("qc45") || n.contains("nc 700") || n.contains("nc700")) {
                    return when {
                        n.contains("ultra open") || n.contains("sport") -> BOSE_SPORT_OPEN
                        n.contains("earbuds") -> BOSE_QC_EARBUDS
                        n.contains("700") -> BOSE_NC_700
                        n.contains("35") || n.contains("45") -> BOSE_QC_45_35
                        else -> BOSE_QC_ULTRA_HEADPHONES
                    }
                }

                // 3. Anker Soundcore
                if (n.contains("soundcore")) {
                    return when {
                        n.contains("liberty") -> SOUNDCORE_LIBERTY_4_NC
                        n.contains("space q45") || n.contains("space one") -> SOUNDCORE_SPACE_Q45
                        n.contains("space a40") -> SOUNDCORE_SPACE_A40
                        n.contains("q30") || n.contains("q35") || n.contains("tune pro") -> SOUNDCORE_LIFE_Q30
                        n.contains("note 3") || n.contains("dot 2") -> SOUNDCORE_LIFE_NOTE_3
                        n.contains("p30i") || n.contains("p40i") || n.contains("r50i") -> SOUNDCORE_P30I
                        else -> SOUNDCORE_GENERIC
                    }
                }

                // 4. Xiaomi / Redmi Buds
                if (n.contains("redmi buds") || n.contains("xiaomi buds") || n.contains("poco buds")) {
                    return when {
                        n.contains("8 active") -> XIAOMI_REDMI_BUDS_8_ACTIVE
                        n.contains("6 active") || n.contains("6 lite") -> XIAOMI_REDMI_BUDS_6_ACTIVE
                        n.contains("6") -> XIAOMI_REDMI_BUDS_6_PRO
                        n.contains("5") -> XIAOMI_REDMI_BUDS_5_PRO
                        n.contains("4") -> XIAOMI_REDMI_BUDS_4_ACTIVE
                        n.contains("3 pro") -> XIAOMI_REDMI_BUDS_3_PRO
                        else -> XIAOMI_BUDS_GENERIC
                    }
                }

                // 5. Sennheiser GAIA
                if (n.contains("sennheiser") || n.contains("momentum") || n.contains("accentum")) {
                    return when {
                        n.contains("tw") || n.contains("true wireless") -> SENNHEISER_MOMENTUM_TW
                        n.contains("accentum") || n.contains("cx ") -> SENNHEISER_ACCENTUM
                        else -> SENNHEISER_MOMENTUM_4
                    }
                }

                // 6. Jabra
                if (n.contains("jabra")) {
                    return when {
                        n.contains("evolve") -> JABRA_EVOLVE
                        n.contains("65t") || n.contains("75t") || n.contains("elite 4") || n.contains("elite 5") -> JABRA_ELITE_CLASSIC
                        else -> JABRA_ELITE_PRO
                    }
                }

                // 7. Google Pixel Buds
                if (n.contains("pixel buds")) {
                    return when {
                        n.contains("pro 2") || n.contains("pro2") -> PIXEL_BUDS_PRO_2
                        n.contains("pro") -> PIXEL_BUDS_PRO
                        else -> PIXEL_BUDS_A
                    }
                }

                // 8. JBL
                if (n.startsWith("jbl ") || n.contains("jbl ")) {
                    return when {
                        n.contains("tour") || n.contains("live") -> JBL_TOUR_LIVE
                        else -> JBL_TUNE_WAVE
                    }
                }

                // 9. Samsung Galaxy Buds
                if (n.contains("galaxy buds") || n.contains("buds live") || n.contains("buds fe") ||
                    n.contains("buds core") || n.contains("buds2") || n.contains("buds3") || n.contains("buds4")
                ) {
                    return when {
                        n.contains("buds4") || n.contains("buds 4") -> GALAXY_BUDS_4
                        n.contains("buds3 pro") || n.contains("buds 3 pro") -> GALAXY_BUDS_3_PRO
                        n.contains("buds3") || n.contains("buds 3") -> GALAXY_BUDS_3
                        n.contains("buds core") -> GALAXY_BUDS_CORE
                        n.contains("buds fe") -> GALAXY_BUDS_FE
                        n.contains("buds2 pro") || n.contains("buds 2 pro") -> GALAXY_BUDS_2_PRO
                        n.contains("buds2") || n.contains("buds 2") -> GALAXY_BUDS_2
                        n.contains("buds pro") -> GALAXY_BUDS_PRO
                        n.contains("buds live") -> GALAXY_BUDS_LIVE
                        n.contains("buds+") || n.contains("buds plus") -> GALAXY_BUDS_PLUS
                        else -> GALAXY_BUDS
                    }
                }

                // 10. Nothing / CMF (all 23 swift-nothing-ear models)
                if (n.contains("nothing") || n.contains("cmf") || n.startsWith("ear (") || n == "ear") {
                    return when {
                        n.contains("cmf") && n.contains("speaker") -> CMF_SPEAKER
                        n.contains("cmf") && n.contains("clip") -> CMF_CLIP_PRO
                        n.contains("cmf") && n.contains("headphone") -> CMF_HEADPHONE_PRO
                        n.contains("cmf") && n.contains("neckband") -> CMF_NECKBAND_PRO
                        n.contains("cmf") && n.contains("neo") -> CMF_BUDS_NEO
                        n.contains("cmf") && (n.contains("pro 2") || n.contains("pro2")) -> CMF_BUDS_PRO_2
                        n.contains("cmf") && n.contains("pro") -> CMF_BUDS_PRO
                        n.contains("cmf") && (n.contains("2 plus") || n.contains("2+")) -> CMF_BUDS_2_PLUS
                        n.contains("cmf") && n.contains("2a") -> CMF_BUDS_2A
                        n.contains("cmf") && (n.contains("buds 2") || n.contains("buds2")) -> CMF_BUDS_2
                        n.contains("cmf") -> CMF_BUDS
                        n.contains("headphone") && n.contains("pro") -> NOTHING_HEADPHONE_1_PRO
                        n.contains("headphone") && (n.contains("(a)") || n.endsWith(" a")) -> NOTHING_HEADPHONE_A
                        n.contains("headphone") -> NOTHING_HEADPHONE_1
                        n.contains("stick") -> NOTHING_EAR_STICK
                        n.contains("open") -> NOTHING_EAR_OPEN
                        n.contains("(3a)") || n.contains("ear 3a") -> NOTHING_EAR_3A
                        n.contains("(3)") || n.contains("ear 3") -> NOTHING_EAR_3
                        n.contains("(a)") || n.contains("ear a") -> NOTHING_EAR_A
                        n.contains("(2)") || n.contains("ear 2") -> NOTHING_EAR_2
                        n.contains("(1)") || n.contains("ear 1") -> NOTHING_EAR_1
                        n.contains("ear") -> NOTHING_EAR
                        else -> NOTHING_GENERIC
                    }
                }

                // 11. HUAWEI FreeBuds / FreeClip / FreeLace
                val isHuaweiBrand = n.contains("huawei") || n.contains("freebuds") ||
                    n.contains("freeclip") || n.contains("freelace")
                if (isHuaweiBrand) {
                    return when {
                        n.contains("freeclip 2") || n.contains("freeclip2") -> HUAWEI_FREECLIP_2
                        n.contains("freeclip") -> HUAWEI_FREECLIP
                        n.contains("freelace pro 2") || n.contains("freelace pro2") -> HUAWEI_FREELACE_PRO_2
                        n.contains("freelace pro") -> HUAWEI_FREELACE_PRO
                        n.contains("studio") -> HUAWEI_FREEBUDS_STUDIO
                        n.contains("pro 5") || n.contains("pro5") -> HUAWEI_FREEBUDS_PRO_5
                        n.contains("pro 4") || n.contains("pro4") -> HUAWEI_FREEBUDS_PRO_3
                        n.contains("pro 3") || n.contains("pro3") -> HUAWEI_FREEBUDS_PRO_3
                        n.contains("pro 2") || n.contains("pro2") -> HUAWEI_FREEBUDS_PRO_2
                        n.contains("freebuds pro") -> HUAWEI_FREEBUDS_PRO
                        n.contains("6i") -> HUAWEI_FREEBUDS_6I
                        n.contains("5i") -> HUAWEI_FREEBUDS_5I
                        n.contains("4i") -> HUAWEI_FREEBUDS_4I
                        n.contains("se 4") || n.contains("se4") -> HUAWEI_FREEBUDS_SE_4
                        n.contains("se 2") || n.contains("se2") -> HUAWEI_FREEBUDS_SE_2
                        n.contains("freebuds se") -> HUAWEI_FREEBUDS_SE
                        n.contains("freebuds 3") -> HUAWEI_FREEBUDS_3
                        else -> HUAWEI_GENERIC
                    }
                }

                // 12. HONOR (Huawei SPP & HONOR CHOICE SPP)
                if (n.contains("honor") || n.contains("荣耀") || n.contains("moecen")) {
                    return when {
                        n.contains("earbuds s2") -> HONOR_EARBUDS_S2
                        n.contains("earbuds 2") && !n.contains("choice") -> HUAWEI_FREEBUDS_4I
                        n.contains("x8") -> HONOR_CHOICE_X8
                        n.contains("x5 pro") || n.contains("x5pro") -> HONOR_CHOICE_X5_PRO
                        n.contains("x5") -> HONOR_CHOICE_X5
                        n.contains("x3") -> HONOR_CHOICE_X3
                        n.contains("x2") || n.contains("earbuds x") || n.contains("moecen") -> HONOR_CHOICE_X2
                        n.contains("choice") || n.contains("earbuds") -> HONOR_CHOICE_GENERIC
                        else -> UNKNOWN
                    }
                }

                // 13. OnePlus Buds (QuickBuds full whiteList)
                if (n.contains("oneplus") || n.contains("nord buds")) {
                    return when {
                        n.contains("bullets") -> ONEPLUS_BULLETS
                        n.contains("nord") && n.contains("4") -> ONEPLUS_NORD_BUDS_4
                        n.contains("nord") && n.contains("3 pro") -> ONEPLUS_NORD_BUDS_3_PRO
                        n.contains("nord") && n.contains("3") -> ONEPLUS_NORD_BUDS_3
                        n.contains("nord") -> ONEPLUS_NORD_BUDS
                        n.contains("pro 4") || n.contains("pro4") -> ONEPLUS_BUDS_PRO_4
                        n.contains("pro 3") || n.contains("pro3") -> ONEPLUS_BUDS_PRO_3
                        n.contains("pro 2") || n.contains("pro2") -> ONEPLUS_BUDS_PRO_2
                        n.contains("buds pro") -> ONEPLUS_BUDS_PRO
                        n.contains("ace 3") || n.contains("ace3") -> ONEPLUS_BUDS_ACE_3
                        n.contains("ace 2") || n.contains("ace2") -> ONEPLUS_BUDS_ACE_2
                        n.contains("ace") -> ONEPLUS_BUDS_ACE
                        n.contains("3v") -> ONEPLUS_BUDS_3V
                        n.contains("buds 4") || n.contains("buds4") -> ONEPLUS_BUDS_4
                        n.contains("buds 3") || n.contains("buds3") -> ONEPLUS_BUDS_3
                        n.contains("clip") || n.contains("open") || n.contains("flow") -> ONEPLUS_BUDS_CLIP
                        n.contains("buds n") || n.contains("buds v") -> ONEPLUS_BUDS_N
                        n.contains("buds z") -> ONEPLUS_BUDS_Z
                        n.contains("oneplus buds") -> ONEPLUS_BUDS
                        else -> ONEPLUS_GENERIC
                    }
                }

                // 14. OPPO Enco (QuickBuds full whiteList)
                if (n.contains("oppo") || n.contains("enco")) {
                    return when {
                        n.contains("x4") -> OPPO_ENCO_X4
                        n.contains("x3") -> OPPO_ENCO_X3
                        n.contains("x2") -> OPPO_ENCO_X2
                        n.contains("enco x") -> OPPO_ENCO_X
                        n.contains("air5") || n.contains("air 5") -> OPPO_ENCO_AIR_5
                        n.contains("air4") || n.contains("air 4") -> OPPO_ENCO_AIR_4
                        n.contains("air3") || n.contains("air 3") -> OPPO_ENCO_AIR_3
                        n.contains("air") -> OPPO_ENCO_AIR
                        n.contains("clip") -> OPPO_ENCO_CLIP
                        n.contains("free") -> OPPO_ENCO_FREE_2
                        n.contains("buds3") || n.contains("buds 3") -> OPPO_ENCO_BUDS_3
                        n.contains("buds") -> OPPO_ENCO_BUDS
                        n.contains("enco r") -> OPPO_ENCO_R
                        n.contains("w11") || n.contains("w31") || n.contains("w51") || n.contains("m31") || n.contains("m32") || n.contains("m33") -> OPPO_ENCO_W
                        else -> OPPO_GENERIC
                    }
                }

                // 15. realme Buds & DIZO (QuickBuds full whiteList)
                if (n.contains("dizo")) return DIZO_BUDS
                val isRealmeOrBuds = n.contains("realme") || n.contains("buds")
                if (isRealmeOrBuds) {
                    return when {
                        n.contains("air 8 pro") || n.contains("air8 pro") -> BUDS_AIR_8_PRO
                        n.contains("air 8") || n.contains("air8") -> BUDS_AIR_8
                        n.contains("air 7 pro") || n.contains("air7 pro") -> BUDS_AIR_7_PRO
                        n.contains("air 7") || n.contains("air7") -> BUDS_AIR_7
                        n.contains("air 6 pro") || n.contains("air6 pro") -> BUDS_AIR_6_PRO
                        n.contains("air 6") || n.contains("air6") -> BUDS_AIR_6
                        n.contains("air 5 pro") || n.contains("air5 pro") -> BUDS_AIR_5_PRO
                        n.contains("air 5") || n.contains("air5") -> BUDS_AIR_5
                        n.contains("air 3") || n.contains("air3") -> BUDS_AIR_3
                        n.contains("air 2") || n.contains("air2") || n.contains("air pro") || n.contains("air neo") -> BUDS_AIR_2
                        n.contains("t500 pro") -> BUDS_T500_PRO
                        n.contains("t500") -> BUDS_T500
                        n.contains("t310") -> BUDS_T310
                        n.contains("t300") -> BUDS_T300
                        n.contains("t200 lite") || n.contains("t200x") -> BUDS_T200_LITE
                        n.contains("t200") -> BUDS_T200
                        n.contains("t110") || n.contains("t100") || n.contains("t01") -> BUDS_T110
                        n.contains("n1") -> BUDS_N1
                        n.contains("clip") -> BUDS_CLIP
                        n.contains("q2") -> BUDS_Q2
                        n.contains("wireless") -> BUDS_WIRELESS
                        n.contains("realme") -> REALME_GENERIC
                        else -> UNKNOWN
                    }
                }

                return UNKNOWN
            }
        }
    }

    enum class AncMode(val modeByte: Byte, @StringRes val titleRes: Int) {
        NOISE_REDUCTION(0x08, R.string.anc_noise_reduction),
        OFF(0x01, R.string.anc_off),
        TRANSPARENCY(0x02, R.string.anc_transparency);

        companion object {
            fun fromByte(b: Byte): AncMode? = entries.firstOrNull { it.modeByte == b }
        }
    }

    enum class ComponentRole(val id: Int, @StringRes val titleRes: Int) {
        LEFT_EAR(1, R.string.battery_left),
        RIGHT_EAR(2, R.string.battery_right),
        CASE(3, R.string.battery_case);

        companion object {
            fun fromId(id: Int): ComponentRole? = entries.firstOrNull { it.id == id }
        }
    }

    enum class EqPreset(val eqId: Byte, @StringRes val titleRes: Int) {
        SERENADE(0x02, R.string.eq_serenade),
        ORIGINAL_SOUND(0x00, R.string.eq_original_sound),
        PURE_BASS(0x03, R.string.eq_pure_bass),
        DEEP_BASS(0x01, R.string.eq_deep_bass);

        companion object {
            fun fromByte(b: Byte): EqPreset? = entries.firstOrNull { it.eqId == b }
        }
    }

    data class CustomEqSummary(
        val eqId: Int,
        val name: String,
        val isSelected: Boolean
    )

    enum class TouchSide(val deviceType: Byte, @StringRes val titleRes: Int) {
        LEFT(0x01, R.string.touch_side_left),
        RIGHT(0x02, R.string.touch_side_right);

        companion object {
            fun fromDeviceType(b: Byte): TouchSide? = entries.firstOrNull { it.deviceType == b }
        }
    }

    enum class TouchGesture(val actionId: Byte, @StringRes val titleRes: Int) {
        DOUBLE_TAP(0x02, R.string.gesture_double_tap),
        TRIPLE_TAP(0x03, R.string.gesture_triple_tap),
        LONG_PRESS(0x04, R.string.gesture_long_press);

        companion object {
            fun fromActionId(b: Byte): TouchGesture? = entries.firstOrNull { it.actionId == b }
        }
    }

    enum class TouchAction(val functionId: Byte, @StringRes val titleRes: Int) {
        PLAY_PAUSE(0x01, R.string.action_play_pause),
        NEXT_TRACK(0x06, R.string.action_next_track),
        PREV_TRACK(0x05, R.string.action_prev_track),
        VOICE_ASSISTANT(0x03, R.string.action_voice_assistant),
        ANC_TOGGLE(0x08, R.string.action_anc_toggle),
        DEVICE_SWITCH(0x0D, R.string.action_device_switch),
        GAME_MODE(0x11, R.string.action_game_mode),
        VOLUME_UP(0x0B, R.string.action_volume_up),
        VOLUME_DOWN(0x0C, R.string.action_volume_down),
        NONE(0x00, R.string.action_none);

        companion object {
            fun fromFunctionId(b: Byte): TouchAction {
                if (b.toInt() == 4) return VOICE_ASSISTANT
                return entries.firstOrNull { it.functionId == b } ?: NONE
            }
        }
    }

    fun getAvailableActions(gesture: TouchGesture): List<TouchAction> {
        return when (gesture) {
            TouchGesture.DOUBLE_TAP -> listOf(
                TouchAction.PLAY_PAUSE,
                TouchAction.NEXT_TRACK,
                TouchAction.VOICE_ASSISTANT,
                TouchAction.NONE
            )
            TouchGesture.TRIPLE_TAP -> listOf(
                TouchAction.PREV_TRACK,
                TouchAction.NEXT_TRACK,
                TouchAction.VOLUME_UP,
                TouchAction.VOLUME_DOWN,
                TouchAction.DEVICE_SWITCH,
                TouchAction.VOICE_ASSISTANT,
                TouchAction.GAME_MODE,
                TouchAction.NONE
            )
            TouchGesture.LONG_PRESS -> listOf(
                TouchAction.ANC_TOGGLE,
                TouchAction.VOICE_ASSISTANT,
                TouchAction.GAME_MODE,
                TouchAction.NONE
            )
        }
    }

    data class KeySettingItem(
        val side: TouchSide,
        val gesture: TouchGesture,
        val action: TouchAction
    )

    data class BatteryInfo(
        val role: ComponentRole,
        val level: Int,          // 0..100% (-1 если нет данных)
        val isCharging: Boolean
    )

    data class ConnectedDeviceInfo(
        val mac: String,
        val name: String,
        val connectState: Int,
        val flag: Int
    ) {
        val isConnected: Boolean get() = connectState != 0
    }

    data class T300State(
        val leftBattery: BatteryInfo = BatteryInfo(ComponentRole.LEFT_EAR, -1, false),
        val rightBattery: BatteryInfo = BatteryInfo(ComponentRole.RIGHT_EAR, -1, false),
        val caseBattery: BatteryInfo = BatteryInfo(ComponentRole.CASE, -1, false),
        val ancMode: AncMode? = null,
        val ancLevel: HuaweiProtocol.AncLevel? = null,
        val gameMode: Boolean? = null,
        val spatialAudio: Boolean? = null,
        val highQualityAudio: Boolean? = null,
        val dynamicAudio: Boolean? = null,
        val dualConnection: Boolean? = null,
        val autoPlayPause: Boolean? = null,
        val autoAnswer: Boolean? = null,
        val connectedDevices: List<ConnectedDeviceInfo> = emptyList(),
        val leftUseTimeMinutes: Int? = null,
        val rightUseTimeMinutes: Int? = null,
        val eqPreset: EqPreset? = null,
        val activeCustomEqName: String? = null,
        val touchSettings: Map<Pair<TouchSide, TouchGesture>, TouchAction> = emptyMap()
    )

    data class Frame(
        val command: Int,
        val address: Byte = DEVICE_ADDRESS_DEFAULT,
        val payload: ByteArray = ByteArray(0)
    ) {
        fun toByteArray(): ByteArray {
            val buffer = ByteBuffer.allocate(5 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putShort(command.toShort())
            buffer.put(address)
            buffer.putShort(payload.size.toShort())
            if (payload.isNotEmpty()) {
                buffer.put(payload)
            }
            return buffer.array()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Frame
            return command == other.command && address == other.address && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = command
            result = 31 * result + address
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /**
     * Оборачивает внутренний TL-кадр в транспортный кадр OPPOv1 (SOF 0xAA).
     */
    fun wrapOppoFrame(tlFrame: ByteArray): ByteArray {
        val payloadLen = tlFrame.size + 2 // +2 для байтов Flags и Reserved
        val lenBytes = encodeVarInt(payloadLen)
        val header = ByteArray(1 + lenBytes.size + 2)
        header[0] = OPPO_SOF
        System.arraycopy(lenBytes, 0, header, 1, lenBytes.size)
        val offset = 1 + lenBytes.size
        header[offset] = 0x00     // Flags: Single package
        header[offset + 1] = 0x00 // Reserved
        return header + tlFrame
    }

    private fun encodeVarInt(value: Int): ByteArray {
        if (value == 0) return byteArrayOf(0)
        var v = value
        val list = mutableListOf<Byte>()
        while (v != 0) {
            var b = (v and 0x7F)
            v = v ushr 7
            if (v != 0) b = b or 0x80
            list.add(b.toByte())
        }
        return list.toByteArray()
    }

    /**
     * Создание кадра запроса батареи: 0x0106 -> [0x06, 0x01, 0x00, 0x00, 0x00]
     */
    fun buildBatteryRequestFrame(address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray {
        return Frame(
            command = CMD_BATTERY_REQ,
            address = address,
            payload = ByteArray(0)
        ).toByteArray()
    }

    /**
     * Создание кадра запроса текущего режима ANC: 0x010C -> [0x0C, 0x01, addr, 0x02, 0x00, 0x01, 0x01]
     */
    fun buildAncStateRequestFrame(address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray =
        Frame(CMD_ANC_STATE_REQ, address, byteArrayOf(0x01, 0x01)).toByteArray()

    /**
     * Запрос состояния переключателя (SwitchFeature, Opcode 0x010D).
     * Для игрового режима featureId = 0x06 (FEATURE_ID_GAME_MODE).
     * Payload: [0x01, featureId]
     */
    fun buildSwitchFeatureRequestFrame(featureId: Byte = FEATURE_ID_GAME_MODE, address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray =
        Frame(CMD_SWITCH_FEATURE_REQ, address, byteArrayOf(0x01, featureId)).toByteArray()

    /**
     * Установка переключателя (SwitchFeature, Opcode 0x0403).
     * Для игрового режима featureId = 0x06 (FEATURE_ID_GAME_MODE), value = 0x01 (вкл) / 0x00 (выкл).
     * Payload: [featureId, value]
     */
    fun buildSwitchFeatureCommandFrame(featureId: Byte = FEATURE_ID_GAME_MODE, enabled: Boolean, address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray =
        Frame(CMD_SWITCH_FEATURE_SET, address, byteArrayOf(featureId, if (enabled) 0x01 else 0x00)).toByteArray()

    /**
     * Регистрация на получение асинхронных уведомлений (Opcode 0x0201).
     * eventType: 0x03 = ANC, 0x01 = батарея, 0x02 = статус наушников
     */
    fun buildRegisterNotificationFrame(eventType: Byte, address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray =
        Frame(CMD_NOTIFICATION_REGISTER, address, byteArrayOf(eventType)).toByteArray()

    /**
     * Создание кадра переключения ANC:
     * 0x0404 -> [0x04, 0x04, addr, 0x03, 0x00, 0x01, 0x01, modeByte]
     */
    fun buildAncCommandFrame(mode: AncMode, address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray {
        val payload = byteArrayOf(0x01, 0x01, mode.modeByte)
        return Frame(
            command = CMD_ANC_SET,
            address = address,
            payload = payload
        ).toByteArray()
    }

    /**
     * Запрос текущего пресета эквалайзера (Opcode 0x010F).
     * Payload: пустой.
     */
    fun buildEqRequestFrame(address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray =
        Frame(CMD_EQ_REQ, address, ByteArray(0)).toByteArray()

    /**
     * Установка пресета эквалайзера (Opcode 0x0406).
     * Payload: [eqId] (1 байт).
     */
    fun buildEqCommandFrame(preset: EqPreset, address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray =
        Frame(CMD_EQ_SET, address, byteArrayOf(preset.eqId)).toByteArray()

    /**
     * Запрос списка пользовательских эквалайзеров (Opcode 0x0122).
     * Payload: пустой.
     */
    fun buildAllEqRequestFrame(address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray =
        Frame(CMD_ALL_EQ_REQ, address, ByteArray(0)).toByteArray()

    private val KEY_FUNCTION_REQ_PAYLOAD = byteArrayOf(
        0x0E, // count = 14
        0x01, 0x03, 0x04, 0x05, 0x06, 0x0B, 0x0C, 0x08, 0x0D, 0x11, 0x1F, 0x1A, 0x1B, 0x00
    )

    /**
     * Запрос текущих настроек кнопок (Opcode 0x0108).
     */
    fun buildKeyFunctionRequestFrame(address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray =
        Frame(CMD_KEY_FUNCTION_REQ, address, KEY_FUNCTION_REQ_PAYLOAD).toByteArray()

    /**
     * Установка настройки кнопки (Opcode 0x0401).
     * Payload: [0x01, deviceType, 0x01, actionId, functionId]
     */
    fun buildKeyFunctionCommandFrame(
        side: TouchSide,
        gesture: TouchGesture,
        action: TouchAction,
        address: Byte = DEVICE_ADDRESS_DEFAULT
    ): ByteArray {
        val payload = byteArrayOf(
            0x01,           // count = 1
            side.deviceType,
            0x01,           // button = 1
            gesture.actionId,
            action.functionId
        )
        return Frame(CMD_KEY_FUNCTION_SET, address, payload).toByteArray()
    }

    /**
     * Парсер сырого кадра (поддерживает как кадры в обёртке OPPO 0xAA, так и чистые TL-кадры).
     */
    fun parseFrame(data: ByteArray): Frame? {
        val unwrapped = unwrapOppo(data) ?: data
        if (unwrapped.size < 5) return null
        val buffer = ByteBuffer.wrap(unwrapped).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.short.toInt() and 0xFFFF
        val address = buffer.get()
        val length = buffer.short.toInt() and 0xFFFF
        if (unwrapped.size < 5 + length) return null

        val payload = ByteArray(length)
        if (length > 0) {
            buffer.get(payload)
        }
        return Frame(command, address, payload)
    }

    private fun unwrapOppo(data: ByteArray): ByteArray? {
        if (data.isEmpty() || data[0] != OPPO_SOF || data.size < 4) return null
        var offset = 1
        var len = 0
        var shift = 0
        while (offset < data.size) {
            val b = data[offset].toInt() and 0xFF
            len = len or ((b and 0x7F) shl shift)
            offset++
            if ((b and 0x80) == 0) break
            shift += 7
        }
        if (offset + 2 <= data.size) {
            val flags = data[offset].toInt() and 0xFF
            val hasFsn = (flags and 0x03) != 0
            val skip = if (hasFsn) 3 else 2
            val payloadStart = offset + skip
            if (payloadStart <= data.size) {
                return data.copyOfRange(payloadStart, data.size)
            }
        }
        return null
    }

    /**
     * Потоковый разбор пакетов из буфера сокета/GATT.
     * Автоматически распознаёт SOF 0xAA (OPPO wrapper) и чистые TL wire frames.
     */
    fun parseFramesFromStreamBuffer(streamBuffer: ByteArray): Pair<List<Frame>, ByteArray> {
        val frames = mutableListOf<Frame>()
        var offset = 0
        val total = streamBuffer.size

        while (offset < total) {
            if (streamBuffer[offset] == OPPO_SOF) {
                // OPPO-пакет
                var varLenOffset = offset + 1
                var linkDataLen = 0
                var shift = 0
                var varLenBytesCount = 0
                while (varLenOffset < total && varLenBytesCount < 4) {
                    val b = streamBuffer[varLenOffset].toInt() and 0xFF
                    linkDataLen = linkDataLen or ((b and 0x7F) shl shift)
                    varLenOffset++
                    varLenBytesCount++
                    if ((b and 0x80) == 0) break
                    shift += 7
                }

                val fullPacketLen = 1 + varLenBytesCount + linkDataLen
                if (offset + fullPacketLen <= total) {
                    val packetBytes = streamBuffer.copyOfRange(offset, offset + fullPacketLen)
                    parseFrame(packetBytes)?.let { frames.add(it) }
                    offset += fullPacketLen
                } else {
                    // Неполный OPPO пакет — ждём остаток
                    break
                }
            } else if (offset + 5 <= total) {
                // Чистый TL-пакет
                val cmd = (streamBuffer[offset].toInt() and 0xFF) or ((streamBuffer[offset + 1].toInt() and 0xFF) shl 8)
                val addr = streamBuffer[offset + 2]
                val len = (streamBuffer[offset + 3].toInt() and 0xFF) or ((streamBuffer[offset + 4].toInt() and 0xFF) shl 8)
                val fullFrameSize = 5 + len

                if (offset + fullFrameSize <= total) {
                    val payload = if (len > 0) {
                        streamBuffer.copyOfRange(offset + 5, offset + fullFrameSize)
                    } else {
                        ByteArray(0)
                    }
                    frames.add(Frame(command = cmd, address = addr, payload = payload))
                    offset += fullFrameSize
                } else {
                    // Неполный чистый кадр
                    break
                }
            } else {
                break
            }
        }

        val remaining = if (offset < total) {
            streamBuffer.copyOfRange(offset, total)
        } else {
            ByteArray(0)
        }

        return Pair(frames, remaining)
    }

    /**
     * Парсинг ответа батареи (Opcode 0x8106).
     *
     * Структура payload:
     * Byte 0: Status code (0x00 = Success)
     * Byte 1: Count (кол-во записей, обычно 3)
     * Затем Count записей по 2 байта:
     *   Offset 0: Component ID (1 = Left, 2 = Right, 3 = Case)
     *   Offset 1: bits 0..6: level (0..100), bit 7: isCharging
     */
    fun parseBatteryPayload(payload: ByteArray): List<BatteryInfo> {
        if (payload.size < 2) return emptyList()
        val status = payload[0].toInt()
        if (status != 0) return emptyList()

        val count = payload[1].toInt() and 0xFF
        val result = mutableListOf<BatteryInfo>()
        var offset = 2

        for (i in 0 until count) {
            if (offset + 1 >= payload.size) break
            val componentId = payload[offset].toInt() and 0xFF
            val rawValue = payload[offset + 1].toInt() and 0xFF

            val level = rawValue and 0x7F
            val isCharging = (rawValue and 0x80) != 0

            val role = ComponentRole.fromId(componentId)
            if (role != null) {
                // Если кейс закрыт / неактивен, наушники присылают 03 00 (0% без зарядки).
                // В таком случае заряд считается недоступным (-1 / "—").
                val effectiveLevel = if (role == ComponentRole.CASE && level == 0 && !isCharging) -1 else level
                result.add(BatteryInfo(role, effectiveLevel, isCharging))
            }
            offset += 2
        }
        return result
    }

    /**
     * Парсинг ответа ANC (Opcode 0x8404).
     * Возвращает true, если смена подтверждена (status == 0).
     */
    fun parseAncResponse(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        val status = payload[0].toInt()
        return status == 0
    }

    /**
     * Парсинг ответа состояния ANC (Opcode 0x810C).
     * Ожидается payload: [status (0x00), action (0x01), type (0x01), noiseStatus]
     */
    fun parseAncStatePayload(p: ByteArray): AncMode? {
        if (p.size < 4 || p[0].toInt() != 0 || p[1].toInt() != 1) return null
        return AncMode.fromByte(p[3])
    }

    /**
     * Парсинг асинхронного события смены ANC (тип 0x03).
     * Если size >= 4: [03, action, type, noiseStatus], режим в p[3] при action==1.
     * Если size == 3: [03, type, noiseStatus], режим в p[2].
     */
    fun parseAncEventPayload(p: ByteArray): AncMode? {
        if (p.isEmpty() || p[0].toInt() != 0x03) return null
        return when {
            p.size >= 4 -> if (p[1].toInt() == 1) AncMode.fromByte(p[3]) else null
            p.size == 3 -> AncMode.fromByte(p[2])
            else -> null
        }
    }

    /**
     * Парсинг ответа состояния SwitchFeature (Opcode 0x810D).
     * Структура payload: [status (0x00), count (N), (featureId, value)...]
     * Возвращает значение для указанного featureId: true = вкл, false = выкл, null = ошибка/не найдено.
     */
    fun parseSwitchFeaturePayload(p: ByteArray, targetFeatureId: Byte = FEATURE_ID_GAME_MODE): Boolean? {
        if (p.size < 4 || p[0].toInt() != 0) return null
        val count = p[1].toInt() and 0xFF
        var offset = 2
        for (i in 0 until count) {
            if (offset + 1 >= p.size) break
            val featureId = p[offset]
            val value = p[offset + 1]
            if (featureId == targetFeatureId) {
                return value.toInt() == 1
            }
            offset += 2
        }
        return null
    }

    /**
     * Парсинг ответа подтверждения установки переключателя (Opcode 0x8403).
     * Payload: [status] (0x00 = Success).
     */
    fun parseSwitchFeatureSetResponse(p: ByteArray): Boolean {
        if (p.isEmpty()) return false
        return p[0].toInt() == 0
    }

    /**
     * Парсинг асинхронного события переключателя (из CMD_EVENT 0x0204, тип события 0x05).
     * Payload: [0x05, value] -> value: 1 = вкл, 0 = выкл.
     */
    fun parseGameModeEventPayload(p: ByteArray): Boolean? {
        if (p.size < 2 || p[0] != EVENT_TYPE_GAME_MODE) return null
        return p[1].toInt() == 1
    }

    /**
     * Парсинг ответа текущего пресета EQ (Opcode 0x810F).
     * Структура payload: [status (0x00), eqId]
     */
    fun parseEqPayload(p: ByteArray): EqPreset? {
        if (p.size < 2 || p[0].toInt() != 0) return null
        return EqPreset.fromByte(p[1])
    }

    /**
     * Возвращает сырой eqId из ответа 0x810F (даже если это кастомный ID).
     */
    fun parseRawEqId(p: ByteArray): Int? {
        if (p.size < 2 || p[0].toInt() != 0) return null
        return p[1].toInt() and 0xFF
    }

    /**
     * Парсинг ответа подтверждения установки пресета EQ (Opcode 0x8406).
     * Payload: [status] (0x00 = Success).
     */
    fun parseEqSetResponse(p: ByteArray): Boolean {
        if (p.isEmpty()) return false
        return p[0].toInt() == 0
    }

    /**
     * Парсинг списка всех пользовательских кривых EQ (Opcode 0x8122).
     * Формат по CommandUtil.b(1, bArrE):
     * [0] status (0 = success)
     * [1] count
     * Для каждого элемента:
     *   isSelected (1 байт, 1 = активен)
     *   minValue (1 байт)
     *   maxValue (1 байт)
     *   eqId (1 байт)
     *   nameLength (1 байт)
     *   name (nameLength байт UTF-8)
     *   freqCount (1 байт)
     *   freqCount * (2 байта частота + 1 байт dB)
     */
    fun parseAllEqPayload(p: ByteArray): List<CustomEqSummary> {
        if (p.size < 2 || p[0].toInt() != 0) return emptyList()
        val count = p[1].toInt() and 0xFF
        val result = mutableListOf<CustomEqSummary>()
        var offset = 2
        for (i in 0 until count) {
            if (offset + 5 > p.size) break
            val isSelected = (p[offset].toInt() and 0xFF) == 1
            val eqId = p[offset + 3].toInt() and 0xFF
            val nameLength = p[offset + 4].toInt() and 0xFF
            offset += 5
            if (offset + nameLength > p.size) break
            val name = String(p, offset, nameLength, Charsets.UTF_8)
            offset += nameLength
            if (offset >= p.size) {
                result.add(CustomEqSummary(eqId, name, isSelected))
                break
            }
            val freqCount = p[offset].toInt() and 0xFF
            offset += 1 + freqCount * 3
            result.add(CustomEqSummary(eqId, name, isSelected))
        }
        return result
    }

    /**
     * Парсинг ответа настроек кнопок (Opcode 0x8108).
     * Формат: [status (0x00), count (N), (deviceType, button, action, function)...]
     */
    fun parseKeyFunctionPayload(p: ByteArray): List<KeySettingItem> {
        if (p.size < 2 || p[0].toInt() != 0) return emptyList()
        val count = p[1].toInt() and 0xFF
        if (p.size < 2 + count * 4) return emptyList()

        val result = mutableListOf<KeySettingItem>()
        var offset = 2
        for (i in 0 until count) {
            val devType = p[offset]
            val button = p[offset + 1]
            val actionId = p[offset + 2]
            val funcId = p[offset + 3]

            val side = TouchSide.fromDeviceType(devType)
            val gesture = TouchGesture.fromActionId(actionId)
            val action = TouchAction.fromFunctionId(funcId)

            if (side != null && gesture != null) {
                result.add(KeySettingItem(side, gesture, action))
            }
            offset += 4
        }
        return result
    }

    /**
     * Парсинг ответа подтверждения установки настройки кнопки (Opcode 0x8401).
     * Payload: [status] (0x00 = Success).
     */
    fun parseKeyFunctionSetResponse(p: ByteArray): Boolean {
        if (p.isEmpty()) return false
        return p[0].toInt() == 0
    }

    /**
     * Запрос списка подключённых устройств при Dual Device Connection (Opcode 0x0112).
     * Payload: пустой.
     */
    fun buildMultiConnectInfoRequestFrame(address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray =
        Frame(CMD_MULTI_CONNECT_INFO_REQ, address, ByteArray(0)).toByteArray()

    /**
     * Парсинг ответа / события списка подключённых устройств (Opcode 0x8112 / Event 0x06).
     * Формат по CommandUtil.h(String, int, byte[]):
     *   [startOffset] count (N)
     *   Для каждого устройства:
     *     6 байт MAC
     *     1 байт entryLen (длина остатка записи после entryLen)
     *     1 байт connectState
     *     1 байт flag
     *     1 байт nameLen
     *     nameLen байт UTF-8 имя устройства
     *     (entryLen - nameLen - 3) байт резерв/пропуск
     */
    fun parseMultiConnectInfoPayload(p: ByteArray, hasStatusByte: Boolean = true): List<ConnectedDeviceInfo>? {
        val countOffset = if (hasStatusByte) 1 else 0
        if (hasStatusByte && (p.isEmpty() || p[0].toInt() != 0)) return null
        if (p.size <= countOffset) return null

        val count = p[countOffset].toInt() and 0xFF
        if (count == 0) return emptyList()

        val result = mutableListOf<ConnectedDeviceInfo>()
        var offset = countOffset + 1
        for (i in 0 until count) {
            if (offset + 10 > p.size) break
            val macBytes = p.copyOfRange(offset, offset + 6)
            val mac = macBytes.reversedArray().joinToString(":") { "%02X".format(it) }
            val entryLen = p[offset + 6].toInt() and 0xFF
            val connectState = p[offset + 7].toInt() and 0xFF
            val flag = p[offset + 8].toInt() and 0xFF
            val nameLen = p[offset + 9].toInt() and 0xFF
            val nameStart = offset + 10
            val nameEnd = nameStart + nameLen
            if (nameEnd > p.size) break
            val name = String(p, nameStart, nameLen, Charsets.UTF_8).trimEnd('\u0000')
            result.add(ConnectedDeviceInfo(mac, name, connectState, flag))
            val extraSkip = (entryLen - nameLen - 3).coerceAtLeast(0)
            offset = nameEnd + extraSkip
        }
        return result
    }

    /**
     * Запрос статистики общего времени использования наушников (Opcode 0x0128 / 296).
     * Payload: пустой.
     */
    fun buildUseTimeRequestFrame(address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray =
        Frame(CMD_USE_TIME_REQ, address, ByteArray(0)).toByteArray()

    /**
     * Парсинг ответа времени использования наушников (Opcode 0x8128 / 33064).
     * Формат по CommandUtil.o(1, bArrE) и UseTimeInfo(offset, bArr):
     *   [0] status (0x00 = Success)
     *   [1] count (N)
     *   Далее N записей по 5 байт:
     *     [0] roleByte (1 = Left, 2 = Right, 3 = Case)
     *     [1..4] totalMinutes (uint32 little-endian)
     */
    fun parseUseTimePayload(p: ByteArray): Map<ComponentRole, Int>? {
        if (p.size < 2 || p[0].toInt() != 0) return null
        val count = p[1].toInt() and 0xFF
        if (count == 0 || p.size < 2 + count * 5) return null

        val result = mutableMapOf<ComponentRole, Int>()
        var offset = 2
        for (i in 0 until count) {
            val roleId = p[offset].toInt() and 0xFF
            val minutes = (p[offset + 1].toInt() and 0xFF) or
                ((p[offset + 2].toInt() and 0xFF) shl 8) or
                ((p[offset + 3].toInt() and 0xFF) shl 16) or
                ((p[offset + 4].toInt() and 0xFF) shl 24)
            val role = ComponentRole.fromId(roleId)
            if (role != null) {
                result[role] = minutes
            }
            offset += 5
        }
        return result
    }
}
