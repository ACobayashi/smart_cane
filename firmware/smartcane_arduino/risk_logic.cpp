#include "risk_logic.h"

#include "config.h"
#include <math.h>

static bool isHistoricalMediumOrHigh(const NearbyRiskSummary &nearby) {
  return nearby.available && (nearby.maxLevel == RISK_MEDIUM || nearby.maxLevel == RISK_HIGH);
}

static void applyBestSide(RiskState &risk, const DistanceReadings &d) {
  if (d.leftValid && d.rightValid) {
    if (d.leftCm < SMARTCANE_SIDE_BLOCKED_CM && d.rightCm < SMARTCANE_SIDE_BLOCKED_CM) {
      risk.direction = "stop";
      return;
    }
    if (d.leftCm > d.rightCm && d.leftCm > SMARTCANE_SIDE_SAFE_CM) {
      risk.direction = "turn_left";
      return;
    }
    if (d.rightCm > d.leftCm && d.rightCm > SMARTCANE_SIDE_SAFE_CM) {
      risk.direction = "turn_right";
      return;
    }
  }
  if (d.leftValid && !d.rightValid) {
    risk.direction = d.leftCm > SMARTCANE_SIDE_SAFE_CM ? "turn_left" : "slow";
    return;
  }
  if (!d.leftValid && d.rightValid) {
    risk.direction = d.rightCm > SMARTCANE_SIDE_SAFE_CM ? "turn_right" : "slow";
    return;
  }
  risk.direction = "slow";
}

static bool isGroundRisk(const char *riskType) {
  return strcmp(riskType, "ground_step") == 0 || strcmp(riskType, "ground_drop") == 0;
}

static void chooseMoreSevere(RiskState &best, const RiskState &candidate) {
  if (strcmp(best.riskType, "none") == 0 && strcmp(candidate.riskType, "none") != 0) {
    best = candidate;
    return;
  }
  // A confirmed stair is more precise than the front ToF's simultaneous view
  // of its vertical riser. Keep ground_step/up or ground_step/down distinct.
  if (isGroundRisk(candidate.riskType) && !isGroundRisk(best.riskType)) {
    best = candidate;
    return;
  }
  if (!isGroundRisk(candidate.riskType) && isGroundRisk(best.riskType)) {
    return;
  }
  if (candidate.level > best.level) {
    best = candidate;
    return;
  }
  if (candidate.level < best.level) return;
  if (strcmp(candidate.direction, "stop") == 0 && strcmp(best.direction, "stop") != 0) {
    best = candidate;
    return;
  }
  if (strcmp(candidate.riskType, "front_obstacle") == 0 && strcmp(best.riskType, "front_obstacle") != 0) {
    best = candidate;
  }
}

// A ground change is expressed in compensated down height.  Positive means
// the ground moved farther from the sensor (a down step); negative means it
// moved closer (an up step).  The names intentionally match the public API.
enum GroundStepState {
  GROUND_NORMAL,
  GROUND_CANDIDATE_UP,
  GROUND_CANDIDATE_DOWN,
  GROUND_STEP_UP,
  GROUND_STEP_DOWN,
  GROUND_DROP,
  GROUND_RELEARN
};

static GroundStepState groundState = GROUND_NORMAL;
static const char *downRiskReason = "clear";
static bool baselineReady = false;
static float baselineDownCm = 0.0f;
static float normalUsePitchDeg = 0.0f;
static float normalUseRollDeg = 0.0f;
static uint8_t baselineFrames = 0;
static int8_t directionHistory[SMARTCANE_STEP_HISTORY_SAMPLES] = {0};
static uint8_t directionHistoryCount = 0;
static uint8_t directionHistoryIndex = 0;
static int8_t candidateDirection = 0;  // +1 down, -1 up
static const char *confirmedGroundRiskType = "none";
static unsigned long candidateStartedMs = 0;
static unsigned long confirmedAtMs = 0;
static unsigned long normalUseStableSinceMs = 0;
static unsigned long startupRelearnUntilMs = 0;
static float rebaseLastCm = 0.0f;
static uint8_t rebaseFrames = 0;
static float lastCompensatedDownCm = -1.0f;
static float lastHeightDeltaCm = 0.0f;
static bool lastCaneMotion = false;

