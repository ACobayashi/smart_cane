#include "buttons.h"

#include "config.h"

static bool lastRawPressed = false;
static bool stablePressed = false;
static bool sosSent = false;
static unsigned long lastChangeAt = 0;
static unsigned long pressStartAt = 0;

static bool readPressedRaw() {
  int level = digitalRead(SOS_BUTTON_PIN);
  if (SOS_BUTTON_ACTIVE_LOW) {
    return level == LOW;
  }
  return level == HIGH;
}

void beginButtons() {
  pinMode(SOS_BUTTON_PIN, SOS_BUTTON_ACTIVE_LOW ? INPUT_PULLUP : INPUT_PULLDOWN);
  lastRawPressed = readPressedRaw();
  stablePressed = lastRawPressed;
  lastChangeAt = millis();
  pressStartAt = stablePressed ? millis() : 0;
  sosSent = false;

  Serial.print("SOS button ready on GPIO ");
  Serial.print(SOS_BUTTON_PIN);
  Serial.print(" hold_ms=");
  Serial.println(SOS_HOLD_MS);
}

void updateButtons(SosCallback onSos) {
  unsigned long now = millis();
  bool rawPressed = readPressedRaw();

  if (rawPressed != lastRawPressed) {
    lastRawPressed = rawPressed;
    lastChangeAt = now;
  }

  if ((now - lastChangeAt) >= BUTTON_DEBOUNCE_MS && rawPressed != stablePressed) {
    stablePressed = rawPressed;
    if (stablePressed) {
      pressStartAt = now;
      sosSent = false;
      Serial.println("SOS button pressed");
    } else {
      Serial.println("SOS button released");
      pressStartAt = 0;
      sosSent = false;
    }
  }

  if (stablePressed && !sosSent && pressStartAt > 0 && (now - pressStartAt) >= SOS_HOLD_MS) {
    sosSent = true;
    if (onSos) {
      onSos();
    }
  }
}

