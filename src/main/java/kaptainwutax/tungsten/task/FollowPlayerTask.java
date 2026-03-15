package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.path.BaritoneDelegate;
import kaptainwutax.tungsten.util.WindMouseRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.WorldView;

/**
 * Follows a named player. Handles re-discovery when they disappear and return.
 *
 * Modes (priority order):
 *   SUPER_FAST — dist < 6 + LOS + outside followRadius:
 *                  direct sprint via WindMouse rotation, no pathfinder
 *   BARITONE   — dist >= 6 (or no LOS): Baritone GoalFollowEntity / GoalBlock
 *
 * followRadius controls the stop distance.
 *   0   → push mode: never stop, always sprint into the target (default)
 *   > 0 → maintain given distance; stop when within radius
 *
 * When target disappears: Baritone navigates to lastKnownPos until re-found.
 */
public class FollowPlayerTask {

    private static final double SUPER_FAST_DIST  = 6.0;
    private static final double BARITONE_MIN_RADIUS = 0.5;
    // DYNAMIC fallback constants
    private static final int    RECALC_TICKS    = 15;
    private static final double MIN_MOVE_DIST   = 1.5;
    private static final int    STUCK_TICKS     = 30;

    // ── identity ──────────────────────────────────────────────────────────────
    private static String  targetName   = null;
    private static Entity  targetEntity = null;
    private static Vec3d   lastKnownPos = null;
    private static boolean active       = false;

    // ── config ────────────────────────────────────────────────────────────────
    private static double followRadius = 0.0;

    // ── mode ──────────────────────────────────────────────────────────────────
    private enum Mode { SUPER_FAST, PATHFINDING }
    private static Mode mode = Mode.PATHFINDING;

    // ── Baritone tracking ─────────────────────────────────────────────────────
    private static Entity baritoneLastEntity = null;

    // ── DYNAMIC fallback state ────────────────────────────────────────────────
    private static Vec3d   lastTargetPos = null;
    private static int     tickCounter   = 0;
    private static int     stuckTicks    = 0;
    private static boolean stopRequested = false;
    // Cooldown (ticks) before Baritone may take over after Tungsten ran
    private static final int SWITCH_COOLDOWN_TICKS = 60; // 3 sec
    private static int     switchCooldown = 0;

    // ─────────────────────────────────────────────────────────────────────────

    /** Start following with push mode (never stop). */
    public static void start(String name) {
        start(name, 0.0);
    }

    /** Start following, stopping when within followRadius blocks (0 = push mode). */
    public static void start(String name, double followRadius) {
        targetName         = name;
        targetEntity       = null;
        lastKnownPos       = null;
        baritoneLastEntity = null;
        lastTargetPos      = null;
        tickCounter        = 0;
        stuckTicks         = 0;
        stopRequested      = false;
        switchCooldown     = 0;
        mode               = Mode.PATHFINDING;
        FollowPlayerTask.followRadius = followRadius;
        active             = true;
        String suffix = followRadius > 0 ? " (radius=" + followRadius + ")" : " (push mode)";
        Debug.logMessage("Following player: " + name + suffix);
    }

    public static void stop() {
        active             = false;
        targetName         = null;
        targetEntity       = null;
        mode               = Mode.PATHFINDING;
        baritoneLastEntity = null;
        stopRequested      = false;
        stuckTicks         = 0;
        switchCooldown     = 0;
        releaseKeys();
        BaritoneDelegate.stop();
        TungstenModDataContainer.PATHFINDER.stop.set(true);
        TungstenModDataContainer.EXECUTOR.stop = true;
        Debug.logMessage("FollowPlayer stopped.");
    }

    public static boolean isActive()        { return active; }
    public static String  getTargetName()   { return targetName; }
    public static double  getFollowRadius() { return followRadius; }

    // ─────────────────────────────────────────────────────────────────────────

