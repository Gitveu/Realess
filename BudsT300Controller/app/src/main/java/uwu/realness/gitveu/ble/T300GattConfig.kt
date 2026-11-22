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
    // Основной вендорный UUID realme / OPPO / OnePlus (из класса BaseBRDevice.L)
    val REALME_VENDOR_SPP_UUID: UUID = UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5")

    // Стандартный SPP UUID (Serial Port Profile, BaseBRDevice.K)
    val STANDARD_SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Дополнительные вендорные UUID (BaseBRDevice.I / J)
    val ALT_VENDOR_SPP_UUID_1: UUID = UUID.fromString("db764ac8-4b08-7f25-aafe-59d03c27bae4")
    val ALT_VENDOR_SPP_UUID_2: UUID = UUID.fromString("db764ac8-4b08-7f25-aafe-59d03c27bae3")

    // Huawei / Honor приватный SPP UUID (RFCOMM channel 16 на FreeBuds 4i / 5i / SE / FreeLace Pro)
    val HUAWEI_VENDOR_SPP_UUID: UUID = UUID.fromString("96cc203e-5068-46ad-b32d-e316f5e069ba")

    val KNOWN_SPP_UUIDS = listOf(
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
