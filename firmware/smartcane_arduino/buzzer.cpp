#include "buzzer.h"

#include "config.h"

enum BuzzerMode {
  BUZZER_IDLE,
  BUZZER_SINGLE,
  BUZZER_DANGER,
  BUZZER_SOS
};

static BuzzerMode buzzerMode = BUZZER_IDLE;
static unsigned long nextStepAt = 0;
static uint8_t patternIndex = 0;
static bool buzzerOn = false;

static const uint16_t dangerDurations[] = {120, 90, 120};
static const bool dangerLevels[] = {true, false, true};
static const uint8_t dangerLen = 3;

static const uint16_t sosDurations[] = {180, 100, 180, 100, 180, 180, 260};
static const bool sosLevels[] = {true, false, true, false, true, false, true};
static const uint8_t sosLen = 7;

static void writeBuzzer(bool on) {
  buzzerOn = on;
  bool pinHigh = BUZZER_ACTIVE_HIGH ? on : !on;
  digitalWrite(BUZZER_PIN, pinHigh ? HIGH : LOW);
}

static void startPattern(BuzzerMode mode) {
  buzzerMode = mode;
  patternIndex = 0;
  nextStepAt = 0;
}

void beginBuzzer() {
  pinMode(BUZZER_PIN, OUTPUT);
  writeBuzzer(false);
  Serial.print("Buzzer ready on GPIO ");
  Serial.println(BUZZER_PIN);
}

void updateBuzzer() {
  unsigned long now = millis();

  if (buzzerMode == BUZZER_IDLE) {
    return;
  }

  if (nextStepAt != 0 && (long)(now - nextStepAt) < 0) {
    return;
  }

  if (buzzerMode == BUZZER_SINGLE) {
    writeBuzzer(false);
    buzzerMode = BUZZER_IDLE;
    return;
  }

  const uint16_t *durations = nullptr;
  const bool *levels = nullptr;
  uint8_t len = 0;

  if (buzzerMode == BUZZER_DANGER) {
    durations = dangerDurations;
    levels = dangerLevels;
    len = dangerLen;
  } else if (buzzerMode == BUZZER_SOS) {
    durations = sosDurations;
    levels = sosLevels;
    len = sosLen;
  }

  if (patternIndex >= len) {
    writeBuzzer(false);
    buzzerMode = BUZZER_IDLE;
    return;
  }

  writeBuzzer(levels[patternIndex]);
  nextStepAt = now + durations[patternIndex];
  patternIndex++;
}

void beep(uint16_t ms) {
  if (ms > 300) {
    ms = 300;
  }
  buzzerMode = BUZZER_SINGLE;
  patternIndex = 0;
  writeBuzzer(true);
  nextStepAt = millis() + ms;
}

void beepPatternDanger() {
  startPattern(BUZZER_DANGER);
}

void beepPatternSos() {
  startPattern(BUZZER_SOS);
}