    public static void tick(WorldView world, ClientPlayerEntity player) {
        if (!active) return;

        tryRediscover();

        Vec3d   targetPos;
        boolean hasEntity;

        if (targetEntity != null && !targetEntity.isRemoved()) {
            BlockPos bp = targetEntity.getBlockPos();
            targetPos    = new Vec3d(bp.getX() + 0.5, targetEntity.getY(), bp.getZ() + 0.5);
            lastKnownPos = targetPos;
            hasEntity    = true;
        } else if (lastKnownPos != null) {
            targetPos = lastKnownPos;
            hasEntity = false;
        } else {
            return; // no position known yet
        }

        double dist          = player.getPos().distanceTo(targetPos);
        boolean outsideRadius = followRadius <= 0 || dist >= followRadius;

        // ── SUPER_FAST: dist < 6 + LOS + outside followRadius ────────────────
        boolean canSuperFast = dist < SUPER_FAST_DIST && outsideRadius
                && hasEntity && hasLineOfSight(player, targetPos);

        if (canSuperFast) {
            if (mode != Mode.SUPER_FAST) {
                mode = Mode.SUPER_FAST;
                BaritoneDelegate.stop();
                baritoneLastEntity = null;
                TungstenMod.LOG.info("[FollowPlayer] MODE: SUPER_FAST (dist="
                        + String.format("%.1f", dist) + ", LOS=true)");
            }
            doDirectSprint(player, targetPos);
            return;
        }

        // Leaving SUPER_FAST → back to pathfinding
        if (mode == Mode.SUPER_FAST) {
            mode = Mode.PATHFINDING;
            releaseKeys();
            baritoneLastEntity = null; // force Baritone restart
            TungstenMod.LOG.info("[FollowPlayer] MODE: PATHFINDING");
        }

        // ── Within followRadius: stop (only when followRadius > 0) ───────────
        if (followRadius > 0 && !outsideRadius && hasEntity) {
            if (BaritoneDelegate.isPathing()) BaritoneDelegate.stop();
            return;
        }

        double closeEnough = Math.max(followRadius, BARITONE_MIN_RADIUS);

        // ── Tungsten A*: always runs as primary pathfinder ───────────────────
        tickCounter++;
        boolean executorRunning  = TungstenModDataContainer.EXECUTOR.isRunning();
        boolean pathfinderActive = TungstenModDataContainer.PATHFINDER.active.get();

        if (!pathfinderActive && !executorRunning && !stopRequested) {
            stuckTicks = 0;
            startFind(world, player, targetPos, dist);
        } else if (stopRequested && !pathfinderActive) {
            stopRequested = false;
            stuckTicks    = 0;
            startFind(world, player, targetPos, dist);
        } else if (!stopRequested && tickCounter >= RECALC_TICKS
                && lastTargetPos != null
                && targetPos.distanceTo(lastTargetPos) > MIN_MOVE_DIST) {
            TungstenModDataContainer.PATHFINDER.stop.set(true);
            stopRequested = true;
            tickCounter   = 0;
        } else if (!executorRunning && !pathfinderActive) {
            if (++stuckTicks >= STUCK_TICKS) {
                TungstenModDataContainer.PATHFINDER.stop.set(true);
                stopRequested = true;
                stuckTicks    = 0;
            }
        } else {
            stuckTicks = 0;
        }

        // ── Baritone: parallel fallback — runs when Tungsten executor is idle ─
        if (TungstenConfig.get().baritoneEnabled) {
            if (executorRunning) {
                // Tungsten executing — reset cooldown and stop Baritone
                switchCooldown = SWITCH_COOLDOWN_TICKS;
                if (baritoneLastEntity != null || BaritoneDelegate.isActive()) {
                    BaritoneDelegate.stop();
                    baritoneLastEntity = null;
                    TungstenMod.LOG.info("[FollowPlayer] Baritone yields to Tungsten");
                }
            } else {
                // Drop stale entity reference so Baritone restarts with correct target
                if (baritoneLastEntity != null && baritoneLastEntity.isRemoved()) {
                    BaritoneDelegate.stop();
                    baritoneLastEntity = null;
                }
                if (switchCooldown > 0) {
                    switchCooldown--;
                } else if (hasEntity) {
                    if (targetEntity != baritoneLastEntity) {
                        baritoneLastEntity = targetEntity;
                        BaritoneDelegate.followEntity(targetEntity, closeEnough);
                        TungstenMod.LOG.info("[FollowPlayer] Baritone fallback (entity: " + targetName + ")");
                    } else if (!BaritoneDelegate.isPathing() && !BaritoneDelegate.isActive()) {
                        BaritoneDelegate.followEntity(targetEntity, closeEnough);
                    }
                } else if (lastKnownPos != null) {
                    if (!BaritoneDelegate.isPathing() && !BaritoneDelegate.isActive()) {
                        baritoneLastEntity = null;
                        BaritoneDelegate.goToBlock(lastKnownPos, (int) Math.max(closeEnough, 1));
                        TungstenMod.LOG.info("[FollowPlayer] Baritone fallback (navigating to lastKnownPos)");
                    }
                }
            }
        } else if (BaritoneDelegate.isActive()) {
            BaritoneDelegate.stop();
            baritoneLastEntity = null;
        }
    }