static const char *groundStateName() {
  switch (groundState) {
    case GROUND_CANDIDATE_UP: return "GROUND_CANDIDATE_UP";
    case GROUND_CANDIDATE_DOWN: return "GROUND_CANDIDATE_DOWN";
    case GROUND_STEP_UP: return "GROUND_STEP_UP";
    case GROUND_STEP_DOWN: return "GROUND_STEP_DOWN";
    case GROUND_DROP: return "GROUND_DROP";
    case GROUND_RELEARN: return "GROUND_RELEARN";
    case GROUND_NORMAL:
    default: return "GROUND_NORMAL";
  }
}

const char *groundDetectorState() {
  return groundStateName();
}

void resetGroundStepDetector() {
  groundState = GROUND_NORMAL;
  baselineReady = false;
  baselineDownCm = 0.0f;
  normalUsePitchDeg = 0.0f;
  normalUseRollDeg = 0.0f;
  baselineFrames = 0;
  directionHistoryCount = 0;
  directionHistoryIndex = 0;
  candidateDirection = 0;
  confirmedGroundRiskType = "none";
  candidateStartedMs = 0;
  confirmedAtMs = 0;
  normalUseStableSinceMs = 0;
  startupRelearnUntilMs = 0;
  rebaseLastCm = 0.0f;
  rebaseFrames = 0;
  lastCompensatedDownCm = -1.0f;
  lastHeightDeltaCm = 0.0f;
  lastCaneMotion = false;
}

int groundBaselineDownCm() {
  return baselineReady ? (int)roundf(baselineDownCm) : -1;
}

static int downRiskCm(const DistanceReadings &d) {
  // The raw, valid down path is deliberately used for edge response.  The
  // display/telemetry EMA remains useful for the other three directions.
  return d.downRawCm > 0 ? d.downRawCm : d.downCm;
}

static float projectedDownCm(int rawCm, const ImuFallState &imu) {
  if (!imu.available) return (float)rawCm;
  const float pitch = (imu.pitchDeg + SMARTCANE_DOWN_SENSOR_MOUNT_PITCH_DEG) * DEG_TO_RAD;
  const float roll = (imu.rollDeg + SMARTCANE_DOWN_SENSOR_MOUNT_ROLL_DEG) * DEG_TO_RAD;
  const float verticalProjection = fmaxf(0.30f, fabsf(cosf(pitch) * cosf(roll)));
  return rawCm * verticalProjection;
}

static bool imuAtNormalUsePose(const ImuFallState &imu) {
  return !imu.available ||
      (fabsf(imu.pitchDeg - normalUsePitchDeg) <= SMARTCANE_DOWN_NORMAL_POSE_DELTA_DEG &&
       fabsf(imu.rollDeg - normalUseRollDeg) <= SMARTCANE_DOWN_NORMAL_POSE_DELTA_DEG &&
       fabsf(imu.totalG - 1.0f) <= SMARTCANE_DOWN_NORMAL_G_DELTA &&
       imu.gyroDps <= SMARTCANE_DOWN_MOTION_GYRO_DPS);
}

static bool caneInMotion(const ImuFallState &imu) {
  if (!imu.available) return false;
  return imu.gyroDps > SMARTCANE_DOWN_MOTION_GYRO_DPS ||
      fabsf(imu.totalG - 1.0f) > SMARTCANE_DOWN_NORMAL_G_DELTA ||
      fabsf(imu.pitchDeg - normalUsePitchDeg) > SMARTCANE_DOWN_MOTION_POSE_DELTA_DEG ||
      fabsf(imu.rollDeg - normalUseRollDeg) > SMARTCANE_DOWN_MOTION_POSE_DELTA_DEG;
}

static bool isInitialBaselineSampleStable(const ImuFallState &imu) {
  return !imu.available ||
      (fabsf(imu.totalG - 1.0f) <= SMARTCANE_DOWN_NORMAL_G_DELTA &&
       imu.gyroDps <= SMARTCANE_DOWN_MOTION_GYRO_DPS);
}

