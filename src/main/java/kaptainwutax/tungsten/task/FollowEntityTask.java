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
 * Continuously follows a target entity.
 *
 * Modes (priority order):
 *   SUPER_FAST — dist < 6 + LOS: direct sprint via WindMouse rotation, bypasses everything
 *   BARITONE   — dist >= 6, baritoneEnabled=true: Baritone GoalFollowEntity
 *   DYNAMIC    — dist >= 6, baritoneEnabled=false: Tungsten A* pathfinder fallback
 */
public class FollowEntityTask {

    private static final double CLOSE_ENOUGH    = 2.0;
    private static final double SUPER_FAST_DIST = 6.0;
    // DYNAMIC fallback constants
    private static final int    RECALC_TICKS    = 15;
    private static final double MIN_MOVE_DIST   = 1.5;
    private static final int    STUCK_TICKS     = 30;

    private static Entity targetEntity    = null;
    private static boolean active         = false;
    private static boolean superFastActive = false;
    private static boolean baritoneActive  = false;
    private static Entity  baritoneLastEntity = null;
    // DYNAMIC fallback state
    private static Vec3d   lastTargetPos  = null;
    private static int     tickCounter    = 0;
    private static int     stuckTicks     = 0;
    private static boolean stopRequested  = false;
    // Cooldown (ticks) before Baritone may take over after Tungsten ran
    private static final int SWITCH_COOLDOWN_TICKS = 60; // 3 sec
    private static int     switchCooldown  = 0;

    public static void start(Entity entity) {
        targetEntity       = entity;
        superFastActive    = false;
        baritoneActive     = false;
        baritoneLastEntity = null;
        lastTargetPos      = null;
        tickCounter        = 0;
        stuckTicks         = 0;
        stopRequested      = false;
        switchCooldown     = 0;
        active             = true;
        Debug.logMessage("Following: " + entity.getName().getString());
    }

    public static void stop() {
        active             = false;
        targetEntity       = null;
        superFastActive    = false;
        baritoneActive     = false;
        baritoneLastEntity = null;
        stopRequested      = false;
        stuckTicks         = 0;
        switchCooldown     = 0;
        releaseKeys();
        BaritoneDelegate.stop();
        TungstenModDataContainer.PATHFINDER.stop.set(true);
        TungstenModDataContainer.EXECUTOR.stop = true;
        Debug.logMessage("Follow stopped.");
    }

    public static boolean isActive() { return active; }
    public static Entity  getTarget() { return targetEntity; }

    /** Called every game tick from MixinClientPlayerEntity. */
    public static void tick(WorldView world, ClientPlayerEntity player) {
        if (!active) return;

        if (targetEntity == null || targetEntity.isRemoved()) {
            stop();
            return;
        }

        // Snap to block center (XZ) — prevents infinite recalc on block edges
        BlockPos bp = targetEntity.getBlockPos();
        Vec3d entityPos = new Vec3d(bp.getX() + 0.5, targetEntity.getY(), bp.getZ() + 0.5);
        double dist = player.getPos().distanceTo(entityPos);

        // ── SUPER_FAST: dist < 6 + LOS → direct sprint, no pathfinder ──────────
        boolean canSuperFast = dist < SUPER_FAST_DIST && dist >= CLOSE_ENOUGH
                && hasLineOfSight(player, entityPos);
        if (canSuperFast) {
            if (!superFastActive) {
                superFastActive = true;
                if (baritoneActive) { BaritoneDelegate.stop(); baritoneActive = false; }
                TungstenMod.LOG.info("[FollowEntity] MODE: SUPER_FAST (dist="
                        + String.format("%.1f", dist) + ", LOS=true)");
            }
            doDirectSprint(player, entityPos);
            return;
        }
        if (superFastActive) {
            superFastActive = false;
            releaseKeys();
        }

        // ── CLOSE_ENOUGH: hold position ─────────────────────────────────────────
        if (dist < CLOSE_ENOUGH) {
            if (baritoneActive) { BaritoneDelegate.stop(); baritoneActive = false; }
            return;
        }

        // ── Tungsten A*: always runs as primary pathfinder ───────────────────
        tickCounter++;
        boolean executorRunning  = TungstenModDataContainer.EXECUTOR.isRunning();
        boolean pathfinderActive = TungstenModDataContainer.PATHFINDER.active.get();

        if (!pathfinderActive && !executorRunning && !stopRequested) {
            stuckTicks = 0;
            startFind(world, player, entityPos, dist);
        } else if (stopRequested && !pathfinderActive) {
            stopRequested = false;
            stuckTicks    = 0;
            startFind(world, player, entityPos, dist);
        } else if (!stopRequested && tickCounter >= RECALC_TICKS
                && lastTargetPos != null
                && entityPos.distanceTo(lastTargetPos) > MIN_MOVE_DIST) {
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
                    baritoneActive     = false;
                    TungstenMod.LOG.info("[FollowEntity] Baritone yields to Tungsten");
                }
            } else {
                // Drop stale entity reference so Baritone restarts with correct target
                if (baritoneLastEntity != null && baritoneLastEntity.isRemoved()) {
                    BaritoneDelegate.stop();
                    baritoneActive     = false;
                    baritoneLastEntity = null;
                }
                if (switchCooldown > 0) {
                    switchCooldown--;
                } else if (targetEntity != baritoneLastEntity) {
                    baritoneActive     = true;
                    baritoneLastEntity = targetEntity;
                    BaritoneDelegate.followEntity(targetEntity, CLOSE_ENOUGH);
                    TungstenMod.LOG.info("[FollowEntity] Baritone fallback started");
                } else if (!BaritoneDelegate.isPathing() && !BaritoneDelegate.isActive()) {
                    BaritoneDelegate.followEntity(targetEntity, CLOSE_ENOUGH);
                }
            }
        } else if (baritoneActive) {
            BaritoneDelegate.stop();
            baritoneActive     = false;
            baritoneLastEntity = null;
        }
    }

    private static void startFind(WorldView world, ClientPlayerEntity player, Vec3d entityPos, double dist) {
        tickCounter   = 0;
        lastTargetPos = entityPos;
        TungstenMod.TARGET = entityPos;

        if (dist < 6 && hasLineOfSight(player, entityPos)) {
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
        TungstenModDataContainer.PATHFINDER.find(world, entityPos, player);
    }

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
}
