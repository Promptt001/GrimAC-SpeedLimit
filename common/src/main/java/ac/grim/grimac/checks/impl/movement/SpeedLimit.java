package ac.grim.grimac.checks.impl.movement;


import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PositionListener;
import ac.grim.grimac.checks.type.PrePredictionPacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import ac.grim.grimac.utils.anticheat.update.PositionUpdate;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientVehicleMove;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * A deliberately simple travel-rate limiter for relaxed/anarchy servers.
 *
 * <p>This does not try to decide whether a movement is vanilla. It only limits
 * sustained horizontal displacement. A token bucket is used so short bursts
 * caused by latency, knockback, pistons, etc. have some tolerance while a
 * client cannot sustain travel above the configured blocks-per-second rate.</p>
 *
 * <p>Five independently configurable horizontal speed ceilings are applied
 * depending on the player's compensated movement state: vehicles (boat,
 * minecart, horse, etc.), boats supported by ice (v8 - lets vanilla ice-boat
 * highways run at full speed while a low general vehicle cap still stops
 * boat-fly), vanilla/creative flight, active elytra gliding, and everything
 * else (walking, sprinting, swimming, Baritone pathing, etc.). Merely wearing
 * an elytra without gliding falls into the "everything else" tier.</p>
 *
 * <p>The vehicle-ice tier is selected only when the riding entity is a boat
 * AND the compensated (server-authoritative, transaction-synced) world shows
 * an ice block directly under the boat hull. A boat-fly client in mid-air
 * never satisfies that condition - the server knows what blocks the client
 * was sent - so it stays under the low general vehicle cap.</p>
 *
 * <p>Optionally, console commands can be executed when a flag is emitted
 * ({@code SpeedLimit.flag-commands} for all tiers plus per-tier lists such as
 * {@code SpeedLimit.flag-commands-vehicle}). Placeholders: {@code %player%},
 * {@code %player_name%}, {@code %uuid%}, {@code %tier%}. Commands run once
 * per emitted flag; on the vehicle path the flag itself is throttled to
 * {@code vehicle-alert-interval-seconds}, which also throttles the commands.</p>
 */
@CheckData(
        name = "SpeedLimit",
        stableKey = "grim.movement.speed_limit",
        description = "Exceeded the configured horizontal travel speed",
        setback = 0,
        decay = 0.25
)
public class SpeedLimit extends Check implements PositionListener, PrePredictionPacketReceiveListener {
    private static final double EPSILON = 1.0E-7;

    /** Tier identifiers for configuration and debug output. */
    private static final String TIER_VEHICLE = "vehicle";
    private static final String TIER_VEHICLE_ICE = "vehicle-ice";
    private static final String TIER_FLIGHT = "flight";
    private static final String TIER_GLIDE = "elytra-glide";
    private static final String TIER_WALK = "walk";

    private final Bucket vehicleBucket = new Bucket();
    private final Bucket vehicleIceBucket = new Bucket();
    private final Bucket flightBucket = new Bucket();
    private final Bucket glideBucket = new Bucket();
    private final Bucket walkBucket = new Bucket();

    private double maxHorizontalBps;        // walk tier (existing key)
    private double maxHorizontalBpsVehicle; // vehicle tier (boats, minecarts, horses, ...)
    private double maxHorizontalBpsVehicleIce; // boat-on-ice tier (v8)
    private double maxHorizontalBpsFlight;  // vanilla/creative flight tier
    private double maxHorizontalBpsGlide;   // active elytra gliding tier
    private double burstSeconds;
    private double vehicleAlertIntervalSeconds;

    /** Console commands executed on every emitted flag, all tiers. */
    private List<String> flagCommands = new ArrayList<>();
    /** Additional console commands executed only for the named tier. */
    private List<String> flagCommandsVehicle = new ArrayList<>();
    private List<String> flagCommandsFlight = new ArrayList<>();
    private List<String> flagCommandsGlide = new ArrayList<>();
    private List<String> flagCommandsWalk = new ArrayList<>();

