#include <ArduinoBLE.h>

const int SENSOR_PIN = A0;

// Geräte-ID (einzigartig für jedes Gerät)
const char* DEVICE_ID = "SG-001";

// Kalibrierungswerte
int minWert = 0;
int maxWert = 1023;
bool isCalibrated = false;

// BLE Service und Characteristics
BLEService gloveService("19B10000-E8F2-537E-4F6C-D104768A1214");
BLEIntCharacteristic stretchChar("19B10001-E8F2-537E-4F6C-D104768A1214", BLERead | BLENotify);
BLEIntCharacteristic rawChar("19B10002-E8F2-537E-4F6C-D104768A1214", BLERead | BLENotify);
BLEStringCharacteristic deviceIdChar("19B10003-E8F2-537E-4F6C-D104768A1214", BLERead, 20);
BLEIntCharacteristic minCalChar("19B10004-E8F2-537E-4F6C-D104768A1214", BLERead | BLEWrite | BLENotify);
BLEIntCharacteristic maxCalChar("19B10005-E8F2-537E-4F6C-D104768A1214", BLERead | BLEWrite | BLENotify);
BLEBoolCharacteristic calibratedChar("19B10006-E8F2-537E-4F6C-D104768A1214", BLERead | BLENotify);

void setup() {
  Serial.begin(115200);
  pinMode(SENSOR_PIN, INPUT);
  
  // BLE initialisieren
  if (!BLE.begin()) {
    Serial.println("BLE Start fehlgeschlagen!");
    while (1);
  }
  
  // Gerätename mit ID
  String fullName = "SmartGlove-";
  fullName += DEVICE_ID;
  BLE.setLocalName(fullName.c_str());
  BLE.setAdvertisedService(gloveService);
  
  // Characteristics hinzufügen
  gloveService.addCharacteristic(stretchChar);
  gloveService.addCharacteristic(rawChar);
  gloveService.addCharacteristic(deviceIdChar);
  gloveService.addCharacteristic(minCalChar);
  gloveService.addCharacteristic(maxCalChar);
  gloveService.addCharacteristic(calibratedChar);
  BLE.addService(gloveService);
  
  // Startwerte
  stretchChar.writeValue(0);
  rawChar.writeValue(0);
  deviceIdChar.writeValue(DEVICE_ID);
  minCalChar.writeValue(minWert);
  maxCalChar.writeValue(maxWert);
  calibratedChar.writeValue(isCalibrated);
  
  // Advertising starten
  BLE.advertise();
  
  Serial.print("SmartGlove ");
  Serial.print(DEVICE_ID);
  Serial.println(" bereit!");
  Serial.println("Warte auf Verbindung...");
}

void loop() {
  BLEDevice central = BLE.central();
  
  if (central) {
    Serial.print("Verbunden mit: ");
    Serial.println(central.address());
    
    while (central.connected()) {
      // Kalibrierungswerte von App empfangen
      if (minCalChar.written()) {
        minWert = minCalChar.value();
        Serial.print("Neuer Min-Wert: ");
        Serial.println(minWert);
      }
      
      if (maxCalChar.written()) {
        maxWert = maxCalChar.value();
        isCalibrated = true;
        calibratedChar.writeValue(isCalibrated);
        Serial.print("Neuer Max-Wert: ");
        Serial.println(maxWert);
      }
      
      int rohwert = analogRead(SENSOR_PIN);
      
      // Normalisierung auf 0-100%
      int prozent = 0;
      if (maxWert != minWert) {
        prozent = map(rohwert, minWert, maxWert, 0, 100);
        prozent = constrain(prozent, 0, 100);
      }
      
      // BLE Werte senden
      stretchChar.writeValue(prozent);
      rawChar.writeValue(rohwert);
      
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
    
    Serial.println("Verbindung getrennt");
  }
}
