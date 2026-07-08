#pragma once

#include <Arduino.h>

typedef void (*SosCallback)();

void beginButtons();
void updateButtons(SosCallback onSos);