static void clearCandidate() {
  directionHistoryCount = 0;
  directionHistoryIndex = 0;
  candidateDirection = 0;
  candidateStartedMs = 0;
}

static void rememberDirection(int8_t direction) {
  directionHistory[directionHistoryIndex] = direction;
  directionHistoryIndex = (directionHistoryIndex + 1) % SMARTCANE_STEP_HISTORY_SAMPLES;
  if (directionHistoryCount < SMARTCANE_STEP_HISTORY_SAMPLES) directionHistoryCount++;
}

static uint8_t directionVotes(int8_t direction) {
  uint8_t votes = 0;
  for (uint8_t i = 0; i < directionHistoryCount; ++i) {
    if (directionHistory[i] == direction) votes++;
  }
  return votes;
}

static void attachGroundTelemetry(RiskState &risk) {
  risk.compensatedDownCm = lastCompensatedDownCm;
  risk.groundBaselineCm = baselineReady ? baselineDownCm : -1.0f;
  risk.heightDeltaCm = lastHeightDeltaCm;
  risk.groundState = groundStateName();
  risk.caneMotion = lastCaneMotion;
}

static const char *confirmedGroundRisk() {
  return confirmedGroundRiskType;
}

static const char *updateDownRiskState(const DistanceReadings &d, const ImuFallState &imu) {
  const int rawCm = downRiskCm(d);
  const unsigned long now = millis();
  downRiskReason = "clear";
  lastCompensatedDownCm = -1.0f;
  lastHeightDeltaCm = 0.0f;
  lastCaneMotion = false;

  if (d.downNoTarget || rawCm >= SMARTCANE_DOWN_NO_TARGET_CM) {
    clearCandidate();
    downRiskReason = "down_no_target_ignored";
    return "none";
  }
  if (!d.downValid) {
    clearCandidate();
    // The ToF reader intentionally tolerates a few missed samples.  Do not
    // turn one bus timeout while the cane is moving into a medium, audible
    // hazard; only report a genuinely unavailable down sensor after the same
    // configured failure count used by the reader itself.
    if (d.downFailCount < SMARTCANE_TOF_FAILS_BEFORE_INVALID) {
      downRiskReason = "down_transient_read_ignored";
      return "none";
    }
    downRiskReason = "down_sensor_unavailable";
    return "down_sensor_unavailable";
  }

  const float compensatedCm = projectedDownCm(rawCm, imu);
  lastCompensatedDownCm = compensatedCm;

  if (!baselineReady) {
    if (!isInitialBaselineSampleStable(imu)) {
      baselineFrames = 0;
      downRiskReason = "baseline_waiting_for_normal_use";
      return "none";
    }
    if (baselineFrames == 0 || fabsf(compensatedCm - baselineDownCm) > SMARTCANE_DOWN_BASELINE_TOLERANCE_CM) {
      baselineDownCm = compensatedCm;
      normalUsePitchDeg = imu.pitchDeg;
      normalUseRollDeg = imu.rollDeg;
      baselineFrames = 1;
    } else {
      baselineDownCm = (baselineDownCm * baselineFrames + compensatedCm) / (baselineFrames + 1);
      normalUsePitchDeg = (normalUsePitchDeg * baselineFrames + imu.pitchDeg) / (baselineFrames + 1);
      normalUseRollDeg = (normalUseRollDeg * baselineFrames + imu.rollDeg) / (baselineFrames + 1);
      if (baselineFrames < 255) baselineFrames++;
    }
    if (baselineFrames >= SMARTCANE_DOWN_BASELINE_STABLE_FRAMES) {
      baselineReady = true;
      groundState = GROUND_NORMAL;
      // The initial seven readings can finish before the user has fully
      // positioned a freshly powered cane.  Treat the next short still
      // interval as baseline settling instead of a real curb/stair event.
      startupRelearnUntilMs = now + SMARTCANE_DOWN_STARTUP_RELEARN_MS;
      downRiskReason = "normal_use_baseline_settling";
    } else {
      downRiskReason = "learning_normal_use_baseline";
    }
    return "none";
  }

  lastHeightDeltaCm = compensatedCm - baselineDownCm;
  const bool poseNearNormal = imuAtNormalUsePose(imu);
  const bool caneMotion = caneInMotion(imu);
  lastCaneMotion = caneMotion;

  if ((long)(now - startupRelearnUntilMs) < 0) {
    clearCandidate();
    groundState = GROUND_NORMAL;
    normalUseStableSinceMs = 0;
    // This window only runs immediately after boot.  Replacing the baseline
    // with each still sample lets the real held angle/range win over the
    // first value observed while the device was being picked up.
    if (poseNearNormal && !caneMotion) {
      baselineDownCm = compensatedCm;
      normalUsePitchDeg = imu.pitchDeg;
      normalUseRollDeg = imu.rollDeg;
      lastHeightDeltaCm = 0.0f;
      downRiskReason = "startup_normal_use_settling";
    } else {
      downRiskReason = "startup_waiting_for_still_normal_use";
    }
    return "none";
  }
  const int8_t direction = lastHeightDeltaCm >= SMARTCANE_STEP_DOWN_ENTER_CM ? 1 :
                           (lastHeightDeltaCm <= -SMARTCANE_STEP_UP_ENTER_CM ? -1 : 0);

  if (groundState == GROUND_STEP_UP || groundState == GROUND_STEP_DOWN || groundState == GROUND_DROP ||
      groundState == GROUND_RELEARN) {
    groundState = GROUND_RELEARN;
    // A new level is adopted only after the confirmed edge has been held and
    // the cane is back in its normal use posture for 0.4 s.  This permits
    // successive stairs without teaching the edge itself into the baseline.
    if (poseNearNormal && !caneMotion) {
      if (rebaseFrames == 0 || fabsf(compensatedCm - rebaseLastCm) <= SMARTCANE_DOWN_BASELINE_TOLERANCE_CM) {
        rebaseLastCm = compensatedCm;
        if (rebaseFrames < 255) rebaseFrames++;
      } else {
        rebaseLastCm = compensatedCm;
        rebaseFrames = 1;
      }
    } else {
      rebaseFrames = 0;
    }
    const char *holdRisk = confirmedGroundRisk();
    if (confirmedAtMs != 0 && now - confirmedAtMs >= SMARTCANE_STEP_REBASE_MIN_HOLD_MS &&
        rebaseFrames >= SMARTCANE_STEP_REBASE_STABLE_FRAMES) {
      baselineDownCm = compensatedCm;
      normalUsePitchDeg = imu.pitchDeg;
      normalUseRollDeg = imu.rollDeg;
      groundState = GROUND_NORMAL;
      clearCandidate();
      rebaseFrames = 0;
      lastHeightDeltaCm = 0.0f;
      downRiskReason = "new_ground_baseline_ready";
      return "none";
    }
    downRiskReason = "confirmed_step_waiting_new_ground";
    return holdRisk;
  }

  // A raised/swept cane changes the down range in exactly the same direction
  // as a lower floor. Do not carry raw samples from that motion into the later
  // normal-use confirmation; re-arm only after a short stable hold. The real
  // stair thresholds and two-of-three confirmation remain unchanged.
  if (!poseNearNormal || caneMotion) {
    clearCandidate();
    groundState = GROUND_NORMAL;
    normalUseStableSinceMs = 0;
    downRiskReason = "cane_motion_candidate_cancelled";
    return "none";
  }
  if (normalUseStableSinceMs == 0) {
    normalUseStableSinceMs = now;
  }

  if (direction != 0) {
    if (now - normalUseStableSinceMs < SMARTCANE_STEP_NORMAL_POSE_SETTLE_MS) {
      // Suppress the front ToF's view of the stair riser while the ground
      // detector takes its short, independent confirmation window.
      groundState = direction < 0 ? GROUND_CANDIDATE_UP : GROUND_CANDIDATE_DOWN;
      downRiskReason = "step_candidate_waiting_stable_normal_use";
      return "none";
    }
    if (candidateDirection != direction) {
      clearCandidate();
      candidateDirection = direction;
      candidateStartedMs = now;
    }
    rememberDirection(direction);
    groundState = direction < 0 ? GROUND_CANDIDATE_UP : GROUND_CANDIDATE_DOWN;
    // During a sweep/lift, preserve the candidate but do not confirm it.  Once
    // the cane returns near normal use, two of the latest three raw samples
    // are sufficient, so a real edge confirms in roughly 200 ms.
    if (poseNearNormal && !caneMotion && directionVotes(direction) >= SMARTCANE_STEP_CONFIRM_SAMPLES) {
      confirmedAtMs = now;
      rebaseFrames = 0;
      if (direction < 0) {
        groundState = GROUND_STEP_UP;
        confirmedGroundRiskType = "ground_step";
        downRiskReason = "compensated_ground_rise_confirmed";
        return "ground_step";
      }
      if (lastHeightDeltaCm >= SMARTCANE_DEEP_DROP_CM) {
        groundState = GROUND_DROP;
        confirmedGroundRiskType = "ground_drop";
        downRiskReason = "compensated_deep_drop_confirmed";
        return "ground_drop";
      }
      groundState = GROUND_STEP_DOWN;
      confirmedGroundRiskType = "ground_step";
      downRiskReason = "compensated_ground_drop_confirmed";
      return "ground_step";
    }
    downRiskReason = caneMotion ? "step_candidate_waiting_normal_use" : "step_candidate_waiting_second_sample";
    return "none";
  }

  // A lifted/swept cane returns to the old ground baseline.  Cancel its
  // candidate immediately rather than waiting for a long EMA or cooldown.
  if (fabsf(lastHeightDeltaCm) <= SMARTCANE_STEP_CLEAR_CM) {
    clearCandidate();
    groundState = GROUND_NORMAL;
    if (poseNearNormal && !caneMotion) {
      baselineDownCm = baselineDownCm * 0.985f + compensatedCm * 0.015f;
      normalUsePitchDeg = normalUsePitchDeg * 0.985f + imu.pitchDeg * 0.015f;
      normalUseRollDeg = normalUseRollDeg * 0.985f + imu.rollDeg * 0.015f;
    }
    downRiskReason = "normal_ground";
    return "none";
  }

  clearCandidate();
  groundState = GROUND_NORMAL;
  downRiskReason = "ground_delta_below_step_threshold";
  return "none";
}