    /**
     * Timestamp (System.nanoTime) of the last emitted vehicle-path flag, for
     * alert throttling. 0 = never flagged on the vehicle path.
     *
     * <p>Vehicle movement is checked per VEHICLE_MOVE packet. A sustained
     * violation (e.g. boat-fly) can produce dozens of packets per second, and
     * resetting the bucket after each flag made every one of them a fresh
     * violation - observed as ~150 log lines in 8 seconds on the live server.
     * The vehicle path therefore (a) leaves the bucket starved after a flag
     * instead of resetting it, and (b) only emits a flag (alert/log/VL) at most
     * once per {@code vehicle-alert-interval-seconds}. Packet cancellation
     * still happens on every violating packet, so enforcement is unchanged.
     * The bucket recovers naturally once the client slows to under the tier
     * rate (or on teleport / tier transition / dismount resets).</p>
     */
    private long lastVehicleAlertNanos;



    /** Tier used by the previous accepted movement update. */
    @NotNull
    private String lastTier = TIER_WALK;

    public SpeedLimit(GrimPlayer player) {
        super(player);
        resetAllBuckets();
    }

    @Override
    public void onPositionUpdate(final PositionUpdate update) {
        if (!isEnabled()) {
            resetAllBuckets();
            return;
        }

        if (update.isTeleport()) {
            resetAllBuckets();
            return;
        }

        Vector3d from = update.getFrom();
        Vector3d to = update.getTo();
        double distance = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());

        final String tier = currentTier();
        final Bucket bucket = bucketFor(tier);

        // Reset on tier transitions so allowance cannot be banked in a cheap
        // tier and then spent in a faster one (e.g. walk a moment, glide fast).
        if (!tier.equals(lastTier)) {
            resetAllBuckets();
            lastTier = tier;
        }

        if (consume(bucket, distance)) {
            reward();
            return;
        }

