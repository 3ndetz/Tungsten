package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.WorldView;

/**
 * Continuously follows a target entity by recalculating path when the entity moves.
 * Used for PvP chasing and entity tracking.
 *
 * Precision is reduced when close to target for faster recalculation:
 *   dist < 12  → timeout 1.5s, minPathSize 5
 *   dist < 25  → timeout 4s,   minPathSize 10
 *   dist >= 25 → timeout 15s,  minPathSize 20
 *
 * Recalculation logic:
 *   - If nothing is running → start find immediately
 *   - Every RECALC_TICKS ticks: if entity moved > MIN_MOVE_DIST → abort calculation, keep executing
 *   - Recalc starts when pathfinder is free
 *   - If player stuck (executor idle, pathfinder calculating too long) → abort and restart
 */
public class FollowEntityTask {

    private static final int RECALC_TICKS = 15;       // ticks between movement checks (~0.75 sec)
    private static final double MIN_MOVE_DIST = 1.5;  // recalc if entity moved more than this
    private static final double CLOSE_ENOUGH = 2.0;   // don't pathfind if already this close
    private static final int STUCK_TICKS = 30;        // executor idle + pathfinder busy → abort after this many ticks

    private static Entity targetEntity = null;
    private static Vec3d lastTargetPos = null;
    private static int tickCounter = 0;
    private static int stuckTicks = 0;  // how many ticks player has been idle while pathfinder is busy
    private static boolean active = false;
    private static boolean stopRequested = false;

    public static void start(Entity entity) {
        targetEntity = entity;
        lastTargetPos = null;
        tickCounter = 0;
        stuckTicks = 0;
        stopRequested = false;
        active = true;
        Debug.logMessage("Following: " + entity.getName().getString());
    }

    public static void stop() {
        active = false;
        targetEntity = null;
        stopRequested = false;
        stuckTicks = 0;
        TungstenModDataContainer.PATHFINDER.stop.set(true);
        TungstenModDataContainer.EXECUTOR.stop = true;
        Debug.logMessage("Follow stopped.");
    }

    public static boolean isActive() {
        return active;
    }

    public static Entity getTarget() {
        return targetEntity;
    }

    /**
     * Called every game tick from MixinClientPlayerEntity.
     */
    public static void tick(WorldView world, ClientPlayerEntity player) {
        if (!active) return;

        if (targetEntity == null || targetEntity.isRemoved()) {
            stop();
            return;
        }

        // Snap to block center (XZ) to avoid infinite recalc when entity stands on block edges
        BlockPos bp = targetEntity.getBlockPos();
        Vec3d entityPos = new Vec3d(bp.getX() + 0.5, targetEntity.getY(), bp.getZ() + 0.5);
        double distToTarget = player.getPos().distanceTo(entityPos);

        // Already close enough — stop executor, wait for entity to move
        if (distToTarget < CLOSE_ENOUGH) {
            if (TungstenModDataContainer.EXECUTOR.isRunning()) {
                TungstenModDataContainer.EXECUTOR.stop = true;
            }
            tickCounter = 0;
            stuckTicks = 0;
            lastTargetPos = entityPos;
            return;
        }

        tickCounter++;

        boolean pathfinderFree = !TungstenModDataContainer.PATHFINDER.active.get();
        boolean executorFree = !TungstenModDataContainer.EXECUTOR.isRunning();

        // Case 1: nothing is running at all → find path immediately
        if (pathfinderFree && executorFree && !stopRequested) {
            stuckTicks = 0;
            startFind(world, player, entityPos, distToTarget);
            return;
        }

        // Case 2: stop was requested and pathfinder is now free → start new find
        if (stopRequested && pathfinderFree) {
            stopRequested = false;
            stuckTicks = 0;
            startFind(world, player, entityPos, distToTarget);
            return;
        }

        // Case 3: entity moved significantly → abort current calculation, keep executing old path.
        // Do NOT stop the executor — player keeps moving along the old path while new one is computed.
        // When the new path is ready, PathFinder.executePath() calls addPath() (executor still running).
        if (!stopRequested && tickCounter >= RECALC_TICKS
                && lastTargetPos != null
                && entityPos.distanceTo(lastTargetPos) > MIN_MOVE_DIST) {
            TungstenModDataContainer.PATHFINDER.stop.set(true);
            stopRequested = true;
            tickCounter = 0;
        }

        // Case 4: player is standing still while pathfinder is calculating (entity moves so fast
        // that pathfinder gets aborted before emitting any path). Abort and restart to keep moving.
        if (executorFree && !pathfinderFree) {
            stuckTicks++;
            if (stuckTicks >= STUCK_TICKS) {
                TungstenModDataContainer.PATHFINDER.stop.set(true);
                stopRequested = true;
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
        }
    }

    /** Sets PathFinder precision params based on distance, then starts async find. */
    private static void startFind(WorldView world, ClientPlayerEntity player, Vec3d entityPos, double dist) {
        tickCounter = 0;
        lastTargetPos = entityPos;
        TungstenMod.TARGET = entityPos;

        if (dist < 6 && hasLineOfSight(player, entityPos)) {
            // Snap mode: accept the very first partial path found — fast, imprecise, good enough
            TungstenModDataContainer.PATHFINDER.searchTimeoutMs = 120L;
            TungstenModDataContainer.PATHFINDER.minPathSizeForTimeout = 1;
            TungstenModDataContainer.PATHFINDER.minDistPath = 0.1;
        } else if (dist < 12) {
            TungstenModDataContainer.PATHFINDER.searchTimeoutMs = 1500L;
            TungstenModDataContainer.PATHFINDER.minPathSizeForTimeout = 5;
            TungstenModDataContainer.PATHFINDER.minDistPath = 0.5;
        } else if (dist < 25) {
            TungstenModDataContainer.PATHFINDER.searchTimeoutMs = 4000L;
            TungstenModDataContainer.PATHFINDER.minPathSizeForTimeout = 10;
            TungstenModDataContainer.PATHFINDER.minDistPath = 1.0;
        } else {
            TungstenModDataContainer.PATHFINDER.searchTimeoutMs = 15000L;
            TungstenModDataContainer.PATHFINDER.minPathSizeForTimeout = 20;
            TungstenModDataContainer.PATHFINDER.minDistPath = 1.8;
        }

        TungstenModDataContainer.PATHFINDER.find(world, entityPos, player);
    }

    /** True if no solid block obstructs the line from player's eyes to targetPos. */
    private static boolean hasLineOfSight(ClientPlayerEntity player, Vec3d targetPos) {
        Vec3d eyePos = player.getEyePos();
        RaycastContext ctx = new RaycastContext(eyePos, targetPos,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player);
        return TungstenMod.mc.world.raycast(ctx).getType() == HitResult.Type.MISS;
    }
}
