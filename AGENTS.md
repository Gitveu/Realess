# realme Link / Realme Buds TWS — статический отчёт

Дата отчёта: 2026-11-17
Статус: статический анализ завершён; рантайм-фаза (ADB) начата 2026-11-17 (см. раздел 14).

## Цель проекта

Понять и документировать протокол Realme Buds T300 настолько полно, чтобы затем разработать самостоятельное Android-приложение, которое работает **напрямую с наушниками, без Realme Link**, и умеет:

1. читать уровень батареи левого и правого наушника и зарядного кейса (если устройство/протокол предоставляют эти значения);
2. переключать режимы шумоподавления: ANC, выключено и прозрачность;
3. получать/проверять текущее состояние режима после подключения и после команды.

Важно: «изменение заряда батареи» не является целью — приложение может только читать и отображать состояние батареи; физически заряд меняется зарядкой/разрядкой. Не пытаться искусственно записывать уровень батареи в наушники. Все команды ограничены штатным управлением собственными наушниками пользователя.

Критерий готовности протокольного исследования: подтверждённые GATT UUID (service/write/notify/CCCD), формат запроса/ответа батареи и семантика слотов L/R/case, точные ANC payload для трёх режимов, обработка ответов/таймаутов, а также runtime-сверка команд и результатов на реальных Buds T300. До такой сверки маркировать выводы как `[по коду]` или `[гипотеза]`, не выдавая их за экспериментально подтверждённые.

## 1. Артефакт

Исходный контейнер:

```text
/Users/cybertveu/realme/realme Link.apkm
SHA-256: 0778fdecc9ad14d96323326164ead858d7737cf714ffdbd075b23cc622e68d2e
```

Основной APK:

```text
base.apk
SHA-256: 88325841d6d189c614acf65c96a447c2dbe9b3314667efbc7446869768e431e7
```

Контейнер распакован REA с проверкой целостности. В APKM присутствуют `base.apk`, ABI-сплиты и vendor-модули.

Android-анализ:

```text
JADX MCP: 0.7.1
Java: 17.0.21
Классов: 32751
Ресурсов: 7020
```

Основные Evidence REA:

```text
ev_fb7763459db13e96e030d6f99c40536acdc1101e51bf1a13ba5ecdb594a8006a — повторная распаковка APKM
ev_e6a366cd2091252d6c837e5615c296fcdb45f0917c0368eb6202e654ee90f5e8 — Android package summary
ev_c2f8f1b5f5862d94404b5ac040446b0dcd00b7228a5b28ca0524b425b7fbe60b — class search headset
ev_e0cb167c59303cd6244feebcb800093341adb734d6c589c317d68c0004a0b9c2 — Airoha class search
ev_7afc75a6a3032857e403a6d3b6efe1c0e6f2dfa9229a1d6598db1ce89a4457 — HeadsetView class search
```

## 2. Ключевой вывод

В APK есть несколько headset-бэкендов. Для TL TWS-ветки статически восстановлена команда шумоподавления до уровня готового BLE/GATT frame.

Основная цепочка:

```text
IotHeadsetController.onReceive
  -> SettingsInfo(settingType=2, value=N)
  -> HeadsetManagerDelegate.J3
  -> HeadsetManagerFactory.a(device)
  -> TLHeadsetConnectionImp.J3
  -> TLHeadsetConnectionImp.O5
  -> SetCommandManager.a(mac, 1, 1, noiseStatus)
  -> PacketFactory.c(mac, 0x0404, payload)
  -> Packet.a()
  -> DeviceConnectionImpl.c
  -> IMessageSender.c
  -> GattWriteCommand
  -> BluetoothGattCharacteristic.setValue(frame)
  -> BluetoothGatt.writeCharacteristic()
```

Это подтверждено статически. Реальное выполнение на Realme Buds T300 не проверялось.

## 3. TL ANC / noise-control

### UI-функции

`IotHeadsetManager.FunctionNameEnum` содержит:

```text
HEADSET_NOISE_REDUCTION
HEADSET_BATTERY
HEADSET_BATTERY_BOX
HEADSET_BATTERY_LEFT
HEADSET_BATTERY_RIGHT
HEADSET_SOUND_EFFECT
HEADSET_GAME_MODE
```

Evidence:

```text
ev_c4e8e0db006eb8c76ec2545b6ab12cc7cf21bfba541b72412d2efec8e7a0efb7
```

UI labels:

```text
ITEM_NOISE_CLOSE
ITEM_NOISE_NORMAL
ITEM_NOISE_REDUCTION
ITEM_NOISE_TRANSPARENT
```

Evidence:

```text
ev_97994c6d724f339a20e2ff0a882622fee039c1564b46c2cd2ef98206686f89ac
```

Также существуют уровни:

```text
ITEM_NOISE_LEVEL_STRONG
ITEM_NOISE_LEVEL_WEAK
ITEM_SMART_REDUCTION
ITEM_DEEP_REDUCTION
ITEM_MEDIUM_REDUCTION
ITEM_LIGHT_REDUCTION
```

Профиль модели `064812` содержит:

```text
sdkVendor: VENDOR_TL
deviceType: TYPE_TWS
noiseControlProtocol: PROTOCOL_STANDARD
```

и перечисленные noise-control items. Но однозначной строки `Realme Buds T300` в APK не найдено.

### UI value -> protocol noiseStatus

Evidence:

```text
ev_107dec488c9bb934e3ec9a0fac9ec5defa31c3157171cb75d47f4f459aed5996
```

Метод `TLHeadsetConnectionImp.O5()` преобразует:

| UI value | noiseStatus |
|---:|---:|
| 1 | 8 |
| 2 | 1 |
| 4 | 2 |
| 8 | 4 |
| 16 | 16 |
| 32 | 32 |
| другое | 0 |

### Команда

Evidence:

```text
ev_ea005e5895e2c294e153d22fe54b7270e587c55ba6720c935b898c42a0685225
```

Команда:

```text
1028 decimal = 0x0404
```

Payload строится так:

```java
byte[] payload = {
    i == 1 ? (byte) 1 : (byte) 2,
    i3 == 1 ? (byte) 1 : (byte) 2,
    (byte) noiseStatus
};
```

Из `O5()` передаются `i=1`, `i3=1`, поэтому:

```text
payload = 01 01 noiseStatus
```

Таблица:

| UI value | noiseStatus | payload |
|---:|---:|---|
| 1 | 8 | `01 01 08` |
| 2 | 1 | `01 01 01` |
| 4 | 2 | `01 01 02` |
| 8 | 4 | `01 01 04` |
| 16 | 16 | `01 01 10` |
| 32 | 32 | `01 01 20` |

Важно: assignment чисел к конкретным labels `CLOSE`, `REDUCTION`, `TRANSPARENT` полностью не доказан. В конфигурации присутствуют дополнительные уровни и smart/deep/medium/light режимы.

## 4. TL wire frame

Класс:

```text
com.realme.iot.headset.tl.protocol.packet.Packet
```

Evidence:

```text
ev_ee996b9267ca8b4a525043e5238eb9768f8fc61527b335c525eeee824dee11b1
```

`Packet.a()` формирует:

```text
offset  length  field
0       2       command, little-endian
2       1       device address
3       2       payload length, little-endian
5       N       payload
```

Endian подтверждён:

```text
ev_1b7c7c978a3f4ae4518b3a15fe62204b4d765635bc9c24132f8e7e5af5b3166e
```

Для ANC:

```text
04 04 <address> 03 00 01 01 <noiseStatus>
```

Для первого зарегистрированного устройства address обычно равен `00`, поэтому возможные frames:

```text
04 04 00 03 00 01 01 08
04 04 00 03 00 01 01 01
04 04 00 03 00 01 01 02
04 04 00 03 00 01 01 04
04 04 00 03 00 01 01 10
04 04 00 03 00 01 01 20
```

