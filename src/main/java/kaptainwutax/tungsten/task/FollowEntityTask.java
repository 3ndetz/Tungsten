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
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.WorldView;

/**
 * Core entity-following engine. Contains ALL routing logic:
 * SUPER_FAST, Tungsten A*, Baritone fallback, TRAILING.
 *
 * Two usage modes:
 *   1. Direct:  start(entity, closeEnough) — auto-stops when entity is removed
 *   2. Managed: startManaged(closeEnough) + updateTarget() — FollowPlayerTask
 *               controls the entity lifecycle; continues with lastKnownPos on removal
 */
public class FollowEntityTask {

    private static final double SUPER_FAST_DIST    = 6.0;
    private static final double DEFAULT_CLOSE_ENOUGH = 2.0;
    private static final int    RECALC_TICKS       = 15;
    private static final double MIN_MOVE_DIST      = 1.5;
    private static final int    STUCK_TICKS        = 30;
    private static final int    SWITCH_COOLDOWN_TICKS = 60; // 3 sec

    // ── state ───────────────────────────────────────────────────────────────────
    private static Entity  targetEntity    = null;
    private static Vec3d   lastKnownPos    = null;
    private static boolean active          = false;
    private static double  closeEnough     = DEFAULT_CLOSE_ENOUGH;
    private static boolean managed         = false; // true = FollowPlayerTask controls entity

    // ── mode ────────────────────────────────────────────────────────────────────
    private enum Mode { SUPER_FAST, PATHFINDING }
    private static Mode mode = Mode.PATHFINDING;

    // ── Baritone ────────────────────────────────────────────────────────────────
    private static Entity baritoneLastEntity = null;

    // ── pathfinder state ────────────────────────────────────────────────────────
    private static Vec3d   lastTargetPos  = null;
    private static int     tickCounter    = 0;
    private static int     stuckTicks     = 0;
    private static boolean stopRequested  = false;
    private static int     switchCooldown = 0;

    // ── TRAILING ────────────────────────────────────────────────────────────────
    private static final TrailTracker trail = new TrailTracker("FollowEntity");

    // ─────────────────────────────────────────────────────────────────────────────

    /** Start following an entity directly. Auto-stops when entity is removed. */
    public static void start(Entity entity) {
        start(entity, DEFAULT_CLOSE_ENOUGH);
    }

    /** Start following an entity directly with custom distance. */
    public static void start(Entity entity, double closeEnough) {
        resetState();
        targetEntity = entity;
        FollowEntityTask.closeEnough = closeEnough;
        managed = false;
        active = true;
        Debug.logMessage("Following: " + (entity != null ? entity.getName().getString() : "null"));
    }

    /** Start in managed mode (FollowPlayerTask controls entity via updateTarget). */
    public static void startManaged(double closeEnough) {
        resetState();
        FollowEntityTask.closeEnough = Math.max(closeEnough, 0.5);
        managed = true;
        active = true;
    }

    private static void resetState() {
        targetEntity       = null;
        lastKnownPos       = null;
        baritoneLastEntity = null;
        lastTargetPos      = null;
        tickCounter        = 0;
        stuckTicks         = 0;
        stopRequested      = false;
        switchCooldown     = 0;
        mode               = Mode.PATHFINDING;
        trail.reset();
    }

    public static void stop() {
        active             = false;
        managed            = false;
        targetEntity       = null;
        lastKnownPos       = null;
        mode               = Mode.PATHFINDING;
        baritoneLastEntity = null;
        stopRequested      = false;
        stuckTicks         = 0;
        switchCooldown     = 0;
        trail.reset();
        releaseKeys();
        BaritoneDelegate.stop();
        TungstenModDataContainer.PATHFINDER.stop.set(true);
        TungstenModDataContainer.EXECUTOR.stop = true;
        Debug.logMessage("Follow stopped.");
    }

    /** Update target entity without resetting pathfinding state. */
    public static void updateTarget(Entity entity) {
        if (entity != targetEntity) {
            targetEntity = entity;
            baritoneLastEntity = null; // force Baritone restart with new entity
        }
    }

    public static boolean isActive()  { return active; }
    public static Entity  getTarget() { return targetEntity; }
    public static boolean isManaged() { return managed; }

    // ─────────────────────────────────────────────────────────────────────────────