        double capacity = bucketCapacity(tier);
        if (flagWithSetback(String.format(
                "tier=%s, horizontal=%.3f blocks, limit=%.1f b/s, burst=%.2fs (%.1f blocks)",
                tier, distance, rateFor(tier), burstSeconds, capacity))) {
            // The rejected movement will be corrected by Grim's setback. Give the
            // player a fresh bucket afterwards so the correction itself cannot
            // cause a chain of immediate flags.
            resetAllBuckets();
            executeFlagCommands(tier);
        }
    }

    @Override
    public void onPrePredictionPacketReceive(final PacketReceiveEvent event) {
        if (!isEnabled()) {
            resetAllBuckets();
            return;
        }

        // Player movement is handled by onPositionUpdate. Vehicles need to be
        // checked here because VehiclePositionUpdate does not expose the previous
        // vehicle position in a useful way for a rate limiter.
        if (event.getPacketType() != PacketType.Play.Client.VEHICLE_MOVE || !player.inVehicle()) {
            return;
        }

        if (player.packetStateData.lastPacketWasTeleport) {
            resetAllBuckets();
            return;
        }

        WrapperPlayClientVehicleMove move = new WrapperPlayClientVehicleMove(event);
        Vector3d to = move.getPosition();
        double distance = Math.hypot(to.getX() - player.x, to.getZ() - player.z);

        // Vehicles use their own tier: boats/minecarts/horses legitimately move
        // faster than walking, so the owner may want a different ceiling here.
        // A boat supported by server-known ice gets the higher vehicle-ice
        // ceiling (v8) so vanilla ice-boat highways are unrestricted; anything
        // else - including boat-fly hovering above the ice - stays under the
        // general vehicle cap.
        final String tier = vehicleTierFor(to);
        final Bucket bucket = bucketFor(tier);
        if (!tier.equals(lastTier)) {
            resetAllBuckets();
            lastTier = tier;
        }

        final long now = System.nanoTime();

        if (consume(bucket, distance)) {
            reward();
            return;
        }

        // Cancel EVERY violating vehicle move packet AND resync the client.
        // Enforcement must not be throttled; only the flag/alert/log output is
        // throttled below, so sustained boat-fly produces a once-per-interval
        // heartbeat instead of one log line per packet.
        //
        // Cancellation alone is invisible to the client: the server simply
        // drops the packet, never moves the player, and never sends anything
        // back. The client keeps flying locally while the server (and every
        // other player) sees it frozen in place, until vanilla Paper's
        // floating-vehicle check kicks it (observed live: "NetherSage was
        // kicked for floating a vehicle too long"). The fix is to also ask
        // Grim's setback machinery to teleport the player (and their vehicle)
        // back to the last known good position - the same path VehicleTimer
        // uses. blockMovementsUntilResync() already guards against setback
        // spam via isPendingSetback(), and its vehicle branch sends the
        // dismount + vehicle teleport + player teleport packets the client
        // needs to actually perceive the correction.
        if (shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
            player.getSetbackTeleportUtil().executeViolationSetback();
        }

        // Leave the bucket starved (no resetAllBuckets here). A reset would
        // grant a fresh burst allowance after every flag, turning each packet
        // into a new violation and flooding the log. The bucket refills
        // naturally at the tier rate, so a client that slows below the cap
        // stops violating on its own.

        if (lastVehicleAlertNanos != 0L
                && (now - lastVehicleAlertNanos) / 1_000_000_000.0 < vehicleAlertIntervalSeconds) {
            return;
        }
        lastVehicleAlertNanos = now;

        double capacity = bucketCapacity(tier);
        if (flag(String.format(
                "vehicle tier=%s, horizontal=%.3f blocks, limit=%.1f b/s, burst=%.2fs (%.1f blocks), alerts <= 1/%.1fs",
                tier, distance, rateFor(tier), burstSeconds, capacity, vehicleAlertIntervalSeconds))) {
            // Runs at most once per vehicle-alert-interval-seconds because the
            // flag itself is throttled above.
            executeFlagCommands(tier);
        }
    }

    /**
     * Executes the configured console commands ({@code SpeedLimit.flag-commands}
     * plus the per-tier {@code flag-commands-<tier>} list) for a flag that was
     * actually emitted.
     *
     * <p>Commands are dispatched by the console on the global region scheduler,
     * the same way Grim's own punishment commands run. Supported placeholders:
     * {@code %player%} / {@code %player_name%} (player name), {@code %uuid%},
     * {@code %tier%}, plus everything {@link MessageUtil#replacePlaceholders}
     * supports (including PlaceholderAPI when present). A leading slash is
     * stripped so both {@code nosavekick %player%} and
     * {@code /nosavekick %player%} work.</p>
     */
    private void executeFlagCommands(final String tier) {
        final List<String> commands = new ArrayList<>(flagCommands.size() + 4);
        commands.addAll(flagCommands);
        commands.addAll(commandsFor(tier));
        if (commands.isEmpty()) {
            return;
        }

        final String playerName = player.getName() != null ? player.getName() : "";
        final String uuid = player.getUniqueId() != null ? player.getUniqueId().toString() : "";

        for (String raw : commands) {
            try {
                String command = raw.trim();
                if (command.startsWith("/")) {
                    command = command.substring(1);
                }
                if (command.isEmpty()) {
                    continue;
                }

                // %player_name% is an alias some anticheats use; Grim's own
                // placeholder machinery spells it %player%.
                command = command.replace("%player_name%", playerName)
                        .replace("%player%", playerName)
                        .replace("%uuid%", uuid)
                        .replace("%tier%", tier);
                final String toRun = MessageUtil.replacePlaceholders(player, command);

                GrimAPI.INSTANCE.getScheduler().getGlobalRegionScheduler().run(GrimAPI.INSTANCE.getGrimPlugin(), () ->
                        GrimAPI.INSTANCE.getPlatformServer().dispatchCommand(
                                GrimAPI.INSTANCE.getPlatformServer().getConsoleSender(),
                                toRun
                        )
                );
            } catch (Exception e) {
                LogUtil.error("SpeedLimit: failed to queue flag command '" + raw + "'", e);
            }
        }
    }

    private List<String> commandsFor(String tier) {
        switch (tier) {
            case TIER_VEHICLE:
            case TIER_VEHICLE_ICE: // ice boats are still vehicles; run the vehicle list
                return flagCommandsVehicle;
            case TIER_GLIDE:
                return flagCommandsGlide;
            case TIER_FLIGHT:
                return flagCommandsFlight;
            default:
                return flagCommandsWalk;
        }
    }

    /**
     * Selects the speed tier for the current movement update.
     *
     * <p>Order matters. A player in a vehicle is never in the flight or glide
     * tier. Gliding is checked before flight: while elytra gliding, Grim
     * tracks isGliding, and isFlying is only meaningful for ability-based
     * flight. Wearing an elytra without actively gliding does not set
     * isGliding, so such players fall into the walk tier.</p>
     */
    private String currentTier() {
        if (player.inVehicle()) {
            return TIER_VEHICLE;
        }
        if (player.isGliding) {
            return TIER_GLIDE;
        }
        if (player.isFlying) {
            return TIER_FLIGHT;
        }
        return TIER_WALK;
    }

    private Bucket bucketFor(String tier) {
        switch (tier) {
            case TIER_VEHICLE:
                return vehicleBucket;
            case TIER_VEHICLE_ICE:
                return vehicleIceBucket;
            case TIER_GLIDE:
                return glideBucket;
            case TIER_FLIGHT:
                return flightBucket;
            default:
                return walkBucket;
        }
    }

    /**
     * Chooses between the general vehicle tier and the boat-on-ice tier for a
     * VEHICLE_MOVE packet.
     *
     * <p>The ice tier requires (a) the riding entity to be a boat, and (b) an
     * ice block (ice / packed ice / blue ice / frosted ice) intersecting the
     * thin slab just under the boat hull, according to the compensated world.
     * The compensated world is built from the block-change packets the server
     * actually sent the client and is transaction-synced, so a modified
     * client cannot claim to be on ice it is not standing on. A boat-fly
     * hovering in the air sees air under the hull and is therefore checked
     * against the low general vehicle limit.</p>
     *
     * <p>Vanilla physics: a boat on ice reaches roughly 40 b/s, on blue ice
     * roughly 72.73 b/s, so the default vehicle-ice ceiling of 80.0 gives
     * legit ice-boat highways a little headroom while keeping a far lower
     * general vehicle cap meaningful.</p>
     */
    private String vehicleTierFor(final Vector3d vehiclePos) {
        final PacketEntity riding = player.compensatedEntities.self.getRiding();
        if (riding == null || !riding.isBoat) {
            return TIER_VEHICLE;
        }

        // Hull footprint: same box Grim's own boat prediction uses
        // (GetBoundingBox.getPacketEntityBoundingBox), shrunk to a slab just
        // under the bottom of the hull so a boat hovering above ice does not
        // count as being on it. A floating boat is 0.625 blocks above the
        // water surface, and a grounded boat hull bottom sits at the boat's
        // position Y - sampling from minY-0.2 down to minY+0.05 catches both
        // a grounded hull and (importantly) the slightly-submerged position
        // a boat has while planing on ice.
        final SimpleCollisionBox hull = GetBoundingBox.getPacketEntityBoundingBox(
                player, vehiclePos.getX(), vehiclePos.getY(), vehiclePos.getZ(), riding);
        final int minX = (int) Math.floor(hull.minX);
        final int maxX = (int) Math.floor(hull.maxX - EPSILON);
        final int minY = (int) Math.floor(hull.minY - 0.2);
        final int maxY = (int) Math.floor(hull.minY + 0.05);
        final int minZ = (int) Math.floor(hull.minZ);
        final int maxZ = (int) Math.floor(hull.maxZ - EPSILON);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    final StateType block = player.compensatedWorld.getBlockType(x, y, z);
                    if (block == StateTypes.ICE
                            || block == StateTypes.PACKED_ICE
                            || block == StateTypes.BLUE_ICE
                            || block == StateTypes.FROSTED_ICE) {
                        return TIER_VEHICLE_ICE;
                    }
                }
            }
        }
        return TIER_VEHICLE;
    }

    private double rateFor(String tier) {
        switch (tier) {
            case TIER_VEHICLE:
                return maxHorizontalBpsVehicle;
            case TIER_VEHICLE_ICE:
                return maxHorizontalBpsVehicleIce;
            case TIER_GLIDE:
                return maxHorizontalBpsGlide;
            case TIER_FLIGHT:
                return maxHorizontalBpsFlight;
            default:
                return maxHorizontalBps;
        }
    }

    /**
     * Consume horizontal distance from the given tier's token bucket,
     * refilling it first based on elapsed time.
     */
    private boolean consume(final Bucket bucket, double horizontalDistance) {
        if (!Double.isFinite(horizontalDistance) || horizontalDistance < 0) {
            return false;
        }

        final long now = System.nanoTime();
        final String tier = bucket == vehicleBucket ? TIER_VEHICLE
                : bucket == vehicleIceBucket ? TIER_VEHICLE_ICE
                : bucket == glideBucket ? TIER_GLIDE
                : bucket == flightBucket ? TIER_FLIGHT
                : TIER_WALK;
        final double capacity = bucketCapacity(tier);

        if (bucket.lastRefillNanos == 0L) {
            bucket.allowance = capacity;
            bucket.lastRefillNanos = now;
        } else {
            double elapsedSeconds = Math.max(0.0, (now - bucket.lastRefillNanos) / 1_000_000_000.0);
            bucket.allowance = Math.min(capacity, bucket.allowance + elapsedSeconds * rateFor(tier));
            bucket.lastRefillNanos = now;
        }

        if (horizontalDistance <= bucket.allowance + EPSILON) {
            bucket.allowance = Math.max(0.0, bucket.allowance - horizontalDistance);
            return true;
        }

        bucket.allowance = 0.0;
        return false;
    }

    private double bucketCapacity(String tier) {
        return Math.max(rateFor(tier) * burstSeconds, rateFor(tier) / 20.0);
    }

    private void resetAllBuckets() {
        // The Check base class calls reload()/onReload() from its constructor,
        // which runs BEFORE this subclass's field initializers. At that point
        // the Bucket fields are still null - treat the premature reset as a
        // no-op. The constructor's own resetAllBuckets() call (after super())
        // performs the real initial fill using the already-loaded rates.
        if (vehicleBucket == null || vehicleIceBucket == null || flightBucket == null || glideBucket == null || walkBucket == null) {
            return;
        }
        resetBucket(vehicleBucket, TIER_VEHICLE);
        resetBucket(vehicleIceBucket, TIER_VEHICLE_ICE);
        resetBucket(flightBucket, TIER_FLIGHT);
        resetBucket(glideBucket, TIER_GLIDE);
        resetBucket(walkBucket, TIER_WALK);
    }

    private void resetBucket(Bucket bucket, String tier) {
        bucket.allowance = bucketCapacity(tier);
        bucket.lastRefillNanos = System.nanoTime();
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        maxHorizontalBps = Math.max(1.0, config.getDoubleElse("SpeedLimit.max-horizontal-bps", 8.0));
        maxHorizontalBpsFlight = Math.max(1.0, config.getDoubleElse("SpeedLimit.max-horizontal-bps-flight", 8.0));
        maxHorizontalBpsGlide = Math.max(1.0, config.getDoubleElse("SpeedLimit.max-horizontal-bps-glide", 40.0));
        // Missing/negative vehicle key falls back to the walk limit, so existing
        // configs (pre-vehicle-tier) behave exactly as before.
        final double vehicleBps = config.getDoubleElse("SpeedLimit.max-horizontal-bps-vehicle", -1.0);
        maxHorizontalBpsVehicle = vehicleBps > 0.0 ? Math.max(1.0, vehicleBps) : maxHorizontalBps;
        // Boat-on-ice tier (v8). Absent/negative falls back to the general
        // vehicle limit so pre-v8 configs behave exactly as before.
        final double vehicleIceBps = config.getDoubleElse("SpeedLimit.max-horizontal-bps-vehicle-ice", -1.0);
        maxHorizontalBpsVehicleIce = vehicleIceBps > 0.0 ? Math.max(1.0, vehicleIceBps) : maxHorizontalBpsVehicle;
        burstSeconds = Math.max(0.05, config.getDoubleElse("SpeedLimit.burst-seconds", 0.25));
        vehicleAlertIntervalSeconds = Math.max(0.05, config.getDoubleElse("SpeedLimit.vehicle-alert-interval-seconds", 1.0));
        flagCommands = commandList(config, "SpeedLimit.flag-commands");
        flagCommandsVehicle = commandList(config, "SpeedLimit.flag-commands-vehicle");
        flagCommandsFlight = commandList(config, "SpeedLimit.flag-commands-flight");
        flagCommandsGlide = commandList(config, "SpeedLimit.flag-commands-glide");
        flagCommandsWalk = commandList(config, "SpeedLimit.flag-commands-walk");
        resetAllBuckets();
    }

    /** Reads a command list from config, tolerating absent/null entries. */
    private static List<String> commandList(@NotNull ConfigManager config, String key) {
        List<String> out = new ArrayList<>();
        try {
            for (String entry : config.getStringListElse(key, new ArrayList<>())) {
                if (entry == null) {
                    continue;
                }
                String trimmed = entry.trim();
                if (trimmed.startsWith("/")) {
                    trimmed = trimmed.substring(1);
                }
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
            }
        } catch (Exception e) {
            LogUtil.error("SpeedLimit: failed to read " + key + " from config", e);
        }
        return out;
    }

    /** Simple mutable token bucket state for one movement tier. */
    private static final class Bucket {
        double allowance;
        long lastRefillNanos;
    }
}
