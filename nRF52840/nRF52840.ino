// Board: "Seeed XIAO nRF52840 Sense" aus "Seeed nRF52 Boards" (Bluefruit / SoftDevice),
// NICHT die "(No Updates)"-Variante aus "Seeed nRF52 mbed-enabled Boards".
#include <bluefruit.h>

const int SENSOR_PIN = A0;

// Geräte-ID (einzigartig für jedes Gerät)
const char* DEVICE_ID = "SG-001";

// PIN für die Kopplung (6 Ziffern). Eigene PIN in secrets.h setzen (wird nicht committet):
//   #define PAIRING_PIN "654321"
#if __has_include("secrets.h")
#include "secrets.h"
#endif
#ifndef PAIRING_PIN
#define PAIRING_PIN "123456"
#warning "Standard-PIN 123456 aktiv - eigene PIN in nRF52840/secrets.h setzen"
#endif

// Kalibrierungswerte
const int ADC_MAX = 1023;
int minWert = 0;
int maxWert = ADC_MAX;
bool isCalibrated = false;

// Ungekoppelte Verbindungen werden nach dieser Zeit getrennt
const unsigned long UNPAIRED_TIMEOUT_MS = 20000;

uint16_t connHandle = BLE_CONN_HANDLE_INVALID;
unsigned long connectedAt = 0;

// 128-bit UUIDs in Little-Endian (19B1xxxx-E8F2-537E-4F6C-D104768A1214)
#define GLOVE_UUID(x) { 0x14, 0x12, 0x8A, 0x76, 0x04, 0xD1, 0x6C, 0x4F, 0x7E, 0x53, 0xF2, 0xE8, x, 0x00, 0xB1, 0x19 }
const uint8_t UUID_SERVICE[16]    = GLOVE_UUID(0x00);
const uint8_t UUID_STRETCH[16]    = GLOVE_UUID(0x01);
const uint8_t UUID_RAW[16]        = GLOVE_UUID(0x02);
const uint8_t UUID_DEVICE_ID[16]  = GLOVE_UUID(0x03);
const uint8_t UUID_MIN_CAL[16]    = GLOVE_UUID(0x04);
const uint8_t UUID_MAX_CAL[16]    = GLOVE_UUID(0x05);
const uint8_t UUID_CALIBRATED[16] = GLOVE_UUID(0x06);

// BLE Service und Characteristics (alle nur mit verschlüsselter, per PIN gekoppelter Verbindung)
BLEService gloveService(UUID_SERVICE);
BLECharacteristic stretchChar(UUID_STRETCH);
BLECharacteristic rawChar(UUID_RAW);
BLECharacteristic deviceIdChar(UUID_DEVICE_ID);
BLECharacteristic minCalChar(UUID_MIN_CAL);
BLECharacteristic maxCalChar(UUID_MAX_CAL);
BLECharacteristic calibratedChar(UUID_CALIBRATED);

void setupCharacteristic(BLECharacteristic& chr, uint8_t props, bool writable, uint16_t len) {
  chr.setProperties(props);
  chr.setPermission(SECMODE_ENC_WITH_MITM, writable ? SECMODE_ENC_WITH_MITM : SECMODE_NO_ACCESS);
  chr.setFixedLen(len);
  chr.begin();
}

bool isValidCalibration(int value) {
  return value >= 0 && value <= ADC_MAX;
}

int32_t readInt32(const uint8_t* data, uint16_t len) {
  if (len != 4) return -1;
  return (int32_t)(data[0] | (data[1] << 8) | (data[2] << 16) | ((uint32_t)data[3] << 24));
}

// Kalibrierungswerte von App empfangen
void minCalWritten(uint16_t conn_hdl, BLECharacteristic* chr, uint8_t* data, uint16_t len) {
  int32_t wert = readInt32(data, len);
  if (isValidCalibration(wert)) {
    minWert = wert;
    Serial.print("Neuer Min-Wert: ");
    Serial.println(minWert);
  } else {
    Serial.print("Ungültiger Min-Wert ignoriert: ");
    Serial.println(wert);
    minCalChar.write32(minWert);
  }
}

void maxCalWritten(uint16_t conn_hdl, BLECharacteristic* chr, uint8_t* data, uint16_t len) {
  int32_t wert = readInt32(data, len);
  if (isValidCalibration(wert)) {
    maxWert = wert;
    isCalibrated = true;
    calibratedChar.notify8(isCalibrated);
    Serial.print("Neuer Max-Wert: ");
    Serial.println(maxWert);
  } else {
    Serial.print("Ungültiger Max-Wert ignoriert: ");
    Serial.println(wert);
    maxCalChar.write32(maxWert);
  }
}

void connectCallback(uint16_t conn_hdl) {
  connHandle = conn_hdl;
  connectedAt = millis();
  Serial.println("Verbunden");
}

void disconnectCallback(uint16_t conn_hdl, uint8_t reason) {
  connHandle = BLE_CONN_HANDLE_INVALID;
  Serial.print("Verbindung getrennt, Grund: 0x");
  Serial.println(reason, HEX);
}

