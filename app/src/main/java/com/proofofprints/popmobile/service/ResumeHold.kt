package com.proofofprints.popmobile.service

/**
 * The cool-down hold that gates restoring full threads after a thermal
 * throttle, and resuming after a thermal pause. Pure so the decision can be
 * reasoned about (and later unit-tested) apart from the service.
 *
 * A hold starts once the temperature is at or below the resume threshold
 * with heat NORMAL, and completes once it has run the hold time (30 s in
 * MiningService) without being cancelled. While holding, only a real rise cancels it: above
 * resume + [TOLERANCE_C], or heat reaching THROTTLE / CRITICAL. Sensor
 * readings jitter about ±1 °C between 3 s samples, so cancelling on any
 * sample above the threshold meant a phone sitting just under it at the
 * throttled thread count never got its threads back.
 */
object ResumeHold {
    /** How far above the resume threshold a reading may wander during a
     *  hold before the hold is cancelled. */
    const val TOLERANCE_C = 1.0f

    enum class Step {
        /** Not holding and not cool enough to start. */
        IDLE,
        /** Cool enough: start the hold now. */
        START,
        /** Holding; keep waiting. */
        HOLD,
        /** Was holding, but it warmed up: drop the hold. */
        CANCEL,
        /** Held long enough: restore / resume. */
        READY
    }

    /**
     * Whether the current reading allows a hold to start ([holding] false)
     * or continue ([holding] true). [tempC] <= 0 means no sysfs reading; the
     * OS heat signal alone then decides, and must be NORMAL.
     */
    fun conditionsMet(
        holding: Boolean,
        tempC: Float,
        resumeC: Float,
        heat: ThermalMonitor.ThermalState
    ): Boolean {
        if (tempC <= 0f) return heat == ThermalMonitor.ThermalState.NORMAL
        return if (holding) {
            // A WARNING reading within the tolerance doesn't cancel: that only
            // happens when the user set resume + tolerance at or above warn.
            (heat == ThermalMonitor.ThermalState.NORMAL ||
                heat == ThermalMonitor.ThermalState.WARNING) &&
                tempC <= resumeC + TOLERANCE_C
        } else {
            heat == ThermalMonitor.ThermalState.NORMAL && tempC <= resumeC
        }
    }

    /** One tick of the hold. [holdingSinceMs] is 0 when not holding. */
    fun step(
        holdingSinceMs: Long,
        nowMs: Long,
        holdMs: Long,
        tempC: Float,
        resumeC: Float,
        heat: ThermalMonitor.ThermalState
    ): Step {
        val holding = holdingSinceMs != 0L
        if (!conditionsMet(holding, tempC, resumeC, heat)) {
            return if (holding) Step.CANCEL else Step.IDLE
        }
        if (!holding) return Step.START
        return if (nowMs - holdingSinceMs >= holdMs) Step.READY else Step.HOLD
    }
}