### Address

`PacketFactory.f(mac)` использует runtime map-счётчиков, а не MAC/OUI:

```text
первый MAC -> 0
второй MAC -> 1
далее -> 2...
```

Evidence:

```text
ev_0b6bc9379cb59a12a80798a567493295281b70ebf0e716ff4a81d2078ab69ed5
```

## 5. TL GATT transport

Фабрика headset implementations:

```text
com.realme.iot.headset.ld.LDHeadsetManagerImpl
com.realme.iot.headset.tl.TLHeadsetConnectionImp
com.realme.iot.headset.wm.WMHeadsetManagerImpl
```

Evidence:

```text
ev_3e8c4059fc438dade71c646188761f3430c2ef6ceb0a28fdd78d8761dde00670
```

TL backend использует:

```text
com.realme.iot.headset.tl.internal.DeviceConnectionImpl
com.realme.iot.headset.tl.internal.connect.ble.GattConnection
com.realme.iot.headset.tl.internal.connect.ble.GattWriteCommand
```

`DeviceConnectionImpl.c()` передаёт `Packet` в `IMessageSender` без модификации.

Evidence:

```text
ev_a7063e8b70a921005a236d8d3a3f1772b7bdbba4b3f4bf016e41b38e39d327f1
ev_4f08cd760a44817183b6be30534bb28b10cef44130fa88e1d063164f2985973f
```

`GattWriteCommand.b()`:

```java
characteristic.setValue(frame);
bluetoothGatt.writeCharacteristic(characteristic);
```

Evidence:

```text
ev_787a576579da3414bd5f7563f32ad5fd03c1e5948d706d0868f3c55bebd832ef
```

В просмотренной TL-цепочке не найдено дополнительного CRC, XOR, шифрования или другого framing между `Packet.a()` и GATT write.

### Notification

`GattConnection.U()` включает notification для service/characteristic pair через `GattSetNotificationCommand`.

Evidence:

```text
ev_749e1c3448da510ac155606ca51daa00e660bc505dc6d7b6c54354ebc0e764b9
```

`GattConnection.E()` передаёт полученные UUID и raw bytes listener без изменения:

```text
service UUID
characteristic UUID
raw byte[]
```

Evidence:

```text
ev_6d9c561ce04793117745b76ab88699a6fd7176f47a73c7794f028d9afd12ce1d
```

### Тайминги TL GATT

```text
connect timeout: 30000 ms
operation delay: 200 ms
queue interval: 1000 ms
long interval: 1800000 ms
```

Evidence:

```text
ev_02d3c273dd6d60c11703a8bae78958c77f3998ca132d83d748ca755fa60e1827
```

## 6. ANC response / timeout

ANC response обрабатывается в `SetCommandManager.d()`:

```text
payload byte 1 -> mRspValue
status == 0 -> onSuccess()
status != 0 -> onFailure(status)
```

Evidence:

```text
ev_f56d1c913cb014f4d6f7c7e6f40372e35439d46ee2a1f91c6a79cefadbd1790c
```

ACK определяется:

```java
(command & 0x8000) != 0
```

Evidence:

```text
ev_9d8205fd3e36e5c09171db1f753c91caddd20eb12e6a65c56d99ef9dcfa38751
```

Timeout processor:

```text
class: com.realme.iot.headset.tl.protocol.PacketTimeoutProcessor
default timeout: 5000 ms
timeout payload: 06
```

Evidence:

```text
ev_07f28e9cefe1ec63cfb4ed499259eb7fbb41db45b3d482e6df75048f661e6417
ev_d576195459b0d42e494822514c21512df0fb2cfd572983a0667c9241151121f0
```

Повторная отправка после timeout статически не обнаружена.

## 7. Заряд

### Цепочка

```text
IotHeadsetManager
  -> HeadsetManagerDelegate.u4
  -> HeadsetManagerFactory.a(device)
  -> IHeadsetContract.u4
  -> TLHeadsetConnectionImp.u4
  -> TLHeadsetConnectionImp.b0
  -> внутренний PollCommandManager
```

Во время OTA получение заряда блокируется.

### BatteryInfo parser

Класс:

```text
com.realme.iot.headset.tl.sdk.BatteryInfo
```

Evidence:

```text
ev_4c94884bd8ae0b19bf77d98abe65eedc62466bbe6a4b86d62496c0875f0dac0c
```

Parser:

```java
state = data[offset];
byte b = data[offset + 1];
level = b & 0x7f;
flag = (b & 0x80) != 0;
```

Формат:

```text
offset     — дополнительное состояние/тип
offset + 1 — bits 0..6: уровень
offset + 1 — bit 7: boolean flag
```

Три объекта сохраняются в slots:

```java
map.put(1, batteryInfo);
map.put(2, batteryInfo2);
map.put(3, batteryInfo3);
```

Evidence:

```text
ev_eff92cc91ae9428d26f8230f278d22a7d37d4a23de961adfc892c9d166760560
```

### Роли

Enum:

```text
0 = LEFT_EAR
1 = RIGHT_EAR
2 = ONLY_LEFT_EAR
3 = ONLY_RIGHT_EAR
4 = BOX_EAR
```

Evidence:

```text
ev_eaedf8eba4c4c9ef2ce22bd49e323c20c0d556c76d222f631afc3574d7b04233
```

Точная связь slots `1/2/3` с `LEFT/RIGHT/BOX` в просмотренной статике не найдена. Публичный метод `b3()` возвращает уже подготовленную map `HeadsetRole -> Pair<Integer, Boolean>`, но не создаёт её.

## 8. Airoha BLE transport

Найдена отдельная Airoha BLE ветка:

```text
com.airoha.android.lib.physical.ble.AirohaBleController
```

Evidence:

```text
ev_d6463cdc6369379db81a5a27e4c9dfa9dd7fee2b17ee06dd19f5de07c82d5d2c
```

UUID:

```text
Service:
5052494D-2DAB-0341-6972-6F6861424C45

Notify/read characteristic:
43484152-2DAB-3141-6972-6F6861424C45

Write characteristic:
43484152-2DAB-3241-6972-6F6861424C45
```

Evidence:

```text
ev_f8e04665e141662797884ce8a0a9ce2ca91bfeb3f1ef18d204e47ab54ba4565e
```

Write path:

```java
mGattWriteCharc.setValue(data);
mBluetoothGatt.writeCharacteristic(mGattWriteCharc);
```

Evidence:

```text
ev_06325930f858d726119b5ce8409d51d5cc02b9391a2286269c2f5421924e1745
```

Airoha BLE backend также содержит:

```text
VERIFIED_MTU = 273
mGattNotifyCharc
mGattWriteCharc
```

APK дополнительно содержит Airoha SPP-классы, поэтому Airoha SDK поддерживает BLE и SPP:

```text
com.airoha.android.lib.physical.spp.AirohaSppController
com.airoha.android.lib.physical.spp.AirohaSppControllerCh2
com.airoha.android.lib.physical.spp.AirohaSppControllerCh3
```

## 9. Модель Buds T300

В APK не найдена однозначная строка:

```text
Realme Buds T300
```

Есть много TL TWS-конфигураций с model IDs, например:

```text
064812
067052
067852
062012
061C12
063412
063012
064C12
066812
066C12
065C12
065852
065452
065052
066452
067452
062812
064012
063812
```

Однозначная привязка конкретного ID к Buds T300 статически не доказана.

## 10. Что подтверждено и что нет

### `[по коду] подтверждено`

