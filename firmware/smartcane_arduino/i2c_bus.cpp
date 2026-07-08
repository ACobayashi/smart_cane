#include "i2c_bus.h"

#include "config.h"

bool beginI2CBus() {
  Wire.begin(I2C_SDA_PIN, I2C_SCL_PIN);
  Wire.setClock(I2C_CLOCK_HZ);
  delay(20);

  Serial.print("I2C started SDA=");
  Serial.print(I2C_SDA_PIN);
  Serial.print(" SCL=");
  Serial.print(I2C_SCL_PIN);
  Serial.print(" clock=");
  Serial.println(I2C_CLOCK_HZ);

  return true;
}

bool selectTcaChannel(uint8_t ch) {
  if (ch > 7) {
    return false;
  }

  Wire.beginTransmission(TCA9548A_I2C_ADDR);
  Wire.write(1 << ch);
  uint8_t error = Wire.endTransmission();
  if (error != 0) {
    Serial.print("TCA9548A select failed ch=");
    Serial.print(ch);
    Serial.print(" err=");
    Serial.println(error);
    return false;
  }
  return true;
}

bool i2cDevicePresent(uint8_t address) {
  Wire.beginTransmission(address);
  return Wire.endTransmission() == 0;
}

void printI2CDeviceStatus() {
  Serial.print("I2C TCA9548A 0x");
  Serial.print(TCA9548A_I2C_ADDR, HEX);
  Serial.println(i2cDevicePresent(TCA9548A_I2C_ADDR) ? " found" : " missing");

  Serial.print("I2C MPR121 0x");
  Serial.print(MPR121_I2C_ADDR, HEX);
  Serial.println(i2cDevicePresent(MPR121_I2C_ADDR) ? " found" : " missing");

  Serial.print("I2C PCA9685 0x");
  Serial.print(PCA9685_I2C_ADDR, HEX);
  Serial.println(i2cDevicePresent(PCA9685_I2C_ADDR) ? " found" : " missing");
}

