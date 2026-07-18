#pragma once

#include <Arduino.h>

#include "data_model.h"

typedef void (*TouchEventCallback)(uint8_t electrode, TouchEventType type);

bool touchBegin();
bool touchMpr121Active();
void touchUpdate(TouchEventCallback callback);
const char *touchEventName(TouchEventType type);
