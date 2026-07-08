#include "gps_location.h"

#include "config.h"

#if USE_GPS_MODULE
#include <TinyGPS++.h>

static TinyGPSPlus gps;
static HardwareSerial gpsSerial(GPS_SERIAL_PORT);
#endif

static LocationData latestLocation = makeMockLocation();

static void useMockLocation() {
  latestLocation.lat = MOCK_LAT;
  latestLocation.lng = MOCK_LNG;
  latestLocation.valid = true;
  latestLocation.mock = true;
  latestLocation.accuracyM = GPS_MOCK_ACCURACY_M;
  latestLocation.satelliteCount = 0;
  latestLocation.updatedAtMs = millis();
}

void beginGpsLocation() {
#if USE_GPS_MODULE
  gpsSerial.begin(GPS_BAUD, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);
  Serial.print("GPS UART started rx=");
  Serial.print(GPS_RX_PIN);
  Serial.print(" tx=");
  Serial.print(GPS_TX_PIN);
  Serial.print(" baud=");
  Serial.println(GPS_BAUD);
#else
  Serial.println("GPS module disabled; using mock location");
#endif

#if GPS_MOCK_FALLBACK
  useMockLocation();
#endif
}

void updateGpsLocation() {
#if USE_GPS_MODULE
  while (gpsSerial.available() > 0) {
    gps.encode((char)gpsSerial.read());
  }

  if (gps.location.isUpdated() && gps.location.isValid()) {
    latestLocation.lat = gps.location.lat();
    latestLocation.lng = gps.location.lng();
    latestLocation.valid = true;
    latestLocation.mock = false;
    latestLocation.accuracyM = gps.hdop.isValid() ? gps.hdop.hdop() * 5.0f : 10.0f;
    latestLocation.satelliteCount = gps.satellites.isValid() ? gps.satellites.value() : 0;
    latestLocation.updatedAtMs = millis();
  }
#endif

#if GPS_MOCK_FALLBACK
  if (!latestLocation.valid || (!latestLocation.mock && (millis() - latestLocation.updatedAtMs) > GPS_FIX_STALE_MS)) {
    useMockLocation();
  }
#endif
}

LocationData getCurrentLocation() {
  updateGpsLocation();
  return latestLocation;
}

bool hasRealGpsFix() {
  return latestLocation.valid && !latestLocation.mock;
}

void printLocationStatus() {
  LocationData location = getCurrentLocation();
  Serial.print("LOCATION lat=");
  Serial.print(location.lat, 6);
  Serial.print(" lng=");
  Serial.print(location.lng, 6);
  Serial.print(" source=");
  Serial.print(location.mock ? "mock" : "gps");
  Serial.print(" acc_m=");
  Serial.print(location.accuracyM, 1);
  Serial.print(" sats=");
  Serial.println(location.satelliteCount);
}
