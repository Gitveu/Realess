package uwu.realness.gitveu.ble

import java.util.UUID

/**
 * Конфигурация Bluetooth UUID (SPP RFCOMM и BLE GATT) для Realme Buds T300.
 *
 * Наушники сопрягаются по Bluetooth Classic (A2DP/HFP), и realme Link общается с ними
 * преимущественно через RFCOMM SPP сокет. В случае необходимости поддерживается fallback на GATT.
 */
object T300GattConfig {

    /**
     * RFCOMM SPP UUIDs (Bluetooth Classic)
     */
    // Основной вендорный UUID realme (из класса BaseBRDevice.L)
    val REALME_VENDOR_SPP_UUID: UUID = UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5")

    // Вендорный UUID OnePlus Buds / OPPO Enco (из QuickBuds)
    val OPPO_ONEPLUS_SPP_UUID: UUID = UUID.fromString("00001107-D102-11E1-9B23-00025B00A5A5")

    // Стандартный SPP UUID (Serial Port Profile, BaseBRDevice.K)
    val STANDARD_SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Дополнительные вендорные UUID (BaseBRDevice.I / J)
    val ALT_VENDOR_SPP_UUID_1: UUID = UUID.fromString("db764ac8-4b08-7f25-aafe-59d03c27bae4")
    val ALT_VENDOR_SPP_UUID_2: UUID = UUID.fromString("db764ac8-4b08-7f25-aafe-59d03c27bae3")

    // Huawei / Honor приватный SPP UUID (RFCOMM channel 16 на FreeBuds 4i / 5i / SE / FreeLace Pro)
    val HUAWEI_VENDOR_SPP_UUID: UUID = UUID.fromString("96cc203e-5068-46ad-b32d-e316f5e069ba")

    // Nothing / CMF RFCOMM SPP UUID (swift-nothing-ear / kde-headphones)
    val NOTHING_SPP_UUID: UUID = UUID.fromString("AEAC4A03-DFF5-498F-843A-34487CF133EB")

    // Samsung Galaxy Buds RFCOMM SPP UUIDs (kde-headphones / GalaxyBudsClient)
    val GALAXY_SPP_UUID: UUID = UUID.fromString("2E73A4AD-332D-41FC-90E2-16BEF06523F2")
    val GALAXY_LEGACY_SPP_UUID: UUID = UUID.fromString("00001102-0000-1000-8000-00805F9B34FD")

    // Sony MDR RFCOMM SPP UUIDs (V2 / V1 — SonyHeadphonesClient / kde-headphones)
    val SONY_V2_SPP_UUID: UUID = UUID.fromString("956C7B26-D49A-4BA8-B03F-B17D393CB6E2")
    val SONY_V1_SPP_UUID: UUID = UUID.fromString("96CC203E-5068-46AD-B32D-E316F5E069BA")

    // Xiaomi / Redmi Buds RFCOMM SPP UUID (kde-headphones)
    val XIAOMI_SPP_UUID: UUID = UUID.fromString("0000FD2D-0000-1000-8000-00805F9B34FB")

    // Sennheiser GAIA RFCOMM SPP UUID (kde-headphones)
    val SENNHEISER_SPP_UUID: UUID = UUID.fromString("A2129FF3-081B-4C45-8AFE-469D9C4842EC")

    // Google Pixel Buds Maestro RFCOMM SPP UUID (kde-headphones)
    val PIXEL_BUDS_SPP_UUID: UUID = UUID.fromString("25E97FF7-24CE-4C4C-8951-F764A708F7B5")

    // JBL BLE GATT Service UUID (kde-headphones)
    val JBL_GATT_SERVICE: UUID = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0000")

    // Standard BLE Battery Service (used by Logitech G435 in Bluetooth mode)
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
    val BATTERY_LEVEL_CHAR: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")

    val KNOWN_SPP_UUIDS = listOf(
        REALME_VENDOR_SPP_UUID,
        OPPO_ONEPLUS_SPP_UUID,
        STANDARD_SPP_UUID,
        ALT_VENDOR_SPP_UUID_1,
        ALT_VENDOR_SPP_UUID_2
    )

    val OPPO_ONEPLUS_SPP_UUIDS = listOf(
        OPPO_ONEPLUS_SPP_UUID,
        REALME_VENDOR_SPP_UUID,
        STANDARD_SPP_UUID,
        ALT_VENDOR_SPP_UUID_1,
        ALT_VENDOR_SPP_UUID_2
    )

    val HUAWEI_SPP_UUIDS_PORT1_FIRST = listOf(
        STANDARD_SPP_UUID,
        HUAWEI_VENDOR_SPP_UUID
    )

    val HUAWEI_SPP_UUIDS_PORT16_FIRST = listOf(
        HUAWEI_VENDOR_SPP_UUID,
        STANDARD_SPP_UUID
    )

    val NOTHING_SPP_UUIDS = listOf(
        NOTHING_SPP_UUID,
        STANDARD_SPP_UUID
    )

    val GALAXY_MODERN_SPP_UUIDS = listOf(
        GALAXY_SPP_UUID,
        STANDARD_SPP_UUID
    )

    val GALAXY_LEGACY_SPP_UUIDS = listOf(
        GALAXY_LEGACY_SPP_UUID,
        STANDARD_SPP_UUID
    )

    val GALAXY_PLUS_SPP_UUIDS = listOf(
        STANDARD_SPP_UUID,
        GALAXY_SPP_UUID
    )

    val SONY_SPP_UUIDS = listOf(
        SONY_V2_SPP_UUID,
        SONY_V1_SPP_UUID,
        STANDARD_SPP_UUID
    )

    val XIAOMI_SPP_UUIDS = listOf(
        XIAOMI_SPP_UUID,
        STANDARD_SPP_UUID
    )

    val SENNHEISER_SPP_UUIDS = listOf(
        SENNHEISER_SPP_UUID,
        STANDARD_SPP_UUID
    )

    val PIXEL_BUDS_SPP_UUIDS = listOf(
        PIXEL_BUDS_SPP_UUID,
        STANDARD_SPP_UUID
    )

    val STANDARD_SPP_ONLY_UUIDS = listOf(
        STANDARD_SPP_UUID
    )

    /**
     * BLE GATT UUIDs
     */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val NORDIC_UART_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50424dcca9e")
    val NORDIC_UART_RX_WRITE: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50424dcca9e")
    val NORDIC_UART_TX_NOTIFY: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50424dcca9e")

    val AIROHA_SERVICE: UUID = UUID.fromString("5052494D-2DAB-0341-6972-6F6861424C45")
    val AIROHA_WRITE: UUID = UUID.fromString("43484152-2DAB-3241-6972-6F6861424C45")
    val AIROHA_NOTIFY: UUID = UUID.fromString("43484152-2DAB-3141-6972-6F6861424C45")

    val KNOWN_CANDIDATE_SERVICES = listOf(
        NORDIC_UART_SERVICE,
        AIROHA_SERVICE
    )

    const val AUTO_DETECT_FALLBACK = true
}
