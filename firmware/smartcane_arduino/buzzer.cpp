#include "buzzer.h"

#include "config.h"

static bool singleBeepActive = false;
static unsigned long singleBeepUntilMs = 0;
static bool patternActive = false;
static bool patternOn = false;
static uint8_t patternRemaining = 0;
static uint16_t patternOnMs = 0;
static uint16_t patternOffMs = 0;
static unsigned long patternNextMs = 0;
static bool buzzerEnabled = SMARTCANE_BUZZER_ENABLED;

static uint8_t activeLevel() {
  return SMARTCANE_BUZZER_ACTIVE_HIGH ? HIGH : LOW;
}

static uint8_t idleLevel() {
  return SMARTCANE_BUZZER_ACTIVE_HIGH ? LOW : HIGH;
}

void buzzerBegin() {
  digitalWrite(SMARTCANE_BUZZER_PIN, idleLevel());
  pinMode(SMARTCANE_BUZZER_PIN, OUTPUT);
  digitalWrite(SMARTCANE_BUZZER_PIN, idleLevel());
  Serial.print(F("[BUZZER] GPIO "));
  Serial.print(SMARTCANE_BUZZER_PIN);
  Serial.println(buzzerEnabled ? F(" enabled") : F(" muted"));
}

void buzzerSetEnabled(bool enabled) {
  buzzerEnabled = enabled;
  if (!buzzerEnabled) {
    singleBeepActive = false;
    patternActive = false;
    digitalWrite(SMARTCANE_BUZZER_PIN, idleLevel());
  }
  Serial.print(F("[BUZZER] "));
  Serial.println(buzzerEnabled ? F("on") : F("off"));
}

bool buzzerIsEnabled() {
  return buzzerEnabled;
}

void beep(uint16_t ms) {
  if (!buzzerEnabled) {
    digitalWrite(SMARTCANE_BUZZER_PIN, idleLevel());
    return;
  }
  // A formal fall alert is a single continuous tone.  It must pre-empt a
  // leftover obstacle/SOS pattern instead of allowing the pattern timer to
  // turn the buzzer off midway through this requested duration.
  patternActive = false;
  digitalWrite(SMARTCANE_BUZZER_PIN, activeLevel());
  singleBeepActive = true;
  singleBeepUntilMs = millis() + ms;
}

static void startPattern(uint8_t repeats, uint16_t onMs, uint16_t offMs) {
  if (!buzzerEnabled) {
    digitalWrite(SMARTCANE_BUZZER_PIN, idleLevel());
    return;
  }
  patternActive = true;
  patternOn = true;
  patternRemaining = repeats;
  patternOnMs = onMs;
  patternOffMs = offMs;
  patternNextMs = millis() + onMs;
  digitalWrite(SMARTCANE_BUZZER_PIN, activeLevel());
}

void beepPatternDanger() {
  startPattern(2, 90, 90);
}

void beepPatternSos() {
  startPattern(6, 120, 120);
}

void buzzerUpdate() {
  unsigned long now = millis();

  if (singleBeepActive && (long)(now - singleBeepUntilMs) >= 0) {
    singleBeepActive = false;
    if (!patternActive) {
      digitalWrite(SMARTCANE_BUZZER_PIN, idleLevel());
    }
  }

  if (!patternActive || (long)(now - patternNextMs) < 0) {
    return;
  }

  if (patternOn) {
    digitalWrite(SMARTCANE_BUZZER_PIN, idleLevel());
    patternOn = false;
    patternNextMs = now + patternOffMs;
  } else {
    if (patternRemaining > 0) {
      patternRemaining--;
    }
    if (patternRemaining == 0) {
      patternActive = false;
      digitalWrite(SMARTCANE_BUZZER_PIN, idleLevel());
    } else {
      digitalWrite(SMARTCANE_BUZZER_PIN, activeLevel());
      patternOn = true;
      patternNextMs = now + patternOnMs;
    }
  }
}
