#pragma once

#include <Arduino.h>

void buzzerBegin();
void buzzerUpdate();
void buzzerSetEnabled(bool enabled);
bool buzzerIsEnabled();
void buzzerStop();
void beep(uint16_t ms);
void beepPatternDanger();
void beepPatternSos();
