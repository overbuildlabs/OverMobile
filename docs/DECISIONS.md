# Decisions

Short, append-only records of decisions and why. Newest at the bottom.
Don't edit an old entry; add a new one that supersedes it.

## 2026-10-21 · Pauses carry a reason

**Decision:** `MiningService` tracks why the engine is paused
(`PauseReason`: heat, battery, unplugged) instead of one `thermalPaused`
flag, and acts on heat-only severity (`DeviceStatus.heatState`). The
combined `thermalState` (heat + low battery) stays for display and
telemetry.

**Why:** with one flag, each pause cause's resume path ignored the others:
an unplugged pause "resumed" after the thermal cool-down and re-paused in
the same tick every 30 s, and a battery pause couldn't resume in the
battery WARNING band while charging (roadmap R5).

**Consequences:** battery pauses resume at cutoff + 5 %; the battery cutoff
still applies only with thermal protection on and outside external-power
mode (unchanged scope).

## 2026-10-21 · Accepted shares come from pool acks

**Decision:** the dashboard, notification and telemetry `acceptedShares`
use a Kotlin counter incremented on the pool's accept response, not the
native shares-found counter. The telemetry field name is unchanged.

**Why:** the native counter includes rejected shares and shares lost on a
disconnect.
