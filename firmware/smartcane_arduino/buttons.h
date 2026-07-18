#pragma once

#include <Arduino.h>

typedef void (*SosCallback)();

void buttonsBegin();
void buttonsUpdate(SosCallback callback);
