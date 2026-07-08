#pragma once

#include <Arduino.h>

void beginBuzzer();
void updateBuzzer();
void beep(uint16_t ms);
void beepPatternDanger();
void beepPatternSos();

