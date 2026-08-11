package com.nankai.smartcane.data.model

data class SelfSosGeneration(
    val riskPointId: Int,
    val timestamp: String,
    val reportCount: Int
)

enum class SelfSosReplayState {
    UNSEEN,
    WAITING_TO_LEAVE,
    ARMED,
    PLAYED
}

data class SelfSosReplayTransition(
    val state: SelfSosReplayState,
    val shouldPlay: Boolean,
    val isNewGeneration: Boolean
)

class SelfSosReplayStateMachine(
    private val exitThresholdM: Double = 10.0,
    private val enterThresholdM: Double = 5.0
) {
    var state: SelfSosReplayState = SelfSosReplayState.UNSEEN
        private set

    var generation: SelfSosGeneration? = null
        private set

    fun update(newGeneration: SelfSosGeneration, distanceM: Double): SelfSosReplayTransition {
        if (newGeneration != generation) {
            generation = newGeneration
            state = if (distanceM < exitThresholdM) {
                SelfSosReplayState.WAITING_TO_LEAVE
            } else {
                SelfSosReplayState.ARMED
            }
            return SelfSosReplayTransition(state, shouldPlay = false, isNewGeneration = true)
        }

        var shouldPlay = false
        state = when (state) {
            SelfSosReplayState.UNSEEN -> if (distanceM < exitThresholdM) {
                SelfSosReplayState.WAITING_TO_LEAVE
            } else {
                SelfSosReplayState.ARMED
            }
            SelfSosReplayState.WAITING_TO_LEAVE -> if (distanceM >= exitThresholdM) {
                SelfSosReplayState.ARMED
            } else {
                SelfSosReplayState.WAITING_TO_LEAVE
            }
            SelfSosReplayState.ARMED -> if (distanceM <= enterThresholdM) {
                shouldPlay = true
                SelfSosReplayState.PLAYED
            } else {
                SelfSosReplayState.ARMED
            }
            SelfSosReplayState.PLAYED -> SelfSosReplayState.PLAYED
        }
        return SelfSosReplayTransition(state, shouldPlay, isNewGeneration = false)
    }
}