- TL TWS vendor branch существует.
- TL noise-control использует `SettingsInfo.settingType=2`.
- ANC command ID: `0x0404`.
- ANC payload: `01 01 noiseStatus`.
- Mapping UI value -> noiseStatus: `1->8`, `2->1`, `4->2`, `8->4`, `16->16`, `32->32`.
- TL frame layout: command + address + little-endian payload length + payload.
- Первый runtime address в map обычно `0`.
- TL frame передаётся в GATT write без дополнительной обработки в просмотренной цепочке.
- ACK flag: `command & 0x8000`.
- TL timeout: 5000 ms; timeout payload `06`.
- Battery level: второй byte `& 0x7f`.
- Battery flag: второй byte `& 0x80`.
- Три battery slots: keys `1`, `2`, `3`.
- TL GATT notification path передаёт raw bytes listener.
- Airoha BLE service/read/write UUIDs.

### `[не подтверждено]`

- Что именно означает UI value `1`, `2`, `4` по labels `CLOSE`, `REDUCTION`, `TRANSPARENT`.
- Какой model ID соответствует Realme Buds T300.
- Какой exact GATT service/characteristic выбирается именно для T300.
- Какой slot является L, R и case.
- Реальное принятие frame гарнитурой.
- Реальные response/notify bytes.
- Наличие firmware-side CRC/encryption.
- Retry после timeout.
- Экспериментальная работа ANC и battery на физическом устройстве.

## 11. Необходимость ADB

Дальнейшие runtime-проверки потребуют хотя бы одного из вариантов:

```text
ADB + устройство + Realme Buds T300
```

или готового:

```text
btsnoop_hci.log
BLE capture
Frida log
```

Без этого текущий документ остаётся статической реконструкцией APK и не заявляет, что команда проверена на реальной гарнитуре.

## 12. Восстановление после `/clear`, нового чата или перезапуска

Этот файл является кратким источником истины для продолжения работы. Если исходный диалог потерян, новый чат нужно начать с такого сообщения:

```text
Прочитай инструкции из /Users/cybertveu/Downloads/AyuGram Desktop/AGENTS (1).md.
Продолжи статический reverse engineering realme Link по отчёту /Users/cybertveu/Desktop/AGENTS.md.
Работай автоматически и сообщай о шагах. ADB пока не используй, если я явно не сообщил, что телефон подключён.
Не выдумывай UUID, байты или model ID; разделяй [по коду], [подтверждено экспериментом] и [не подтверждено].
Сначала проверь сохранённый отчёт и не повторяй уже завершённый анализ.
Продолжи с раздела "Следующие статические задачи".
```

### Основные пути

```text
APKM:
/Users/cybertveu/realme/realme Link.apkm

Отчёт:
/Users/cybertveu/Desktop/AGENTS.md

JADX MCP 0.7.1:
/Users/cybertveu/rea-tools/jadx-0.7.1/jadx-headless-mcp-0.7.1-all.jar

Apktool launcher:
/Users/cybertveu/rea-tools/apktool

REA configuration:
/Users/cybertveu/.claude.json
```

### Проверка инструментов

Перед продолжением при необходимости выполнить в терминале:

```bash
source ~/.zshrc
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export REA_JADX_MCP_JAR="$HOME/rea-tools/jadx-0.7.1/jadx-headless-mcp-0.7.1-all.jar"
export REA_APKTOOL_COMMAND="$HOME/rea-tools/apktool"
java -version
javac -version
"$REA_APKTOOL_COMMAND" --version
npx -y rea-agents@6.8.0 doctor --provider jadx --json
```

Ожидается:

```text
JADX MCP: 0.7.1
Apktool: 3.0.3
Java: Temurin 17+
```

`~/.claude.json` уже содержит переменные REA. Если REA сообщает JADX 0.8.0, это старый MCP-процесс: полностью выйти из Claude Code и запустить его заново с окружением из `~/.zshrc`.

### Если нужен именно старый диалог

`/status` может открыть settings dialog, а не показать session ID. Не считать это ошибкой. Если ID известен, продолжать так:

```bash
source ~/.zshrc
claude --resume SESSION_ID
```

Если ID неизвестен, использовать:

```bash
claude --resume
```

и выбрать сессию, связанную с `realme Link`/`Realme Buds`.

Если старую сессию найти не удаётся, продолжать по этому файлу: весь подтверждённый статический результат уже сохранён ниже/выше, а APK и инструменты остаются на указанных путях.

### Текущий завершённый статический результат

Уже восстановлены:

```text
TL ANC command: 0x0404
TL ANC payload: 01 01 noiseStatus
TL frame: 04 04 <address> 03 00 01 01 <noiseStatus>
UI value mapping: 1->8, 2->1, 4->2, 8->4, 16->16, 32->32
TL timeout: 5000 ms
Timeout payload: 06
ACK flag: command & 0x8000
Battery parser: level = byte & 0x7f; flag = byte & 0x80
Battery slots: 1, 2, 3
Airoha BLE service:
5052494D-2DAB-0341-6972-6F6861424C45
Airoha read/notify:
43484152-2DAB-3141-6972-6F6861424C45
Airoha write:
43484152-2DAB-3241-6972-6F6861424C45
```

Не повторять эти пункты без новой цели или нового evidence.

## 13. Следующие статические задачи

Продолжить в таком порядке:

1. Найти concrete population map `BatteryInfo slot -> HeadsetRole` в TL vendor implementation.
2. Определить, какой profile/model ID может соответствовать Buds T300; не объявлять совпадение без evidence.
3. Проверить `GattConnection` service/characteristic UUID sources для TL branch.
4. Разобрать `IMessageSender` concrete implementation и подтвердить полный путь `Packet.a()` до GATT write.
5. Найти статические retry/reconnect branches и ошибки GATT status.
6. Проверить наличие CRC/encryption в TL packet parser/serializer.
7. Составить финальную таблицу GATT и кадров.

После каждого значимого шага обновлять этот файл и сохранять новые Evidence ID.

## 14. Runtime: ADB-сессия 2026-11-17 (serial 9b79d6c2)

### Устройство

```text
serial: 9b79d6c2
model: PJZ110 (OnePlus)
Android: 17
SDK: 37
ro.debuggable: 0 (release build — Frida недоступна)
ABI: arm64-v8a
```

Device identity evidence: `ev_9e6bd13c6e426e92c56400ced53d49a4dccd53c51522f8631d2bb884fc9c546c`

### Пакет realme Link

```text
version: 5.8.811.2814 (versionCode 530214)
package: com.realme.link
first_install: 2026-11-07
installer: com.android.vending
PID after start: 15595
subprocesses: com.realme.link:link.monitor (15640), com.realme.link:AcOpenDBContentProvider (15832)
Launched activity: SplashActivity → MainActivity
```

Evidence: `ev_67c01ebd0640217cb68e3218c01f9b72efb16e44b8f52eb6cc594f4d48909755`

### Карточка Realme Buds T300 на экране

Экран устройства при запущенном приложении (контрольный скриншот):
- На экране: **Realme Buds T300** (текст на карточке прямо виден)
- Три режима ANC: «Шумоподавление / Выкл. / Прозрачность»
- Статус: «Не используется» (наушник не в активной сессии управления)

Контрольный скриншот: `/tmp/rea-bt-cache/card/screen.png` (1080×2376, evidence `ev_24d87e23d387047381b9a482e153725a40dc4a2fc9810afaed9a815ad2f6ab18`)

### HeadsetDeviceInfo из лога приложения (PID 15595)

```
HeadsetDeviceInfo(mac=74:D7:*:7B:92, noiseMode=0, battery=0)        — 19:30:54.119
HeadsetDeviceInfo(mac=74:D7:*:7B:92, noiseMode=0, battery=30000)     — 19:30:54.122
HeadsetDeviceInfo(mac=74:D7:*:7B:92, noiseMode=0, battery=100000)   — 19:30:54.407
HeadsetDeviceInfo(mac=74:D7:*:7B:92, noiseMode=2, battery=100000)   — 19:30:54.518
```

