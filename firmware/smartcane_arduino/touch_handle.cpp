#include "touch_handle.h"

#include <Adafruit_MPR121.h>

#include "config.h"

static Adafruit_MPR121 cap = Adafruit_MPR121();
static bool touchMockActive = false;

static bool touched[6] = {false, false, false, false, false, false};
static bool longSent[6] = {false, false, false, false, false, false};
static bool pendingTap[6] = {false, false, false, false, false, false};
static unsigned long touchStartAt[6] = {0, 0, 0, 0, 0, 0};
static unsigned long pendingTapAt[6] = {0, 0, 0, 0, 0, 0};

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

static void emitEvent(TouchEventCallback callback, uint8_t electrode, TouchEventType type) {
  if (electrode > 5) {
    return;
  }
  Serial.print("Touch event electrode=");
  Serial.print(electrode);
  Serial.print(" type=");
  Serial.println(touchEventName(type));
  if (callback) {
    callback(electrode, type);
  }
}

static void handleSerialMock(TouchEventCallback callback) {
#if MOCK_TOUCH_SERIAL
  while (Serial.available() > 0) {
    char c = (char)Serial.read();
    if (c >= '0' && c <= '5') {
      emitEvent(callback, (uint8_t)(c - '0'), TOUCH_EVENT_TAP);
    } else if (c >= 'A' && c <= 'F') {
      emitEvent(callback, (uint8_t)(c - 'A'), TOUCH_EVENT_LONG_PRESS);
    } else if (c >= 'a' && c <= 'f') {
      emitEvent(callback, (uint8_t)(c - 'a'), TOUCH_EVENT_DOUBLE_CLICK);
    } else if (c == 'u' || c == 'U') {
      emitEvent(callback, 1, TOUCH_EVENT_LONG_PRESS);
    } else if (c == 'r' || c == 'R') {
      emitEvent(callback, 2, TOUCH_EVENT_TAP);
    } else if (c == 'm' || c == 'M') {
      emitEvent(callback, 3, TOUCH_EVENT_TAP);
    }
  }
#else
  (void)callback;
#endif
}

bool beginTouchHandle() {
  if (!cap.begin(MPR121_I2C_ADDR)) {
    touchMockActive = true;
    Serial.println("MPR121 missing, touch serial mock enabled");
    Serial.println("Serial mock: 0-5 tap, A-F long press, a-f double click, U=user_mark, R=repeat, M=mode");
    return false;
  }

  touchMockActive = false;
  Serial.print("MPR121 ready at 0x");
  Serial.println(MPR121_I2C_ADDR, HEX);
  return true;
}

void updateTouchHandle(TouchEventCallback callback) {
  handleSerialMock(callback);
  if (touchMockActive) {
    return;
  }

  unsigned long now = millis();
  uint16_t mask = cap.touched();

  for (uint8_t e = 0; e < 6; e++) {
    bool nowTouched = (mask & (1 << e)) != 0;

    if (nowTouched && !touched[e]) {
      touched[e] = true;
      touchStartAt[e] = now;
      longSent[e] = false;
    }

    if (nowTouched && touched[e] && !longSent[e] && (now - touchStartAt[e]) >= TOUCH_LONG_PRESS_MS) {
      longSent[e] = true;
      pendingTap[e] = false;
      emitEvent(callback, e, TOUCH_EVENT_LONG_PRESS);
    }

    if (!nowTouched && touched[e]) {
      touched[e] = false;
      if (!longSent[e]) {
        if (pendingTap[e] && (now - pendingTapAt[e]) <= TOUCH_DOUBLE_CLICK_MS) {
          pendingTap[e] = false;
          emitEvent(callback, e, TOUCH_EVENT_DOUBLE_CLICK);
        } else {
          pendingTap[e] = true;
          pendingTapAt[e] = now;
        }
      }
    }

    if (pendingTap[e] && (now - pendingTapAt[e]) > TOUCH_DOUBLE_CLICK_MS) {
      pendingTap[e] = false;
      emitEvent(callback, e, TOUCH_EVENT_TAP);
    }
  }
}

bool isTouchMockActive() {
  return touchMockActive;
}

