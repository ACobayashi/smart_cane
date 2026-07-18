#include "buttons.h"

#include "config.h"

static bool stablePressed = false;
static bool lastRawPressed = false;
static bool sosSent = false;
static unsigned long changedAtMs = 0;
static unsigned long pressedAtMs = 0;

static bool readPressed() {
  int raw = digitalRead(SMARTCANE_SOS_BUTTON_PIN);
  return SMARTCANE_SOS_ACTIVE_LOW ? (raw == LOW) : (raw == HIGH);
}

void buttonsBegin() {
  pinMode(SMARTCANE_SOS_BUTTON_PIN, SMARTCANE_SOS_ACTIVE_LOW ? INPUT_PULLUP : INPUT_PULLDOWN);
  stablePressed = readPressed();
  lastRawPressed = stablePressed;
  changedAtMs = millis();
  Serial.print(F("[SOS] button GPIO "));
  Serial.println(SMARTCANE_SOS_BUTTON_PIN);
}

void buttonsUpdate(SosCallback callback) {
  bool rawPressed = readPressed();
  unsigned long now = millis();

  if (rawPressed != lastRawPressed) {
    lastRawPressed = rawPressed;
    changedAtMs = now;
  }

  if (now - changedAtMs < SMARTCANE_BUTTON_DEBOUNCE_MS) {
    return;
  }

  if (rawPressed != stablePressed) {
    stablePressed = rawPressed;
    if (stablePressed) {
      pressedAtMs = now;
      sosSent = false;
      Serial.println(F("[SOS] press"));
    } else {
      Serial.println(F("[SOS] release"));
    }
  }

  if (stablePressed && !sosSent && now - pressedAtMs >= SMARTCANE_SOS_HOLD_MS) {
    sosSent = true;
    if (callback != nullptr) {
      callback();
    }
  }
}