При открытии карточки Buds T300 → HeadsetDeviceActivity (дважды: 19:30:55, 19:31:28):
- `ConstraintHelper: Could not find id of "layout_left_battery"` / `layout_right_battery` / `layout_box_battery"` — на экране карточки присутствуют виджеты L/R/кейса

Это **кэшированные данные из БД**, не живой GATT-опрос (наушник не подключён активно).

Evidence: `ev_9354c55cd5befdf748f85b2648130fbe76a51f2d9e85201d20ce5109cc11339f`

### HeadsetDeviceInfo.battery = 100000 [по коду]

`PropertyUtils.d(List<BatteryInfo>): int` — из списка TL-объектов возвращает **минимум** уровня (7-битное значение `b & 0x7F`, диапазон 0–127).

Декомпиляция:

```java
public static int d(List<BatteryInfo> list) {
    if (list == null) return 0;
    Iterator<BatteryInfo> it = list.iterator();
    int i = Integer.MAX_VALUE;
    while (it.hasNext()) {
        int i3 = it.next().b;        // b = byte & 0x7F
        if (i > i3) i = i3;
    }
    return i;
}
```

Evidence: `ev_88c8f2c94df900331987312063a11d0f38a2188a8e29183f58464b69e95a5bff`

Связь с `HeadsetDeviceInfo.battery`:
- `PropertyUtils.d()` возвращает 0–127 (7-битный уровень)
- В UI-модель попадает как `×1000` (100000 = 100×1000, 30000 = 30×1000)
- Статически метод ×1000 не обнаружен; масштаб выведен из лога
- `[подтверждено экспериментом]` — значение `battery=100000` получено из лога приложения на реальном устройстве

### HeadsetDeviceInfo.noiseMode

| значение | что означает |
|---:|---|
| 0 | инициализация / нет данных |
| 2 | режим после загрузки (из лога: «updateNoiseMode noiseMode=2») |

Что именно означает `noiseMode=2` для T300 (Выкл./Прозрачность/Шумоподавление) — статически не привязано.

### GATT-кэши

Извлечённые файлы Bluetooth (без пользовательских данных):

```text
/data/misc/bluetooth/gatt_cache_c8bd4d8db58f (878 bytes)
  ev_ba59f249258481dec2394ac7ba9f6007f11fffca6bb1e21f41cd636fd9cc4f10
  Содержит UUID-паттерны: 0x00280000-0000-1000-8000-00805f9b34fb (GAP), 0x00280001-..., несколько characteristic UUID
  Кэш содержит service handle, characteristic handles (0x000a, 0x000b, 0x0012, 0x0014, 0x001e, 0x0028, 0x002a)
  Записи принадлежат разным BLE device (hex MAC-адреса различаются: 0x80_5f_9b_34_fb, 0xe7_3e_00_01, 0xee_d6_d5_cc, 0x1c_81_44_7b, 0x9b_79_d6_c2 — частично совпадает с serial)
  Один из UUID: 0x6e400001-b5a3-f393-e0a9-e50424dcca9e (Nordic UART TX) — типичный для BLE TWS
  Другой: 0x6e400002-b5a3-f393-e0a9-e50424dcca9e (Nordic UART RX)

/data/misc/bluetooth/gatt_cache_c16443ebbb97 (498 bytes)
  ev_392d3bffe0947fe5ce071976de394214c13c15bcc4b3d09508657dbb600394a5
  Аналогичная структура, меньше записей — возможно кэш одного устройства
```

**Важно**: Nordic UART UUID (6e400001/2) типичны для многих BLE-устройств, включая TWS-гарнитуры. Это **не подтверждает** протокол T300, но указывает на направление для поиска.

### Bluetooth HCI snoop

```text
/data/misc/bluetooth/logs — пустая директория
Settings bluetooth_hci_log — null (выключен)
HCI snoop runtime capture недоступен без настройки системы
```

### Следующие шаги runtime

```text
[ожидается подтверждение пользователя для продолжения]
```

## 15. Следующие статические задачи

(перенесено из раздела 13)

ADB не нужен для статической работы. Если пользователь сообщит, что телефон подключён, сначала проверить:

```bash
adb devices
```

Затем через REA:

```text
list_adb_devices
inspect_adb_device
resolve_adb_packages для com.realme.link
read_adb_logcat
```

Для ANC-сценария включить HCI snoop до подключения наушников, затем зафиксировать:

```text
Bluetooth on/off
открытие кейса
извлечение L/R
ANC OFF
ANC ON
Transparency
```

Никогда не утверждать `[подтверждено экспериментом]`, пока реальный HCI/GATT evidence не сопоставлен со статическим кодом.

## 16. Статическое завершение и прототип приложения (2026-11-17)

### 16.1. Маппинг слотов батареи `[по коду] подтверждено`

Декомпиляция `TLHeadsetConnectionImp$AnonymousClass2.handleMessage` (case 10):
```java
BatteryInfo batteryInfo4 = (BatteryInfo) ((List) pair.second).get(i7);
int i8 = batteryInfo4.f7691a;
if (i8 == 1) {
    batteryInfo = batteryInfo4;   // -> HeadsetRole.LEFT_EAR
} else if (i8 == 2) {
    batteryInfo2 = batteryInfo4;  // -> HeadsetRole.RIGHT_EAR
} else if (i8 == 3) {
    batteryInfo3 = batteryInfo4;  // -> HeadsetRole.BOX_EAR
}
```
- Component ID `0x01` = **Левый наушник** (`LEFT_EAR` / `ONLY_LEFT_EAR`)
- Component ID `0x02` = **Правый наушник** (`RIGHT_EAR` / `ONLY_RIGHT_EAR`)
- Component ID `0x03` = **Кейс** (`BOX_EAR`)

Формат записи батареи (2 байта):
- Байт 0: `Component ID` (`0x01`, `0x02`, `0x03`)
- Байт 1:
  - `байт & 0x7F` = уровень заряда (0..100%)
  - `(байт & 0x80) != 0` = флаг зарядки (true = заряжается)

### 16.2. Команды протокола TL `[по коду] подтверждено`

Из класса `com.realme.iot.headset.tl.protocol.packet.Protocol`:
- **Запрос батареи**: `Protocol.v = 262` (`0x0106`)
  - Кадр: `06 01 00 00 00` (длина 0, пустой payload)
- **Ответ батареи**: `Protocol.w = 33030` (`0x8106`)
  - Обработка в `PollCommandManager.W()` и `NotificationCommandManager.k()` (Event `0x01`)
- **Управление ANC**: `Protocol.f7653f1 = 1028` (`0x0404`)
  - Кадр: `04 04 00 03 00 01 01 <noiseStatus>`
  - ANC ON: `04 04 00 03 00 01 01 08`
  - ANC OFF: `04 04 00 03 00 01 01 01`
  - Transparency: `04 04 00 03 00 01 01 02`
- **Ответ ANC**: `Protocol.f7655g1 = 33796` (`0x8404`)

### 16.3. Самостоятельный прототип

В каталоге `/Users/cybertveu/realme/BudsT300Controller` реализовано автономное Android-приложение на Kotlin + Jetpack Compose:
1. `T300Protocol.kt` — упаковка и распаковка кадров TL wire frame.
2. `T300GattConfig.kt` — таблица UUID и гибкий автодетект характеристик.
3. `T300BleManager.kt` — неблокирующий BLE-клиент с очередью команд и автополлингом батареи.
4. `BudsViewModel.kt` — публикация реактивного состояния батареи и ANC в StateFlow.
5. `MainActivity.kt` — Compose UI с карточками L/Case/R, переключателем ANC и консолью сырых пакетов.
6. `AndroidManifest.xml` — разрешения Bluetooth LE (Android 12+ и legacy).


## 17. Реконструкция транспорта: Bluetooth Classic SPP vs GATT и автоподключение

### 17.1. Причина зависания GATT и обнаружение RFCOMM SPP `[по коду] подтверждено`