    private static void startFind(WorldView world, ClientPlayerEntity player, Vec3d targetPos, double dist) {
        tickCounter   = 0;
        lastTargetPos = targetPos;
        TungstenMod.TARGET = targetPos;

        if (dist < 6 && hasLineOfSight(player, targetPos)) {
            TungstenModDataContainer.PATHFINDER.searchTimeoutMs      = 120L;
            TungstenModDataContainer.PATHFINDER.minPathSizeForTimeout = 1;
            TungstenModDataContainer.PATHFINDER.minDistPath           = 0.1;
        } else if (dist < 12) {
            TungstenModDataContainer.PATHFINDER.searchTimeoutMs      = 1500L;
            TungstenModDataContainer.PATHFINDER.minPathSizeForTimeout = 5;
            TungstenModDataContainer.PATHFINDER.minDistPath           = 0.5;
        } else if (dist < 25) {
            TungstenModDataContainer.PATHFINDER.searchTimeoutMs      = 4000L;
            TungstenModDataContainer.PATHFINDER.minPathSizeForTimeout = 10;
            TungstenModDataContainer.PATHFINDER.minDistPath           = 1.0;
        } else {
            TungstenModDataContainer.PATHFINDER.searchTimeoutMs      = 15000L;
            TungstenModDataContainer.PATHFINDER.minPathSizeForTimeout = 20;
            TungstenModDataContainer.PATHFINDER.minDistPath           = 1.8;
        }
        TungstenModDataContainer.PATHFINDER.find(world, targetPos, player);
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** Direct sprint toward target via WindMouse rotation — no pathfinder. */
    private static void doDirectSprint(ClientPlayerEntity player, Vec3d targetPos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d pos = player.getPos();
        double dx = targetPos.x - pos.x;
        double dz = targetPos.z - pos.z;
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        // Render mixin applies WindMouse rotation at render frequency
        WindMouseRotation.INSTANCE.setTarget(targetYaw, player.getPitch());
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
        // Always jump when on ground for aggressive push
        mc.options.jumpKey.setPressed(player.isOnGround());
    }

    private static void releaseKeys() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        WindMouseRotation.INSTANCE.clearTarget();
    }

    /** True if no solid block obstructs the line from player eyes to targetPos. */
    private static boolean hasLineOfSight(ClientPlayerEntity player, Vec3d targetPos) {
        Vec3d eyePos = player.getEyePos();
        RaycastContext ctx = new RaycastContext(eyePos, targetPos,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player);
        return TungstenMod.mc.world.raycast(ctx).getType() == HitResult.Type.MISS;
    }

    /** Scan nearby players each tick to (re-)find target by name. */
    private static void tryRediscover() {
        if (targetName == null) return;
        if (targetEntity != null && !targetEntity.isRemoved()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p.getName().getString().equalsIgnoreCase(targetName)) {
                targetEntity = p;
                return;
            }
        }
        targetEntity = null;
    }
}
