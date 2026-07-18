#include "i2c_bus.h"

#include "config.h"

bool i2cBusBegin() {
  Wire.begin(SMARTCANE_I2C_SDA_PIN, SMARTCANE_I2C_SCL_PIN);
  Wire.setClock(SMARTCANE_I2C_CLOCK_HZ);
  delay(50);

  Serial.print(F("[I2C] SDA="));
  Serial.print(SMARTCANE_I2C_SDA_PIN);
  Serial.print(F(" SCL="));
  Serial.print(SMARTCANE_I2C_SCL_PIN);
  Serial.print(F(" clock="));
  Serial.println(SMARTCANE_I2C_CLOCK_HZ);

  return i2cProbe(SMARTCANE_TCA9548A_ADDR);
}

bool selectTcaChannel(uint8_t channel) {
  if (channel > 7) {
    return false;
  }

  Wire.beginTransmission(SMARTCANE_TCA9548A_ADDR);
  Wire.write(1 << channel);
  uint8_t err = Wire.endTransmission();
  if (err != 0) {
    Serial.print(F("[I2C] TCA channel select failed ch="));
    Serial.print(channel);
    Serial.print(F(" err="));
    Serial.println(err);
    return false;
  }
  delayMicroseconds(500);
  return true;
}

bool i2cProbe(uint8_t address) {
  Wire.beginTransmission(address);
  return Wire.endTransmission() == 0;
}

void i2cScanRoot() {
  i2cScanCurrentBus("root");
}

void i2cScanCurrentBus(const char *label) {
  Serial.print(F("[I2C] scan "));
  Serial.println(label == nullptr ? "bus" : label);

  for (uint8_t addr = 1; addr < 127; ++addr) {
    Wire.beginTransmission(addr);
    if (Wire.endTransmission() == 0) {
      Serial.print(F("  found 0x"));
      if (addr < 16) Serial.print('0');
      Serial.println(addr, HEX);
      delay(2);
    }
  }
}

void i2cScanTcaChannels() {
  for (uint8_t ch = 0; ch < 8; ++ch) {
    if (!selectTcaChannel(ch)) {
      continue;
    }
    char label[16];
    snprintf(label, sizeof(label), "TCA CH%u", ch);
    i2cScanCurrentBus(label);
  }
}