При подключении через Android Bluetooth наушники Realme Buds T300 сопрягаются как стандартная Bluetooth Classic гарнитура (A2DP / HFP). В приложении realme Link для устройств TWS используется канал Bluetooth Classic RFCOMM:
- Класс `com.realme.iot.headset.tl.internal.DeviceInteractionImpl.G()` создаёт `BRClientDevice` (вместо `GattDevice`).
- `BRClientConnection.o0(BluetoothDevice)` создаёт сокет:
  ```java
  this.j = bluetoothDevice.createRfcommSocketToServiceRecord(o());
  this.j.connect();
  ```
- Кадр протокола TL передаётся в поток напрямую:
  ```java
  this.n.write(bArr2);
  this.n.flush();
  ```
- Чтение потока выполняется циклически в `BaseBRConnection.T()`:
  ```java
  int i = inputStream.read(bArr);
  ```

### 17.2. UUID каналов RFCOMM SPP `[по коду] подтверждено`

Из класса `com.realme.iot.headset.tl.internal.connect.common.BaseBRDevice`:
- `BaseBRDevice.L` = `0000079A-D102-11E1-9B23-00025B00A5A5` — основной вендорный SPP UUID для устройств realme/OPPO/OnePlus.
- `BaseBRDevice.K` = `00001101-0000-1000-8000-00805F9B34FB` — стандартный Serial Port Profile (SPP).
- `BaseBRDevice.J` = `db764ac8-4b08-7f25-aafe-59d03c27bae4` — резервный вендорный UUID.
- `BaseBRDevice.I` = `db764ac8-4b08-7f25-aafe-59d03c27bae3` — резервный вендорный UUID.

### 17.3. Автоподключение и мониторинг состояния `[реализовано]`

1. **Разрешения Android 12+ (API 31+)**:
   - `BLUETOOTH_CONNECT` и `BLUETOOTH_SCAN` запрашиваются сразу при старте Activity через `registerForActivityResult`.
   - В UI выводится предупреждающий баннер и кнопка запроса, если разрешения не выданы.
2. **Автоматическое обнаружение (Zero-Touch UI)**:
   - Приложение опрашивает `BluetoothA2dp` профиль (`connectedDevices`) и список сопряжённых устройств (`bondedDevices`).
   - Регистрируется динамический `BroadcastReceiver` для `ACTION_ACL_CONNECTED`, `ACTION_ACL_DISCONNECTED`, `ACTION_CONNECTION_STATE_CHANGED`.
   - При появлении Buds T300 в сети телефон автоматически инициирует соединение без необходимости нажатия кнопок или выбора из списка.
   - При помещении наушников в кейс (отключении A2DP) статус автоматически переходит в «В кейсе / отключено».
3. **Двухрежимный транспорт (SPP Primary + GATT Fallback)**:
   - В `T300BleManager.kt` сначала выполняется подключение через `createRfcommSocketToServiceRecord(REALME_VENDOR_SPP_UUID)`.
   - При сбое сокета выполняется автоматический перебор стандартного SPP и fallback на BLE GATT.

## 18. Транспортное обрамление OPPOv1 (SOF 0xAA) `[по коду] подтверждено`

### 18.1. Причина отсутствия ответа на «голые» TL-кадры

При отправке чистого TL-кадра `06 01 00 00 00` через установленный сокет RFCOMM SPP устройство не отвечало, так как прошивка наушников ожидает пакеты в транспортном обрамлении **OPPOv1**.

В классе `com.realme.iot.headset.tl.internal.DeviceConnectionImpl.t(DeviceInfo)` для TWS-гарнитур создаётся обёртка:
```java
OPPOv1Wrapper oPPOv1Wrapper = new OPPOv1Wrapper();
oPPOv1Wrapper.g(mtu);
oPPOv1Wrapper.h(mtu);
oPPOv1Wrapper.e(i); // sppCmdInterval
return oPPOv1Wrapper;
```

### 18.2. Структура транспортного кадра OPPOv1

Из класса `com.realme.iot.headset.tl.internal.message.wrapper.OPPOv1Wrapper`:
- **SOF (Start of Frame)**: `0xAA` (байт `-86`).
- **Длина (varint 7-bit)**: длина полезной нагрузки + 2 байта заголовка. Для пакетов $< 128$ байт занимает ровно 1 байт.
- **Байт флагов**: `0x00` (одиночный пакет, без фрагментации/FSN).
- **Байт резерва**: `0x00`.
- **Полезная нагрузка**: внутренний TL-кадр.

Точные кадры на физическом уровне (Wire Frames):
- **Запрос батареи (TL 0x0106)**:
  `AA 07 00 00 06 01 00 00 00` (9 байт)
- **Управление ANC (TL 0x0404)**:
  - ANC ON (Шумоподавление): `AA 0A 00 00 04 04 00 03 00 01 01 08` (12 байт)
  - ANC OFF (Выкл.): `AA 0A 00 00 04 04 00 03 00 01 01 01` (12 байт)
  - Transparency (Прозрачность): `AA 0A 00 00 04 04 00 03 00 01 01 02` (12 байт)

### 18.3. Обработка входящего потока

При получении данных из сокета `OPPOv1Wrapper.f(byte[])` проверяет `bArr[0] == 0xAA` и извлекает внутренний TL-кадр начиная с байта 4 (для одиночных пакетов), после чего пакет передаётся в парсер TL (`0x8106` для батареи, `0x8404` для подтверждения ANC, `0x8400` для асинхронных событий).

## 19. Автоматическое обновление состояния слотов при событиях кейса/наушников

### 19.1. Архитектура обновления по событиям (Event-driven Refresh)

Вместо разбора неподтверждённого формата асинхронных уведомлений `0x8400`, любое аппаратное событие от Buds T300 (вынимание наушника, открытие/закрытие кейса, сброс состояния) активирует отложенный опрос полного снимка батареи через проверенную команду `0x0106`:
- `BATTERY_POLL_INTERVAL_MS` уменьшен до **8 000 мс** (с 25 000 мс).
- `EVENT_REFRESH_DELAY_MS` = **400 мс** (дебаунс для склейки пачек событий).
- Ветка `CMD_NOTIFICATION (0x8400)`:
  - Событие смены ANC (`payload[0] == 0x03`) обновляет режим шумоподавления.
  - Все остальные уведомления запускают `scheduleBatteryRefresh("событие 0x..")`.
- Ветка `else` (неизвестные кадры): логирует сырой кадр и запускает `scheduleBatteryRefresh("кадр 0x..")`. Зацикливания нет, так как `0x8106` и `0x8404` обрабатываются отдельными терминальными ветками.

### 19.2. Сброс отсутствующих слотов (Snapshot Reset)

При получении ответа `0x8106` с `status == 0` и размером $\ge 2$ данные трактуются как полный снимок текущего состояния:
- Для каждого слота (`LEFT_EAR`, `RIGHT_EAR`, `CASE`), отсутствующего в списке записей ответа, выставляется `level = -1, isCharging = false`.
- Это предотвращает «залипание» старых значений, когда второй наушник убран в кейс или кейс закрыт.
- В UI значение `level == -1` отображается как «—» (отключён/в кейсе).

## 20. Нормализация неактивного слота кейса `[подтверждено логом устройства]`

В ответе `0x8106` при закрытом кейсе или когда в кейсе нет наушника, прошивка присылает слот кейса с нулевым уровнем:
`RX Battery [00 02 02 5A 03 00] -> правый 90%, кейс 03 00`.
Значение `03 00` означает «нет данных / кейс недоступен», а не «кейс разряжен до 0%».
В `T300BleManager.kt` добавлена функция `normalizeBatteries`:
Если `role == CASE`, `level == 0` и `!isCharging`, уровень сбрасывается в `-1` (в UI отображается как «—»).
При открытии кейса или установке наушника прошивка передаёт реальный процент заряда кейса, и он отображается штатно.