    /** Called every game tick from MixinClientPlayerEntity. */
    public static void tick(WorldView world, ClientPlayerEntity player) {
        if (!active) return;

        // resolve target position
        Vec3d   targetPos;
        boolean hasEntity;

        if (targetEntity != null && !targetEntity.isRemoved()) {
            BlockPos bp = targetEntity.getBlockPos();
            targetPos    = new Vec3d(bp.getX() + 0.5, targetEntity.getY(), bp.getZ() + 0.5);
            lastKnownPos = targetPos;
            hasEntity    = true;
        } else if (managed && lastKnownPos != null) {
            // managed mode: survive entity removal, navigate to lastKnownPos
            targetPos = lastKnownPos;
            hasEntity = false;
        } else if (!managed) {
            // direct mode: entity gone → stop
            stop();
            return;
        } else {
            return; // managed but no position known yet
        }

        double dist          = player.getPos().distanceTo(targetPos);
        boolean outsideRadius = closeEnough <= 0 || dist >= closeEnough;

        // ── Trail recording + TRAILING state ───────────────────────────────────
        if (hasEntity) trail.recordPosition(targetPos);
        trail.update(player.getPos(), targetPos);

        // ── SUPER_FAST: dist < 6 + LOS + outside closeEnough ──────────────────
        boolean canSuperFast = dist < SUPER_FAST_DIST && outsideRadius
                && hasEntity && hasLineOfSight(player, targetPos);

        if (canSuperFast) {
            if (mode != Mode.SUPER_FAST) {
                mode = Mode.SUPER_FAST;
                BaritoneDelegate.stop();
                baritoneLastEntity = null;
                TungstenMod.LOG.info("[FollowEntity] MODE: SUPER_FAST (dist="
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
            TungstenMod.LOG.info("[FollowEntity] MODE: PATHFINDING");
        }

        // ── Within closeEnough: hold position ─────────────────────────────────
        if (closeEnough > 0 && !outsideRadius && hasEntity) {
            if (BaritoneDelegate.isPathing()) BaritoneDelegate.stop();
            return;
        }

        // ── Resolve effective target: waypoint when TRAILING, else real target ─
        Vec3d effectiveTarget = targetPos;
        if (trail.isTrailing()) {
            Vec3d wp = trail.getWaypoint(player.getPos());
            if (wp != null) {
                effectiveTarget = wp;
            }
        }
        double effectiveDist = player.getPos().distanceTo(effectiveTarget);

        // ── Tungsten A*: always runs as primary pathfinder ───────────────────
        tickCounter++;
        boolean executorRunning  = TungstenModDataContainer.EXECUTOR.isRunning();
        boolean pathfinderActive = TungstenModDataContainer.PATHFINDER.active.get();

        if (!pathfinderActive && !executorRunning && !stopRequested) {
            stuckTicks = 0;
            startFind(world, player, effectiveTarget, effectiveDist);
        } else if (stopRequested && !pathfinderActive) {
            stopRequested = false;
            stuckTicks    = 0;
            startFind(world, player, effectiveTarget, effectiveDist);
        } else if (!stopRequested && tickCounter >= RECALC_TICKS
                && lastTargetPos != null
                && effectiveTarget.distanceTo(lastTargetPos) > MIN_MOVE_DIST) {
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
                switchCooldown = SWITCH_COOLDOWN_TICKS;
                if (baritoneLastEntity != null || BaritoneDelegate.isActive()) {
                    BaritoneDelegate.stop();
                    baritoneLastEntity = null;
                    TungstenMod.LOG.info("[FollowEntity] Baritone yields to Tungsten");
                }
            } else {
                if (baritoneLastEntity != null && baritoneLastEntity.isRemoved()) {
                    BaritoneDelegate.stop();
                    baritoneLastEntity = null;
                }
                if (switchCooldown > 0) {
                    switchCooldown--;
                } else if (trail.isTrailing()) {
                    // TRAILING: Baritone navigates to waypoint
                    if (!BaritoneDelegate.isPathing() && !BaritoneDelegate.isActive()) {
                        baritoneLastEntity = null;
                        BaritoneDelegate.goToBlock(effectiveTarget, (int) Math.max(closeEnough, 1));
                        TungstenMod.LOG.info("[FollowEntity] Baritone TRAILING → waypoint "
                                + trail.getWaypointIndex() + "/" + trail.getTrailSize());
                    }
                } else if (hasEntity) {
                    if (targetEntity != baritoneLastEntity) {
                        baritoneLastEntity = targetEntity;
                        BaritoneDelegate.followEntity(targetEntity, closeEnough);
                        TungstenMod.LOG.info("[FollowEntity] Baritone fallback (entity)");
                    } else if (!BaritoneDelegate.isPathing() && !BaritoneDelegate.isActive()) {
                        BaritoneDelegate.followEntity(targetEntity, closeEnough);
                    }
                } else if (lastKnownPos != null) {
                    if (!BaritoneDelegate.isPathing() && !BaritoneDelegate.isActive()) {
                        baritoneLastEntity = null;
                        BaritoneDelegate.goToBlock(lastKnownPos, (int) Math.max(closeEnough, 1));
                        TungstenMod.LOG.info("[FollowEntity] Baritone fallback (lastKnownPos)");
                    }
                }
            }
        } else if (BaritoneDelegate.isActive()) {
            BaritoneDelegate.stop();
            baritoneLastEntity = null;
        }
    }

    private static void startFind(WorldView world, ClientPlayerEntity player, Vec3d target, double dist) {
        tickCounter   = 0;
        lastTargetPos = target;
        TungstenMod.TARGET = target;

        if (dist < 6 && hasLineOfSight(player, target)) {
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
        TungstenModDataContainer.PATHFINDER.find(world, target, player);
    }

    // ─────────────────────────────────────────────────────────────────────────────

    /** Direct sprint toward target via WindMouse rotation — no pathfinder. */
    private static void doDirectSprint(ClientPlayerEntity player, Vec3d targetPos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d pos = player.getPos();
        double dx = targetPos.x - pos.x;
        double dz = targetPos.z - pos.z;
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        WindMouseRotation.INSTANCE.setTarget(targetYaw, player.getPitch());
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
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
    static boolean hasLineOfSight(ClientPlayerEntity player, Vec3d targetPos) {
        Vec3d eyePos = player.getEyePos();
        RaycastContext ctx = new RaycastContext(eyePos, targetPos,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player);
        return TungstenMod.mc.world.raycast(ctx).getType() == HitResult.Type.MISS;
    }
}
