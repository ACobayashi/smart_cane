#pragma once

#include <Arduino.h>

enum TouchEventType {
  TOUCH_EVENT_TAP,
  TOUCH_EVENT_LONG_PRESS,
  TOUCH_EVENT_DOUBLE_CLICK
};

typedef void (*TouchEventCallback)(uint8_t electrode, TouchEventType type);

bool beginTouchHandle();
void updateTouchHandle(TouchEventCallback callback);
bool isTouchMockActive();
const char *touchEventName(TouchEventType type);