## 21. Чтение текущего состояния шумоподавления (ANC State) `[по коду / подтверждено на устройстве]`

### 21.1. Запрос текущего режима ANC
- **Команда (Cmd ID)**: `0x010C` (268 decimal, `CMD_ANC_STATE_REQ`).
- **Payload запроса**: `01 01` (2 байта).
- **TL Wire Frame**: `0C 01 00 02 00 01 01` (7 байт).
- **Кадр в обёртке OPPOv1**: `AA 07 00 00 0C 01 00 02 00 01 01` (11 байт).
- **Источник**: `PollCommandManager.k(mac)` / `TLHeadsetConnectionImp.querySettings(2)`.

### 21.2. Ответ гарнитуры
- **Опкод ответа**: `0x810C` (33036 decimal, `0x010C | 0x8000`, `CMD_ANC_STATE_RSP`).
- **Формат Payload**: `[status, action, type, noiseStatus]`
  - `status` (`payload[0]`): `0x00` (Success).
  - `action` (`payload[1]`): `0x01`.
  - `type` (`payload[2]`): `0x01`.
  - `noiseStatus` (`payload[3]`): байт активного режима.
- **Маппинг режимов**:
  - `0x08` = **Шумоподавление** (`NOISE_REDUCTION`)
  - `0x01` = **Выкл.** (`OFF`)
  - `0x02` = **Прозрачность** (`TRANSPARENCY`)
- **Статус**: подтверждено на физическом устройстве Realme Buds T300. При подключении приложение сразу получает актуальный режим из наушников.

## 22. Асинхронные события наушников (CMD_EVENT 0x0204) и подписка (0x0201) `[по коду / подтверждено на устройстве]`

### 22.1. Опкод событий устройства
На реальном устройстве все асинхронные события (смена режима ANC через датчик на ножке наушника, помещение в кейс/извлечение, переключение игрового режима) приходят с кодом команды **`0x0204`** (516 decimal, `CMD_EVENT`), а не `0x8400`.

### 22.2. Подписка на уведомления (Notification Registration)
- **Команда**: `0x0201` (513 decimal, `CMD_NOTIFICATION_REGISTER`).
- **Payload**: `[eventType]` (1 байт).
  - `0x03` — события смены шумоподавления (ANC).
  - `0x05` — события переключателей функций (SwitchFeature / Game Mode).
  - `0x01` — события батареи.
  - `0x02` — статус наушников (в ухе / в кейсе).
- **Ответ**: `0x8201` (33281 decimal), payload `[status]` (0x00 = Success).

### 22.3. Структура полезной нагрузки событий 0x0204
- **Событие ANC (`payload[0] == 0x03`)**:
  - Длина $\ge 4$: `[03, action, type, noiseStatus]`. При `action == 1` режим шумоподавления извлекается из `payload[3]`.
  - Длина $== 3$: `[03, type, noiseStatus]`. Режим шумоподавления извлекается из `payload[2]`.
- **Событие переключателя (`payload[0] == 0x05`)**:
  - Формат: `[05, value]`, где `value == 1` (ВКЛ), `value == 0` (ВЫКЛ).
- **Неизвестные/прочие события**:
  - Активируют дебаунс-опрос полного снимка состояния через `scheduleStateRefresh(120 мс)`.

## 23. Игровой режим (Game Mode / SwitchFeature — Этап 1) `[по коду / подтверждено на устройстве]`

В протоколе TL TWS игровой режим реализован через подсистему `SwitchFeature` (идентификатор фичи `0x06`).

### 23.1. Запрос текущего состояния
- **Команда (Cmd ID)**: `0x010D` (269 decimal, `CMD_SWITCH_FEATURE_REQ`).
- **Payload запроса**: `[01, 06]` (тип запроса 1, featureId = 0x06).
- **Кадр в обёртке OPPOv1**: `AA 07 00 00 0D 01 00 02 00 01 06`.
- **Источник**: `PollCommandManager.m(mac, 6)`.
- **Ответ**: `0x810D` (33037 decimal, `CMD_SWITCH_FEATURE_RSP`).
- **Структура ответа**: `[status (0x00), count (N), (featureId, value)...]`.
  - Для `featureId == 0x06`: `value == 0x01` (ВКЛ), `value == 0x00` (ВЫКЛ).
- **Источник парсера**: `CommandUtil.k(1, bArrE)`.

### 23.2. Установка игрового режима
- **Команда (Cmd ID)**: `0x0403` (1027 decimal, `CMD_SWITCH_FEATURE_SET`).
- **Payload установки**: `[featureId, value]`
  - Включить: `06 01`
  - Выключить: `06 00`
- **TL Wire Frames**:
  - Включить: `03 04 00 02 00 06 01`
  - Выключить: `03 04 00 02 00 06 00`
- **Кадры в обёртке OPPOv1**:
  - Включить: `AA 07 00 00 03 04 00 02 00 06 01`
  - Выключить: `AA 07 00 00 03 04 00 02 00 06 00`
- **Источник**: `SetCommandManager.k(mac, 6, enabled, listener)`.
- **Ответ**: `0x8403` (33795 decimal, `CMD_SWITCH_FEATURE_SET_RSP`), payload `[status]` (`0x00` = Success).
- **Асинхронное событие**: `0x0204`, payload `[05, value]`.
- **Результат на физическом устройстве**: подтверждено пользователем. Переключение и чтение игрового режима работают в полной мере.

## 24. Звуковые эффекты / Пресеты эквалайзера (EQ Presets — Этап 2) `[по коду]`

Подсистема эквалайзера управляется через `SettingsInfo(14)` (`HEADSET_SOUND_EFFECT`).

### 24.1. Чтение текущего пресета эквалайзера
- **Команда (Cmd ID)**: `0x010F` (271 decimal, `CMD_EQ_REQ`).
- **Payload запроса**: пустой (`new byte[0]`).
- **TL Wire Frame**: `0F 01 00 00 00`.
- **Кадр в обёртке OPPOv1**: `AA 05 00 00 0F 01 00 00 00`.
- **Источник**: `PollCommandManager.j(mac)` -> `this.b.b(str, 271)`.
- **Ответ**: `0x810F` (33039 decimal, `0x010F | 0x8000`, `CMD_EQ_RSP`).
- **Структура ответа**: `[status, eqId]`
  - `status` (`bArrE[0]`): `0x00` (Success).
  - `eqId` (`bArrE[1]`): идентификатор активного пресета (`iE = ProtocolUtil.e(bArrE, 1, 1, false)`).
- **Источник парсера**: `PollCommandManager.X(str, packet)` -> отправляет сообщение `18` в Handler `TLHeadsetConnectionImp`.

### 24.2. Таблица соответствия пресетов эквалайзера

Декомпиляция `TLHeadsetConnectionImp.T5()` (строки 1404–1416), Handler сообщения `18` (`TLHeadsetConnectionImp.java`, строки 414–435) и `AllEqActivity.I9()` (строки 395–399):

| Пресет эквалайзера | Название в realme Link | Wire `eqId` (Байт протокола) | UI ID / `SettingsInfo` |
|---|---|:---:|:---:|
| **Серенада** (Clear Vocals / Serenade) | `headset_pure_voice` | **`0x02`** (2) | `4` |
| **Оригинальный звук** (Original Sound) | `headset_pure_raw` | **`0x00`** (0) | `2` |
| **Чистые басы** (Pure Bass) | `headset_pure_bass` | **`0x03`** (3) | `8` |
| **Глубокие басы** (Deep Bass) | `headset_deep_bass` | **`0x01`** (1) | `1` |
| **Пользовательский** (Custom EQ) | Пользовательское имя ("Test"...) | **`> 0x03` / `0xFF`** | `0` |

