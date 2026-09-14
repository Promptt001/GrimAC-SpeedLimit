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
import ac.grim.grimac.utils.data.packetentity.PacketEntityCamel;
import ac.grim.grimac.utils.data.packetentity.PacketEntityNautilus;
import ac.grim.grimac.utils.data.packetentity.PacketEntityStrider;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.enchantment.type.EnchantmentTypes;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientVehicleMove;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A deliberately simple travel-rate limiter for relaxed/anarchy servers.
 *
 * <p>This does not try to decide whether a movement is vanilla. It only limits
 * sustained horizontal displacement. A token bucket is used so short bursts
 * caused by latency, knockback, pistons, etc. have some tolerance while a
 * client cannot sustain travel above the configured blocks-per-second rate.</p>
 *
 * <p>Independently configurable horizontal speed ceilings are applied
 * depending on the player's compensated movement state: vanilla/creative
 * flight, active elytra gliding, everything else (walking, sprinting,
 * swimming, Baritone pathing, etc. - merely wearing an elytra without gliding
 * falls here), and a family of vehicle tiers. Vehicles are tiered per entity
 * type (v9 - boat, horse, camel, minecart, pig, strider, happy ghast,
 * nautilus, each defaulting to the fastest speed that vehicle type can
 * legitimately reach in vanilla) plus a general vehicle fallback for anything
 * else. Boats supported by ice get their own higher tier (v8 - lets vanilla
 * ice-boat highways run at full speed while a low boat cap still stops
 * boat-fly).</p>
 *
 * <p>Two additional tiers cover server-granted momentum bursts (v10): the
 * riptide tier for trident launches (gated on the transaction-synced spin
 * attack pose / launch attempt, default 60 bps = Riptide III launch speed) and
 * the lunge tier for 1.21.11 spear-Lunge jabs (a short window opened by an
 * attack while holding a Lunge spear per the compensated inventory, default
 * 28 bps = Lunge III jab speed). Both fall back to the walk limit when
 * unconfigured.</p>
 *
 * <p>The vehicle-ice tier is selected only when the riding entity is a boat
 * AND the compensated (server-authoritative, transaction-synced) world shows
 * an ice block directly under the boat hull. A boat-fly client in mid-air
 * never satisfies that condition - the server knows what blocks the client
 * was sent - so it stays under the low boat cap (vehicle-boat).</p>
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
    private static final String TIER_VEHICLE_BOAT = "vehicle-boat";
    private static final String TIER_VEHICLE_HORSE = "vehicle-horse";
    private static final String TIER_VEHICLE_CAMEL = "vehicle-camel";
    private static final String TIER_VEHICLE_MINECART = "vehicle-minecart";
    private static final String TIER_VEHICLE_PIG = "vehicle-pig";
    private static final String TIER_VEHICLE_STRIDER = "vehicle-strider";
    private static final String TIER_VEHICLE_GHAST = "vehicle-ghast";
    private static final String TIER_VEHICLE_NAUTILUS = "vehicle-nautilus";
    private static final String TIER_FLIGHT = "flight";
    private static final String TIER_GLIDE = "elytra-glide";
    private static final String TIER_RIPTIDE = "riptide";
    private static final String TIER_LUNGE = "lunge";
    private static final String TIER_WALK = "walk";

    /**
     * One token bucket per tier, keyed by tier name (v9: the vehicle family
     * grew to ten tiers, so a lazily-filled map replaces the old per-field
     * buckets). Buckets are created by {@link #bucketFor(String)}.
     *
     * <p>NOTE: the Check base class calls reload()/onReload() from its
     * constructor, BEFORE this subclass's field initializers run - at that
     * point this map is still null, and resetAllBuckets() treats that as a
     * no-op.</p>
     */
    private final Map<String, Bucket> buckets = new HashMap<>();

    private double maxHorizontalBps;        // walk tier (existing key)
    private double maxHorizontalBpsVehicle; // vehicle tier (boats, minecarts, horses, ...)
    private double maxHorizontalBpsVehicleIce; // boat-on-ice tier (v8)
    private double maxHorizontalBpsVehicleBoat;     // boat on water/land (v9)
    private double maxHorizontalBpsVehicleHorse;    // horse/donkey/mule/undead horses (v9)
    private double maxHorizontalBpsVehicleCamel;    // camel, incl. dash bursts (v9)
    private double maxHorizontalBpsVehicleMinecart; // minecart (v9)
    private double maxHorizontalBpsVehiclePig;      // saddled pig w/ carrot on a stick (v9)
    private double maxHorizontalBpsVehicleStrider;  // strider on lava (v9)
    private double maxHorizontalBpsVehicleGhast;    // happy ghast (v9)
    private double maxHorizontalBpsVehicleNautilus; // nautilus, incl. zombie nautilus (v9)
    private double maxHorizontalBpsFlight;  // vanilla/creative flight tier
    private double maxHorizontalBpsGlide;   // active elytra gliding tier
    private double maxHorizontalBpsRiptide; // riptide spin-attack launch tier (v10)
    private double maxHorizontalBpsLunge;   // spear-Lunge jab burst tier (v10)
    private double burstSeconds;
    private double vehicleAlertIntervalSeconds;
    private long lungeWindowMillis;         // how long a Lunge jab keeps the lunge tier active (v10)

    /** Console commands executed on every emitted flag, all tiers. */
    private List<String> flagCommands = new ArrayList<>();
    /** Additional console commands executed only for the named tier. */
    private List<String> flagCommandsVehicle = new ArrayList<>();
    private List<String> flagCommandsFlight = new ArrayList<>();
    private List<String> flagCommandsGlide = new ArrayList<>();
    private List<String> flagCommandsRiptide = new ArrayList<>();
    private List<String> flagCommandsLunge = new ArrayList<>();
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

    /**
     * Timestamp (System.nanoTime) of the last accepted Lunge jab attack.
     * 0 = no Lunge jab seen yet (or the window has been allowed to lapse).
     */
    private long lastLungeAttackNanos;



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

        if (consume(bucket, tier, distance)) {
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

        // Spear-Lunge jabs (1.21.11 Mounts of Mayhem) apply an instant horizontal
        // momentum boost to the wielder (0.458 blocks/tick per Lunge level, per
        // vanilla) whenever a jab attack is performed. The boost is applied by the
        // vanilla server itself, but the resulting client movement shows up as a
        // walk-tier rate spike that the token bucket rejects, producing the
        // rubber-banding testers reported. Detect a jab here - ATTACK packet or
        // INTERACT_ENTITY with the ATTACK action - while the compensated (server-
        // known) held item is a Lunge spear, and open a short window during which
        // the lunge tier applies so the server-granted momentum is not flagged.
        // Both the held item and the attack are things the client cannot usefully
        // spoof: the item is tracked from the server's own slot packets, and an
        // attack packet without a real server-side jab produces no momentum. A
        // cheater spamming attack packets to hold the lunge tier open gains only
        // the lunge-tier ceiling while attacking (still rate-capped), and vanilla
        // limits Lunge activation to a successful hit landing on an entity, so the
        // window only opens for attacks the server actually processes.
        if (isAttackPacket(event)) {
            maybeOpenLungeWindow(event);
            // fall through - a Lunge jab while mounted is impossible (vanilla
            // disables Lunge when riding), so vehicle handling below is unaffected.
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

        // Vehicles are tiered per entity type (v9): each rideable type gets
        // its own ceiling defaulting to the fastest that type can reach in
        // vanilla. A boat supported by server-known ice gets the higher
        // vehicle-ice ceiling (v8) so vanilla ice-boat highways are
        // unrestricted; anything else - including boat-fly hovering above the
        // ice - stays under its type's cap (vehicle-boat for boats) or the
        // general vehicle fallback.
        final String tier = vehicleTierFor(to);
        final Bucket bucket = bucketFor(tier);
        if (!tier.equals(lastTier)) {
            resetAllBuckets();
            lastTier = tier;
        }

        final long now = System.nanoTime();

        if (consume(bucket, tier, distance)) {
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
            // All vehicle-family tiers run the flag-commands-vehicle list.
            case TIER_VEHICLE:
            case TIER_VEHICLE_ICE:
            case TIER_VEHICLE_BOAT:
            case TIER_VEHICLE_HORSE:
            case TIER_VEHICLE_CAMEL:
            case TIER_VEHICLE_MINECART:
            case TIER_VEHICLE_PIG:
            case TIER_VEHICLE_STRIDER:
            case TIER_VEHICLE_GHAST:
            case TIER_VEHICLE_NAUTILUS:
                return flagCommandsVehicle;
            case TIER_GLIDE:
                return flagCommandsGlide;
            case TIER_RIPTIDE:
                return flagCommandsRiptide;
            case TIER_LUNGE:
                return flagCommandsLunge;
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
        // Riptide (v10): a trident launch applies 3x(1+level)/4 blocks/tick of
        // velocity in one burst (60 bps at Riptide III). Two transaction-synced
        // signals cover it: the client's own shared-entity flags pose bit
        // (isRiptidePose, set from self metadata the server echoes) is authoritative
        // for the ~20-tick spin attack; tryingToRiptide covers the launch tick
        // before the pose flips, and the prediction engine clears it within one
        // movement tick if it is bogus (it re-validates the water/rain/450ms
        // conditions on the next processed movement). Riptide is impossible while
        // gliding or riding, so ordering relative to those tiers does not matter.
        if (player.isRiptidePose || player.packetStateData.tryingToRiptide) {
            return TIER_RIPTIDE;
        }
        if (player.isGliding) {
            return TIER_GLIDE;
        }
        if (player.isFlying) {
            return TIER_FLIGHT;
        }
        if (lungeWindowActive()) {
            return TIER_LUNGE;
        }
        return TIER_WALK;
    }

    /**
     * Whether the configured Lunge window is currently open, i.e. a Lunge jab
     * attack was accepted recently enough that the resulting momentum may still
     * be carrying the player at above-walk speed.
     */
    private boolean lungeWindowActive() {
        return lastLungeAttackNanos != 0L
                && (System.nanoTime() - lastLungeAttackNanos) / 1_000_000.0 < lungeWindowMillis;
    }

    /**
     * True when this packet is an entity attack (the modern ATTACK packet, or
     * INTERACT_ENTITY with the ATTACK action as older/other clients send).
     */
    private static boolean isAttackPacket(final PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
            return true;
        }
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            try {
                return new WrapperPlayClientInteractEntity(event).getAction()
                        == WrapperPlayClientInteractEntity.InteractAction.ATTACK;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    /**
     * Opens the lunge window when the player performs an attack while holding a
     * spear enchanted with Lunge (per the compensated inventory - server-known
     * item contents). The window is only opened when the attack targets an
     * entity the server has actually spawned (compensated entity map), since
     * vanilla requires a successful hit on an entity for Lunge to activate.
     */
    private void maybeOpenLungeWindow(final PacketReceiveEvent event) {
        // getHeldItem() never returns null (falls back to ItemStack.EMPTY, whose
        // type is AIR - rejected by isSpear), so no null handling is needed.
        final ItemStack held = player.inventory.getHeldItem();
        if (!isSpear(held.getType()) || held.getEnchantmentLevel(EnchantmentTypes.LUNGE) <= 0) {
            return;
        }

        final int targetEntityId = getAttackTargetEntityId(event);
        if (targetEntityId < 0
                || player.compensatedEntities.entityMap.get(targetEntityId) == null) {
            return;
        }

        lastLungeAttackNanos = System.nanoTime();
    }

    /** Entity id targeted by an attack packet, or -1 when unavailable. */
    private static int getAttackTargetEntityId(final PacketReceiveEvent event) {
        try {
            if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
                return new WrapperPlayClientAttack(event).getEntityId();
            }
            return new WrapperPlayClientInteractEntity(event).getEntityId();
        } catch (Exception e) {
            return -1;
        }
    }

    /** All seven spear material tiers added in 1.21.11 Mounts of Mayhem. */
    private static boolean isSpear(final ItemType type) {
        return type == ItemTypes.WOODEN_SPEAR || type == ItemTypes.STONE_SPEAR
                || type == ItemTypes.COPPER_SPEAR || type == ItemTypes.IRON_SPEAR
                || type == ItemTypes.GOLDEN_SPEAR || type == ItemTypes.DIAMOND_SPEAR
                || type == ItemTypes.NETHERITE_SPEAR;
    }

    private Bucket bucketFor(String tier) {
        return buckets.computeIfAbsent(tier, t -> new Bucket());
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
        if (riding == null) {
            return TIER_VEHICLE;
        }

        // Per-vehicle-type tiers (v9). Type is taken from the compensated
        // entity (built from the server's own spawn/metadata packets), so a
        // modified client cannot claim to be riding a faster vehicle type
        // than it actually is.
        if (!riding.isBoat) {
            // Non-boat vehicles: pick the tier for the entity type. Note that
            // camels extend PacketEntityHorse, so the camel check must come
            // before the general horse check. Unknown/other rideables fall
            // back to the general vehicle tier.
            if (riding.isHappyGhast) {
                return TIER_VEHICLE_GHAST;
            }
            if (riding.isMinecart) {
                return TIER_VEHICLE_MINECART;
            }
            if (riding instanceof PacketEntityCamel) {
                return TIER_VEHICLE_CAMEL;
            }
            if (riding.isHorse) {
                return TIER_VEHICLE_HORSE;
            }
            if (riding instanceof PacketEntityNautilus) {
                return TIER_VEHICLE_NAUTILUS;
            }
            if (riding instanceof PacketEntityStrider) {
                return TIER_VEHICLE_STRIDER;
            }
            if (riding.getType() == EntityTypes.PIG) {
                return TIER_VEHICLE_PIG;
            }
            return TIER_VEHICLE;
        }
        // Boats: fall through to the ice scan below, which decides between
        // the vehicle-ice tier (boat supported by ice) and vehicle-boat.

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
        // Boat not on ice: water/land boat tier.
        return TIER_VEHICLE_BOAT;
    }

    private double rateFor(String tier) {
        switch (tier) {
            case TIER_VEHICLE_ICE:
                return maxHorizontalBpsVehicleIce;
            case TIER_VEHICLE_BOAT:
                return maxHorizontalBpsVehicleBoat;
            case TIER_VEHICLE_HORSE:
                return maxHorizontalBpsVehicleHorse;
            case TIER_VEHICLE_CAMEL:
                return maxHorizontalBpsVehicleCamel;
            case TIER_VEHICLE_MINECART:
                return maxHorizontalBpsVehicleMinecart;
            case TIER_VEHICLE_PIG:
                return maxHorizontalBpsVehiclePig;
            case TIER_VEHICLE_STRIDER:
                return maxHorizontalBpsVehicleStrider;
            case TIER_VEHICLE_GHAST:
                return maxHorizontalBpsVehicleGhast;
            case TIER_VEHICLE_NAUTILUS:
                return maxHorizontalBpsVehicleNautilus;
            case TIER_VEHICLE:
                return maxHorizontalBpsVehicle;
            case TIER_GLIDE:
                return maxHorizontalBpsGlide;
            case TIER_RIPTIDE:
                return maxHorizontalBpsRiptide;
            case TIER_LUNGE:
                return maxHorizontalBpsLunge;
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
    private boolean consume(final Bucket bucket, final String tier, double horizontalDistance) {
        if (!Double.isFinite(horizontalDistance) || horizontalDistance < 0) {
            return false;
        }

        final long now = System.nanoTime();
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
        // the bucket map is still null - treat the premature reset as a
        // no-op. The constructor's own resetAllBuckets() call (after super())
        // performs the real initial fill using the already-loaded rates.
        if (buckets == null || buckets.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
            resetBucket(entry.getValue(), entry.getKey());
        }
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
        // Per-vehicle-type tiers (v9). Defaults are the fastest speed each
        // vehicle type can legitimately reach in vanilla, with a little
        // headroom for friction/potion variance; absent/-1 falls back to the
        // general vehicle limit so pre-v9 configs behave exactly as before.
        // Vanilla maxima: horse 14.23 bps (fastest breed, incl. Speed potion
        // headroom -> 16.0), boat flat water 8.0 bps, minecart powered rail
        // 8.0 bps, pig w/ carrot on a stick ~4.19 bps (bursts -> 6.0),
        // strider on lava w/ boost ~7.34 bps, camel dash burst ~10 bps,
        // happy ghast ~3.6 bps, nautilus w/ dash ~7.15 bps.
        maxHorizontalBpsVehicleBoat = vehicleTypeBps(config, "SpeedLimit.max-horizontal-bps-vehicle-boat", 12.0, maxHorizontalBpsVehicle);
        maxHorizontalBpsVehicleHorse = vehicleTypeBps(config, "SpeedLimit.max-horizontal-bps-vehicle-horse", 16.0, maxHorizontalBpsVehicle);
        maxHorizontalBpsVehicleCamel = vehicleTypeBps(config, "SpeedLimit.max-horizontal-bps-vehicle-camel", 10.0, maxHorizontalBpsVehicle);
        maxHorizontalBpsVehicleMinecart = vehicleTypeBps(config, "SpeedLimit.max-horizontal-bps-vehicle-minecart", 9.0, maxHorizontalBpsVehicle);
        maxHorizontalBpsVehiclePig = vehicleTypeBps(config, "SpeedLimit.max-horizontal-bps-vehicle-pig", 6.0, maxHorizontalBpsVehicle);
        maxHorizontalBpsVehicleStrider = vehicleTypeBps(config, "SpeedLimit.max-horizontal-bps-vehicle-strider", 8.0, maxHorizontalBpsVehicle);
        maxHorizontalBpsVehicleGhast = vehicleTypeBps(config, "SpeedLimit.max-horizontal-bps-vehicle-ghast", 5.0, maxHorizontalBpsVehicle);
        maxHorizontalBpsVehicleNautilus = vehicleTypeBps(config, "SpeedLimit.max-horizontal-bps-vehicle-nautilus", 8.0, maxHorizontalBpsVehicle);
        // Riptide tier (v10). A Riptide III launch applies 3 blocks/tick of
        // velocity (60 bps) in one burst; the sustained travel speed is far lower
        // as air drag decays the momentum, so 60.0 with the shared burst-seconds
        // allowance (0.25s -> 15-block capacity) tolerates the launch tick without
        // insta-flagging. The wiki's ~375 m/s figure is stacked ice/Depth
        // Strider/Dolphin's Grace chains, not the bare launch. Absent/-1 falls
        // back to the walk limit so pre-v10 configs behave exactly as before.
        final double riptideBps = config.getDoubleElse("SpeedLimit.max-horizontal-bps-riptide", -1.0);
        maxHorizontalBpsRiptide = riptideBps > 0.0 ? Math.max(1.0, riptideBps) : maxHorizontalBps;
        // Lunge tier (v10). A Lunge III jab applies 1.374 blocks/tick (27.48 bps)
        // of horizontal momentum; 28.0 default with headroom for the follow-through
        // of successive ticks as the boost decays. Absent/-1 -> walk limit.
        final double lungeBps = config.getDoubleElse("SpeedLimit.max-horizontal-bps-lunge", -1.0);
        maxHorizontalBpsLunge = lungeBps > 0.0 ? Math.max(1.0, lungeBps) : maxHorizontalBps;
        // Window during which the lunge tier applies after a jab; the momentum
        // decays within ~10 ticks (0.5s) on the ground and a few more midair, and
        // jabs are cooldown-gated (fastest spear ~1.5/s), so 2.0s covers the whole
        // decay plus the next jab without letting the tier linger indefinitely.
        lungeWindowMillis = (long) Math.max(100.0, config.getDoubleElse("SpeedLimit.lunge-window-seconds", 2.0) * 1000.0);
        burstSeconds = Math.max(0.05, config.getDoubleElse("SpeedLimit.burst-seconds", 0.25));
        vehicleAlertIntervalSeconds = Math.max(0.05, config.getDoubleElse("SpeedLimit.vehicle-alert-interval-seconds", 1.0));
        flagCommands = commandList(config, "SpeedLimit.flag-commands");
        flagCommandsVehicle = commandList(config, "SpeedLimit.flag-commands-vehicle");
        flagCommandsFlight = commandList(config, "SpeedLimit.flag-commands-flight");
        flagCommandsGlide = commandList(config, "SpeedLimit.flag-commands-glide");
        flagCommandsRiptide = commandList(config, "SpeedLimit.flag-commands-riptide");
        flagCommandsLunge = commandList(config, "SpeedLimit.flag-commands-lunge");
        flagCommandsWalk = commandList(config, "SpeedLimit.flag-commands-walk");
        resetAllBuckets();
    }

    /**
     * Reads a per-vehicle-type speed limit. Absent/-1 falls back to the
     * general vehicle limit (pre-v9 configs behave exactly as before); the
     * in-code default applies only when config loading itself fails.
     */
    private double vehicleTypeBps(@NotNull ConfigManager config, String key, double defaultBps, double vehicleBps) {
        final double value = config.getDoubleElse(key, defaultBps);
        return value > 0.0 ? Math.max(1.0, value) : vehicleBps;
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