RiskState calculateRisk(const DistanceReadings &d, const NearbyRiskSummary &nearby, const ImuFallState &imu) {
  RiskState best;
  best.detectedAtMs = millis();
  best.level = RISK_LOW;
  best.riskType = "none";
  best.direction = "none";
  best.sensor = "tof";
  best.reason = "clear";
  best.confidence = d.valid ? 0.7f : 0.2f;

  const char *downRiskType = updateDownRiskState(d, imu);
  const int downCmForRisk = downRiskCm(d);
  const bool groundCandidateActive = groundState == GROUND_CANDIDATE_UP ||
                                     groundState == GROUND_CANDIDATE_DOWN;
  RiskState risk;
  if (strcmp(downRiskType, "down_sensor_unavailable") == 0) {
    risk.level = RISK_MEDIUM;
    risk.riskType = "down_sensor_unavailable";
    risk.direction = "stop";
    risk.sensor = "tof_down";
    risk.reason = downRiskReason;
    risk.distanceMm = -1;
    risk.confidence = 0.70f;
    risk.groundDrop = true;
    risk.realtimeMedium = true;
    attachGroundTelemetry(risk);
    chooseMoreSevere(best, risk);
  } else if (strcmp(downRiskType, "ground_step") == 0 || strcmp(downRiskType, "ground_drop") == 0) {
    risk.level = RISK_MEDIUM;
    risk.riskType = downRiskType;
    risk.direction = candidateDirection < 0 ? "up" : "down";
    risk.sensor = "tof_down";
    risk.reason = downRiskReason;
    risk.distanceMm = downCmForRisk * 10;
    risk.confidence = strcmp(downRiskType, "ground_drop") == 0 ? 0.92f : 0.87f;
    risk.groundDrop = true;
    risk.realtimeMedium = true;
    attachGroundTelemetry(risk);
    chooseMoreSevere(best, risk);
  }

  if (!groundCandidateActive && d.frontValid && d.frontCm <= SMARTCANE_FRONT_DANGER_CM) {
    risk = RiskState();
    risk.detectedAtMs = best.detectedAtMs;
    risk.level = RISK_HIGH;
    risk.riskType = "front_obstacle";
    risk.sensor = "tof_front";
    risk.reason = "front_distance_at_or_below_danger_threshold";
    risk.distanceMm = d.frontCm * 10;
    risk.confidence = 0.88f;
    risk.frontObstacle = true;
    risk.realtimeHigh = true;
    applyBestSide(risk, d);
    attachGroundTelemetry(risk);
    chooseMoreSevere(best, risk);
  } else if (!groundCandidateActive && d.frontValid && d.frontCm <= SMARTCANE_FRONT_WARN_CM) {
    risk = RiskState();
    risk.detectedAtMs = best.detectedAtMs;
    risk.level = RISK_LOW;
    risk.riskType = "front_obstacle";
    risk.sensor = "tof_front";
    risk.reason = "front_distance_at_or_below_warn_threshold";
    risk.distanceMm = d.frontCm * 10;
    risk.confidence = 0.45f;
    risk.frontObstacle = true;
    if (isHistoricalMediumOrHigh(nearby)) {
      risk.reason = "front_warn_plus_nearby_history";
      risk.historyInfluenced = true;
      risk.confidence = 0.55f;
    }
    applyBestSide(risk, d);
    attachGroundTelemetry(risk);
    chooseMoreSevere(best, risk);
  }

  if (d.leftValid && d.leftCm <= SMARTCANE_SIDE_ALERT_CM) {
    risk = RiskState();
    risk.detectedAtMs = best.detectedAtMs;
    risk.level = RISK_LOW;
    risk.riskType = "left_obstacle";
    risk.direction = "keep_right";
    risk.sensor = "tof_left";
    risk.reason = "left_side_at_or_below_35cm";
    risk.distanceMm = d.leftCm * 10;
    risk.confidence = 0.60f;
    risk.sideObstacle = true;
    if (isHistoricalMediumOrHigh(nearby)) {
      risk.historyInfluenced = true;
      risk.reason = "left_side_35cm_plus_nearby_history";
    }
    attachGroundTelemetry(risk);
    chooseMoreSevere(best, risk);
  }

  if (d.rightValid && d.rightCm <= SMARTCANE_SIDE_ALERT_CM) {
    risk = RiskState();
    risk.detectedAtMs = best.detectedAtMs;
    risk.level = RISK_LOW;
    risk.riskType = "right_obstacle";
    risk.direction = "keep_left";
    risk.sensor = "tof_right";
    risk.reason = "right_side_at_or_below_35cm";
    risk.distanceMm = d.rightCm * 10;
    risk.confidence = 0.60f;
    risk.sideObstacle = true;
    if (isHistoricalMediumOrHigh(nearby)) {
      risk.historyInfluenced = true;
      risk.reason = "right_side_35cm_plus_nearby_history";
    }
    attachGroundTelemetry(risk);
    chooseMoreSevere(best, risk);
  }

  attachGroundTelemetry(best);
  return best;
}

const char *riskDirectionLabel(const RiskState &risk) {
  return risk.direction;
}

void printRiskState(const RiskState &risk) {
  Serial.print(F("risk="));
  Serial.print(riskLevelToString(risk.level));
  Serial.print(F(" type="));
  Serial.print(risk.riskType);
  Serial.print(F(" direction="));
  Serial.print(risk.direction);
  Serial.print(F(" sensor="));
  Serial.print(risk.sensor);
  Serial.print(F(" ground="));
  Serial.print(risk.groundState);
  Serial.print(F(" delta="));
  Serial.print(risk.heightDeltaCm, 1);
  Serial.print(F(" confidence="));
  Serial.print(risk.confidence, 2);
  Serial.print(F(" reason="));
  Serial.println(risk.reason);
}