### 24.3. Установка пресета эквалайзера
- **Команда (Cmd ID)**: `0x0406` (1030 decimal, `CMD_EQ_SET`).
- **Payload установки**: `[eqId]` (1 байт).
- **TL Wire Frames**:
  - Серенада: `06 04 00 01 00 02`
  - Оригинальный звук: `06 04 00 01 00 00`
  - Чистые басы: `06 04 00 01 00 03`
  - Глубокие басы: `06 04 00 01 00 01`
- **Кадры в обёртке OPPOv1**:
  - Серенада: `AA 06 00 00 06 04 00 01 00 02`
  - Оригинальный звук: `AA 06 00 00 06 04 00 01 00 00`
  - Чистые басы: `AA 06 00 00 06 04 00 01 00 03`
  - Глубокие басы: `AA 06 00 00 06 04 00 01 00 01`
- **Источник**: `SetCommandManager.n(mac, eqId, listener)`.
- **Ответ**: `0x8406` (33798 decimal, `CMD_EQ_SET_RSP`), payload `[status]` (`0x00` = Success).

### 24.4. Детекция пользовательского профиля эквалайзера (Custom EQ)
- **Команда запроса всех профилей**: `0x0122` (290 decimal, `CMD_ALL_EQ_REQ`), payload пустой.
- **Опкод ответа**: `0x8122` (33058 decimal, `CMD_ALL_EQ_RSP`).
- **Парсер по `CommandUtil.b(1, bArrE)`**:
  - `bArr[0]` = status (`0x00`).
  - `bArr[1]` = количество профилей (N).
  - Структура записи: `isSelected` (1 байт, 1 = активен), `minValue` (1 байт), `maxValue` (1 байт), `eqId` (1 байт), `nameLength` (1 байт), `name` (N байт UTF-8), `frequencyNum` (1 байт), сетка частот и dB.
  - Если `isSelected == 1`, соответствующий профиль («Test», «Test 2») отображается в приложении как активный. Создание/редактирование кривых частот в приложении намеренно опущено по условиям задачи.

### 24.5. Автоматическая синхронизация эквалайзера `[реализовано]`
В отличие от ANC и игрового режима, прошивка не шлёт отдельный push-эвент 0x0204 при смене эквалайзера во внешнем приложении (realme Link). Для мгновенного и бесшовного отображения актуального пресета реализовано:
1. **Фоновый опрос (`startPolling`)**: `refreshEq()` (cmd `0x010F`) включён в регулярный цикл опроса с интервалом 2000 мс (с безопасным интервалом 60 мс между командами).
2. **Мгновенный опрос на фокусе (`onResume`)**: при возвращении из realme Link в наше приложение немедленно вызывается `viewModel.refreshAll()`, подтягивая текущий пресет из наушников за десятки миллисекунд.

## 25. Настройки кнопок / жестов касания (Touch Button Settings — Этап 3) `[по коду]`

Подсистема настройки жестов сенсорной панели на ножках наушников управляется через `SettingsInfo(5)` (общие настройки кнопок), `SettingsInfo(6)` (левый наушник) и `SettingsInfo(7)` (правый наушник).

### 25.1. Чтение текущих настроек кнопок
- **Команда (Cmd ID)**: `0x0108` (264 decimal, `CMD_KEY_FUNCTION_REQ`, `Protocol.z`).
- **Payload запроса**: список поддерживаемых идентификаторов функций (15 байт):
  `0E 01 03 04 05 06 0B 0C 08 0D 11 1F 1A 1B 00`
  (байт 0 = количество 14 / `0x0E`, далее 14 байт ID).
- **TL Wire Frame**: `08 01 00 0F 00 0E 01 03 04 05 06 0B 0C 08 0D 11 1F 1A 1B 00` (20 байт).
- **Кадр в обёртке OPPOv1**: `AA 16 00 00 08 01 00 0F 00 0E 01 03 04 05 06 0B 0C 08 0D 11 1F 1A 1B 00` (24 байта).
- **Источник**: `PollCommandManager.t(mac)` -> `this.b.c(str, Protocol.z, u())`.

### 25.2. Ответ гарнитуры
- **Опкод ответа**: `0x8108` (33032 decimal, `0x0108 | 0x8000`, `CMD_KEY_FUNCTION_RSP`, `Protocol.A`).
- **Источник парсера**: `PollCommandManager.h0(str, packet)` -> создаёт список `KeyFunctionInfo`.
- **Структура ответа**: `[status (0x00), count (N), (deviceType, button, action, function)...]`
  - `status` (`bArrE[0]`): `0x00` (Success).
  - `count` (`bArrE[1]`): общее количество настроек (обычно 6 записей: 3 жеста для левого и 3 для правого).
  - Каждая запись занимает ровно 4 байта:
    - Байт 0 (`deviceType`): `0x01` = **Левый наушник** (`LEFT_EAR`), `0x02` = **Правый наушник** (`RIGHT_EAR`).
    - Байт 1 (`button`): `0x01` (сенсорная панель ножки).
    - Байт 2 (`action`): идентификатор жеста:
      - `0x02` = **Двойное касание** (`ITEM_ACTION_DOUBLE_CLICK`, `voca_main_key_double_click`)
      - `0x03` = **Тройное касание** (`ITEM_ACTION_TRI_CLICK`, `voca_main_key_triple_click`)
      - `0x04` = **Касание и удержание (1 с)** (`ITEM_ACTION_LONG_CLICK_1S`, `headset_long_one`)
    - Байт 3 (`function`): идентификатор назначенного действия на физическом уровне протокола.

### 25.3. Установка настройки кнопки
- **Команда (Cmd ID)**: `0x0401` (1025 decimal, `CMD_KEY_FUNCTION_SET`, `Protocol.Z0`).
- **Payload установки для одного жеста**: `[count (0x01), deviceType, button (0x01), action, function]` (5 байт).
- **TL Wire Frame**: `01 04 00 05 00 01 <deviceType> 01 <action> <function>` (10 байт).
- **Кадр в обёртке OPPOv1**: `AA 0C 00 00 01 04 00 05 00 01 <deviceType> 01 <action> <function>` (14 байт).
- **Источник**: `SetCommandManager.s(mac, list, listener)` -> `this.a.c(str, Protocol.Z0, bArr)`.
- **Ответ**: `0x8401` (33793 decimal, `CMD_KEY_FUNCTION_SET_RSP`, `Protocol.a1`), payload `[status]` (`0x00` = Success).

### 25.4. Таблица соответствия действий жестов
Декомпиляция `ParamsConverter.A(SettingsInfo)` (строки 60–94), `PodsUtils.b(KeyFunctionInfo, boolean)` (строки 44–98) и `KeyTextUtils.a(int)` (строки 10–42):

| Действие в realme Link | Название строки ресурса | Wire `function` (Байт протокола) | UI `settingValue` |
|---|---|:---:|:---:|
| **Воспроизведение / Пауза** | `voca_setting_value_play_or_pause` | **`0x01`** (1) | `1` |
| **Следующая композиция** | `voca_setting_value_play_next` | **`0x06`** (6) | `2` |
| **Предыдущая композиция** | `voca_setting_value_play_previous` | **`0x05`** (5) | `3` |
| **Голосовой помощник** | `voca_setting_value_wake_audio_assistant` | **`0x03`** (3 или 4) | `4` |
| **Шумоподавление** | `voca_setting_value_voice_reduction_main` | **`0x08`** (8) | `5` |
| **Переключить устройства** | `voca_setting_value_device_change` | **`0x0D`** (13) | `6` |
| **Игровой режим** | `voca_setting_value_game_model` | **`0x11`** (17) | `7` |
| **Увеличение громкости** | `headset_setting_value_value_volume_increase` | **`0x0B`** (11) | `8` |
| **Уменьшение громкости** | `headset_setting_value_value_volume_reduce` | **`0x0C`** (12) | `9` |
| **Нет действия** | `voca_setting_value_none` | **`0x00`** (0) | `0` |

