# Grim relaxed/anarchy fork

This fork is aimed at servers where automation and non-vanilla movement behavior are acceptable, but sustained travel speed is not.

## Behavior

- Baritone is allowed.
- Scaffold / air place / rotation-place behavior is allowed.
- Grim's normal simulation/timer/no-fall/no-slow/sprint/elytra-state checks are disabled by the default `punishments/en.yml` profile.
- Packet/crash protections remain enabled.
- Reach/hitbox/aim/interact combat checks remain enabled by default.
- A new `SpeedLimit` check caps **sustained horizontal travel** at 40 blocks/second.
- The speed cap applies to normal player movement and client-driven vehicle movement, each state with its own configurable ceiling.
- Optional console commands can run when `SpeedLimit` flags (e.g. `nosavekick %player%`), globally or per movement tier.
- Server teleports are ignored.
- Vertical speed is ignored so legitimate long falls do not trigger the cap.

## Why source changes were needed

In stock Grim, omitting a check from `punishments.yml` stops punishment-group handling and packet modification, but the check can still call its normal flag/setback path. In this fork, `Check.recordFlag(...)` also requires the check to be enabled, so omitted checks are actually inactive.

## Speed configuration

In `config.yml`:

```yaml
SpeedLimit:
    # Everything else: walking, sprinting, swimming, Baritone, Meteor on ground, etc.
    max-horizontal-bps: 8.0
    # Vanilla/creative flight (ability-based flying, e.g. creative or /fly).
    max-horizontal-bps-flight: 8.0
    # Active elytra gliding. Wearing an elytra without gliding uses the walk tier.
    max-horizontal-bps-glide: 40.0
    # Vehicles: boat, minecart, horse, pig, etc. Omit or set to -1 to reuse the walk limit.
    max-horizontal-bps-vehicle: 12.0
    burst-seconds: 0.25
    # At most one vehicle-path flag (alert/log) per this many seconds.
    vehicle-alert-interval-seconds: 1.0
    # Optional console commands run when SpeedLimit flags (once per emitted flag,
    # dispatched by the console; a leading slash is optional).
    # Placeholders: %player% (alias %player_name%), %uuid%, %tier%.
    flag-commands: []
    # Per-tier alternatives, in addition to flag-commands:
    flag-commands-vehicle: []
    flag-commands-flight: []
    flag-commands-glide: []
    flag-commands-walk: []
    setbackvl: 0
```

The limiter uses a token bucket per movement state, with four independently configurable horizontal ceilings selected per movement update:

- `max-horizontal-bps` — everything else (walking, sprinting, swimming, Baritone pathing, and merely wearing an elytra without gliding).
- `max-horizontal-bps-flight` — vanilla/creative flight (ability-based flying).
- `max-horizontal-bps-glide` — active elytra gliding (Grim's compensated `isGliding` state; only true while actually gliding).
- `max-horizontal-bps-vehicle` — client-driven vehicle movement (boat-fly, ice-road boats, etc.). Backward compatible: if the key is missing or `-1`, the walk limit is used, so pre-vehicle-tier configs behave exactly as before.
- `vehicle-alert-interval-seconds` — throttle for SpeedLimit flag output on the VEHICLE_MOVE path. Sustained vehicle violations (e.g. boat-fly) send many packets per second; every violating packet is still cancelled AND triggers a real Grim setback (enforcement is not throttled), but alerts/logs/VL are emitted at most once per this interval. Default 1.0 s.

### Flag commands

`flag-commands` (and the per-tier `flag-commands-<tier>` lists) execute console commands whenever `SpeedLimit` actually emits a flag. This is the `nosavekick %player%` hook: a command on the server, run by console, when a player exceeds the speed cap in the chosen tier.

- Placeholders: `%player%` / `%player_name%` (player name), `%uuid%`, `%tier%` (`walk`, `vehicle`, `flight`, `elytra-glide`), plus everything Grim's placeholder machinery supports (including PlaceholderAPI when present).
- Commands run once per **emitted** flag. On the vehicle path, the flag is already throttled to one per `vehicle-alert-interval-seconds`, so commands are automatically rate-limited too (at most one execution per command per interval).
- A leading `/` is optional: `nosavekick %player%` and `/nosavekick %player%` are equivalent.
- Dispatch is via Grim's global region scheduler and the console sender, the same path Grim's own punishment commands use, so commands are thread-safe to run from the movement/packet threads.
- Execution cannot cause flag chains: it runs after the bucket reset/setback, independent of the limiter.

Example — kick boat-flyers via a server-side command:

```yaml
SpeedLimit:
    max-horizontal-bps-vehicle: 20.0
    flag-commands-vehicle:
        - "nosavekick %player%"
```

**Vehicle desync fix (v5):** cancelling a VEHICLE_MOVE packet alone is invisible to the client — the server drops it and never tells the client anything, so a boat-fly client keeps flying locally while the server (and observers) see it frozen, until vanilla Paper's floating-vehicle check kicks it. The vehicle path therefore now also calls `SetbackTeleportUtil.executeViolationSetback()` on every violating packet, which sends the stock dismount + vehicle-teleport + player-teleport sequence to the client so it actually perceives the correction (`blockMovementsUntilResync` self-throttles via `isPendingSetback`).

The three tiers use separate token buckets, and all buckets are reset whenever the tier changes, so allowance cannot be banked in a cheap tier and spent in an expensive one.

At 40 b/s with `burst-seconds: 0.25`, a one-off horizontal burst of up to roughly 10 blocks can be tolerated, but the bucket only refills at 40 blocks per real-world second. This makes the limiter substantially less sensitive to packet bunching, latency, knockback, and similar short transients.

For a somewhat more forgiving ceiling, try `burst-seconds: 0.50` (about a 20-block burst allowance). For an extremely strict per-tick-style cap, use `0.05` (about 2 blocks at 40 b/s), but that is more likely to react badly to lag or strong external velocity.

## Existing server configs

Grim generally does not replace an already-generated server config just because the jar/resource defaults changed. If this is installed over an existing Grim setup, copy the relevant `SpeedLimit` section into the live `config.yml` and update the live `punishments.yml` to match the relaxed profile.

## Build note

The ready-built Paper jar in [Releases](../../releases) is produced with the standard project Gradle build (Gradle 9.4.1, `./gradlew build -x test`) from this exact source tree; no manual steps are needed to deploy it.

## Verified environments

- **Paper 1.21.11** (live server, sessions 6–8): walk tier confirmed flagging in production; elytra-glide tier observed flagging at a 50 b/s cap; vehicle (boat-fly) path tested — flags throttled to ~1/s, setback teleport verified working from v5 on.
- **Paper 26.2-121** (clean boot test): plugin enables with zero errors, bundled packetevents 2.13.1 loads `V_26_2` mappings, default config generates with the 8/8/40/12 tier values, `grim help` / `grim reload` (full `SpeedLimit.onReload` path) run clean. Minecraft 26.2 = protocol 776; the 26.1/26.2 entity-metadata changes in upstream (already part of base `61caa53e`) are included.

The SpeedLimit check itself is version-independent packet math (horizontal `hypot(dx, dz)` token buckets), so behavior on 26.2 matches 1.21.x.