void pairCompleteCallback(uint16_t conn_hdl, uint8_t auth_status) {
  if (auth_status == BLE_GAP_SEC_STATUS_SUCCESS) {
    Serial.println("Neues Gerät gekoppelt");
  } else {
    Serial.print("Kopplung fehlgeschlagen, Status: 0x");
    Serial.println(auth_status, HEX);
  }
}

void securedCallback(uint16_t conn_hdl) {
  Serial.println("Verbindung verschlüsselt");
}

// Serieller Befehl "CLEAR" löscht alle Kopplungen
void handleSerial() {
  if (!Serial.available()) return;
  String cmd = Serial.readStringUntil('\n');
  cmd.trim();
  if (cmd.equalsIgnoreCase("CLEAR")) {
    Bluefruit.Periph.clearBonds();
    Serial.println("Alle Kopplungen gelöscht");
  }
}

void startAdvertising() {
  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
  Bluefruit.Advertising.addService(gloveService);
  Bluefruit.ScanResponse.addName();

  Bluefruit.Advertising.restartOnDisconnect(true);
  Bluefruit.Advertising.setInterval(32, 244);  // in 0,625 ms
  Bluefruit.Advertising.setFastTimeout(30);
  Bluefruit.Advertising.start(0);
}

void setup() {
  Serial.begin(115200);
  pinMode(SENSOR_PIN, INPUT);
  // Referenz = Versorgungsspannung, passend zum Spannungsteiler an 3,3 V
  analogReference(AR_VDD4);
  analogReadResolution(10);

  // BLE initialisieren
  Bluefruit.begin();

  // Gerätename mit ID
  String fullName = "SmartGlove-";
  fullName += DEVICE_ID;
  Bluefruit.setName(fullName.c_str());

  // Kopplung mit fester PIN (Passkey Entry mit MITM-Schutz); Bonds speichert Bluefruit im Flash
  Bluefruit.Security.setPIN(PAIRING_PIN);
  Bluefruit.Security.setPairCompleteCallback(pairCompleteCallback);
  Bluefruit.Security.setSecuredCallback(securedCallback);

  Bluefruit.Periph.setConnectCallback(connectCallback);
  Bluefruit.Periph.setDisconnectCallback(disconnectCallback);

  // Characteristics hinzufügen
  gloveService.begin();
  setupCharacteristic(stretchChar, CHR_PROPS_READ | CHR_PROPS_NOTIFY, false, 4);
  setupCharacteristic(rawChar, CHR_PROPS_READ | CHR_PROPS_NOTIFY, false, 4);
  deviceIdChar.setProperties(CHR_PROPS_READ);
  deviceIdChar.setPermission(SECMODE_ENC_WITH_MITM, SECMODE_NO_ACCESS);
  deviceIdChar.setMaxLen(20);
  deviceIdChar.begin();
  setupCharacteristic(minCalChar, CHR_PROPS_READ | CHR_PROPS_WRITE | CHR_PROPS_NOTIFY, true, 4);
  setupCharacteristic(maxCalChar, CHR_PROPS_READ | CHR_PROPS_WRITE | CHR_PROPS_NOTIFY, true, 4);
  setupCharacteristic(calibratedChar, CHR_PROPS_READ | CHR_PROPS_NOTIFY, false, 1);
  minCalChar.setWriteCallback(minCalWritten);
  maxCalChar.setWriteCallback(maxCalWritten);

  // Startwerte
  stretchChar.write32(0);
  rawChar.write32(0);
  deviceIdChar.write(DEVICE_ID);
  minCalChar.write32(minWert);
  maxCalChar.write32(maxWert);
  calibratedChar.write8(isCalibrated);

  // Advertising starten
  startAdvertising();

  Serial.print("SmartGlove ");
  Serial.print(DEVICE_ID);
  Serial.println(" bereit!");
  Serial.println("Warte auf Verbindung...");
}

void loop() {
  handleSerial();

  if (connHandle == BLE_CONN_HANDLE_INVALID) {
    delay(50);
    return;
  }

  BLEConnection* connection = Bluefruit.Connection(connHandle);

  // Ohne Kopplung keine Daten; Fremde blockieren die Verbindung nicht dauerhaft
  if (connection == nullptr || !connection->secured()) {
    if (connection != nullptr && millis() - connectedAt > UNPAIRED_TIMEOUT_MS) {
      Serial.println("Nicht gekoppelt - trenne Verbindung");
      connection->disconnect();
    }
    delay(50);
    return;
  }

  int rohwert = analogRead(SENSOR_PIN);

  // Normalisierung auf 0-100%
  int prozent = 0;
  if (maxWert != minWert) {
    prozent = map(rohwert, minWert, maxWert, 0, 100);
    prozent = constrain(prozent, 0, 100);
  }

  // BLE Werte senden (ohne aktive Notifications wird nur der lesbare Wert aktualisiert)
  stretchChar.notify32(connHandle, prozent);
  rawChar.notify32(connHandle, rohwert);

  Serial.print("Rohwert: ");
  Serial.print(rohwert);
  Serial.print(" | Min: ");
  Serial.print(minWert);
  Serial.print(" | Max: ");
  Serial.print(maxWert);
  Serial.print(" | Dehnung: ");
  Serial.print(prozent);
  Serial.println("%");

  delay(50);
}