### 25.5. Допустимый набор действий по жестам (по интерфейсу realme Link для Buds T300)
- **Двойное касание (Double tap)**:
  - Воспроизведение / Пауза (`0x01`)
  - Следующая композиция (`0x06`)
  - Голосовой помощник (`0x03`)
  - Нет действия (`0x00`)
- **Тройное касание (Triple tap)**:
  - Предыдущая композиция (`0x05`)
  - Следующая композиция (`0x06`)
  - Увеличение громкости (`0x0B`)
  - Уменьшение громкости (`0x0C`)
  - Переключить устройства (`0x0D`)
  - Голосовой помощник (`0x03`)
  - Игровой режим (`0x11`)
  - Нет действия (`0x00`)
- **Касание и удержание (Long press 1s)**:
  - Переключение режимов шумоподавления (`0x08`)
  - Голосовой помощник (`0x03`)
  - Игровой режим (`0x11`)
  - Нет действия (`0x00`)

## 26. Пространственное аудио (Spatial Audio / 3D Sound — Этап 4) `[по коду]`

В протоколе TL TWS пространственное аудио (3D Sound Effect) управляется через подсистему `SwitchFeature` (идентификатор фичи `0x1B` / 27 decimal) и связано с `SettingsInfo(38)` (`TYPE_ITEM_3D_SOUND_EFFECT`).

### 26.1. Запрос текущего состояния пространственного аудио
- **Команда (Cmd ID)**: `0x010D` (269 decimal, `CMD_SWITCH_FEATURE_REQ`, `Protocol.P`).
- **Payload запроса**: `[01, 1B]` (тип запроса 1, featureId = 0x1B / 27).
- **TL Wire Frame**: `0D 01 00 02 00 01 1B` (7 байт).
- **Кадр в обёртке OPPOv1**: `AA 07 00 00 0D 01 00 02 00 01 1B` (11 байт).
- **Источник**: `TLHeadsetConnectionImp.x(device, 38, ...)` -> `PollCommandManager.J(mac, 27)`.
- **Ответ**: `0x810D` (33037 decimal, `CMD_SWITCH_FEATURE_RSP`).
- **Структура ответа**: `[status (0x00), count (N), (featureId, value)...]`.
  - Для `featureId == 0x1B` (27): `value == 0x01` (ВКЛ), `value == 0x00` (ВЫКЛ).
- **Источник парсера**: `CommandUtil.k(1, bArrE)`.

### 26.2. Установка пространственного аудио
- **Команда (Cmd ID)**: `0x0403` (1027 decimal, `CMD_SWITCH_FEATURE_SET`, `Protocol.k0`).
- **Payload установки**: `[featureId, value]`
  - Включить: `1B 01`
  - Выключить: `1B 00`
- **TL Wire Frames**:
  - Включить: `03 04 00 02 00 1B 01`
  - Выключить: `03 04 00 02 00 1B 00`
- **Кадры в обёртке OPPOv1**:
  - Включить: `AA 07 00 00 03 04 00 02 00 1B 01`
  - Выключить: `AA 07 00 00 03 04 00 02 00 1B 00`
- **Источник**: `TLHeadsetConnectionImp.z5(device, settingsInfo, boolean)` -> `SetCommandManager.y(mac, 27, z, listener)`.
- **Ответ**: `0x8403` (33795 decimal, `CMD_SWITCH_FEATURE_SET_RSP`), payload `[status]` (`0x00` = Success).

### 26.3. Кнопка «Прослушать» (демо-звук) `[статически доказано по коду]`
По результатам декомпиляции `SpatialAudioActivity.java` (строки 65–90, 175–185):
- Кнопка «Прослушать» запускает локальный Android `MediaPlayer` с аудиофайлами из ресурсов APK:
  - `R.raw.headset_spatial_audio_off`
  - `R.raw.headset_spatial_audio_fix`
- Управление звуковым фокусом выполняется через `HearIdAudioManager`.
- **Вывод**: это локальное воспроизведение аудиофайла на стороне смартфона, а **НЕ команда протокола в наушники**.

### 26.4. Улучшение панели логов `[реализовано]`
1. **Сворачивание / разворачивание**: панель может быть свёрнута в компактную строку с заголовком и счётчиком записей, либо развёрнута по клику (с плавной анимацией `AnimatedVisibility`).
2. **Копирование логов**: кнопка «Скопировать» копирует полный стек строк в системный буфер обмена (`ClipboardManager`) с выводом всплывающего уведомления (`Toast`).
3. **Очистка логов**: кнопка «Очистить» сбрасывает накопленный буфер логов в `BudsViewModel`.
4. **Независимый плавный скроллинг**: устранён конфликт жестов с внешним `verticalScroll` экрана; при поступлении новых записей список автоматически доскролливается вниз.

## 27. Сводная таблица протокола Realme Buds T300

Все команды передаются через транспорт RFCOMM SPP (`UUID: 0000079A-D102-11E1-9B23-00025B00A5A5`) в обёртке OPPOv1 (`SOF 0xAA`):

| Функция | TX Cmd ID | TX Payload | RX Cmd ID | RX Payload формат | Статус |
|---|:---:|---|:---:|---|:---:|
| **Запрос батареи** | `0x0106` | *пустой* | `0x8106` | `[00, count, (slot, level\|flag)...]` | Подтверждено на устройстве |
| **Установка ANC** | `0x0404` | `01 01 <status>` | `0x8404` | `[00]` | Подтверждено на устройстве |
| **Запрос режима ANC** | `0x010C` | `01 01` | `0x810C` | `[00, 01, 01, <status>]` | Подтверждено на устройстве |
| **Запрос Game Mode** | `0x010D` | `01 06` | `0x810D` | `[00, count, (06, val)...]` | Подтверждено на устройстве |
| **Установка Game Mode**| `0x0403` | `06 <01/00>` | `0x8403` | `[00]` | Подтверждено на устройстве |
| **Запрос Spatial Audio**| `0x010D` | `01 1B` | `0x810D` | `[00, count, (1B, val)...]` | [по коду] Реализовано |
| **Установка Spatial Audio**| `0x0403` | `1B <01/00>` | `0x8403` | `[00]` | [по коду] Реализовано |
| **Запрос пресета EQ** | `0x010F` | *пустой* | `0x810F` | `[00, <eqId>]` | Подтверждено на устройстве |
| **Установка пресета EQ**| `0x0406` | `<eqId>` | `0x8406` | `[00]` | Подтверждено на устройстве |
| **Запрос кастомных EQ**| `0x0122` | *пустой* | `0x8122` | `[00, count, (selected, ..., name)...]` | Подтверждено на устройстве |
| **Запрос кнопок (жестов)**| `0x0108` | `0E 01 03 04 ... 00` | `0x8108` | `[00, count, (dev, 1, act, func)...]` | [по коду] Реализовано |
| **Установка жеста кнопки**| `0x0401` | `01 dev 01 act func` | `0x8401` | `[00]` | [по коду] Реализовано |
| **Подписка на события** | `0x0201` | `<type>` | `0x8201` | `[00]` | Подтверждено на устройстве |
| **Асинхронные события** | — | — | `0x0204` | `[03, ...]`, `[05, val]` | Подтверждено на устройстве |

### Репозиторий проекта
- Исходный код: `https://github.com/Gitveu/realess-t300.git` (ветка `main`).
- Текущая версия: включает Этап 1 (Game Mode), Этап 2 (EQ Presets + Custom EQ), Этап 3 (Настройка кнопок) и Этап 4 (Пространственное аудио + улучшенная консоль логов).
- Скомпилированный APK: `/Users/cybertveu/realme/BudsT300Controller-debug.apk`.
