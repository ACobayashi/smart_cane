#include "touch_handle.h"

#include <Adafruit_MPR121.h>

#include "config.h"
#include "i2c_bus.h"

static Adafruit_MPR121 cap = Adafruit_MPR121();
static bool capReady = false;
static bool pressed[12] = {false};
static bool longSent[12] = {false};
static unsigned long pressStartMs[12] = {0};
static unsigned long lastTapMs[12] = {0};

static bool selectTouchBus() {
#if SMARTCANE_TOUCH_ON_TCA
  return selectTcaChannel(SMARTCANE_TCA_CH_TOUCH);
#else
  return true;
#endif
}

bool touchBegin() {
  if (!selectTouchBus()) {
    Serial.println(F("[TOUCH] TCA channel select failed"));
    return false;
  }

  capReady = cap.begin(SMARTCANE_MPR121_ADDR, &Wire);
  Serial.print(F("[TOUCH] MPR121/HW017 addr=0x"));
  Serial.print(SMARTCANE_MPR121_ADDR, HEX);
#if SMARTCANE_TOUCH_ON_TCA
  Serial.print(F(" TCA CH"));
  Serial.print(SMARTCANE_TCA_CH_TOUCH);
#endif
  Serial.println(capReady ? F(" OK") : F(" FAILED"));
  return capReady;
}

bool touchMpr121Active() {
  return capReady;
}

void touchUpdate(TouchEventCallback callback) {
  if (!capReady || callback == nullptr || !selectTouchBus()) {
    return;
  }

  uint16_t touched = cap.touched();
  unsigned long now = millis();

  for (uint8_t i = 0; i < 6; ++i) {
    bool isDown = (touched & (1 << i)) != 0;

    if (isDown && !pressed[i]) {
      pressed[i] = true;
      longSent[i] = false;
      pressStartMs[i] = now;
      Serial.print(F("[TOUCH] E"));
      Serial.print(i);
      Serial.println(F(" down"));
    }

    if (isDown && pressed[i] && !longSent[i] &&
        now - pressStartMs[i] >= SMARTCANE_TOUCH_LONG_PRESS_MS) {
      longSent[i] = true;
      callback(i, TOUCH_EVENT_LONG_PRESS);
    }

    if (!isDown && pressed[i]) {
      pressed[i] = false;
      if (!longSent[i]) {
        if (now - lastTapMs[i] <= SMARTCANE_TOUCH_DOUBLE_CLICK_MS) {
          lastTapMs[i] = 0;
          callback(i, TOUCH_EVENT_DOUBLE_CLICK);
        } else {
          lastTapMs[i] = now;
          callback(i, TOUCH_EVENT_TAP);
        }
      }
    }
  }
}

const char *touchEventName(TouchEventType type) {
  switch (type) {
    case TOUCH_EVENT_LONG_PRESS:
      return "long_press";
    case TOUCH_EVENT_DOUBLE_CLICK:
      return "double_click";
    case TOUCH_EVENT_TAP:
    default:
      return "tap";
  }
}
