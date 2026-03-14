package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * Continuously follows a target entity by recalculating path when the entity moves.
 * Used for PvP chasing and entity tracking.
 *
 * Recalculation logic:
 * - If nothing is running → start find immediately
 * - Every RECALC_TICKS ticks: if entity moved > MIN_MOVE_DIST → stop current path + request recalc
 * - Recalc only starts when pathfinder is fully free (not active)
 */
public class FollowEntityTask {

    private static final int RECALC_TICKS = 15;       // ticks between movement checks (~0.75 sec)
    private static final double MIN_MOVE_DIST = 1.5;  // recalc if entity moved more than this
    private static final double CLOSE_ENOUGH = 2.0;   // don't pathfind if already this close

    private static Entity targetEntity = null;
    private static Vec3d lastTargetPos = null;
    private static int tickCounter = 0;
    private static boolean active = false;
    private static boolean stopRequested = false; // stop requested, waiting for pathfinder to free up

    public static void start(Entity entity) {
        targetEntity = entity;
        lastTargetPos = null;
        tickCounter = 0;
        stopRequested = false;
        active = true;
        Debug.logMessage("Following: " + entity.getName().getString());
    }

    public static void stop() {
        active = false;
        targetEntity = null;
        stopRequested = false;
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

        Vec3d entityPos = targetEntity.getPos();
        double distToTarget = player.getPos().distanceTo(entityPos);

        // Already close enough — stop executor, wait for entity to move
        if (distToTarget < CLOSE_ENOUGH) {
            if (TungstenModDataContainer.EXECUTOR.isRunning()) {
                TungstenModDataContainer.EXECUTOR.stop = true;
            }
            tickCounter = 0;
            lastTargetPos = entityPos;
            return;
        }

        tickCounter++;

        boolean pathfinderFree = !TungstenModDataContainer.PATHFINDER.active.get();
        boolean executorFree = !TungstenModDataContainer.EXECUTOR.isRunning();

        // Case 1: nothing is running at all → find path immediately
        if (pathfinderFree && executorFree && !stopRequested) {
            startFind(world, player, entityPos);
            return;
        }

        // Case 2: stop was requested and pathfinder is now free → start new find
        if (stopRequested && pathfinderFree) {
            stopRequested = false;
            startFind(world, player, entityPos);
            return;
        }

        // Case 3: entity moved significantly → request stop + recalc
        if (!stopRequested && tickCounter >= RECALC_TICKS
                && lastTargetPos != null
                && entityPos.distanceTo(lastTargetPos) > MIN_MOVE_DIST) {
            TungstenModDataContainer.PATHFINDER.stop.set(true);
            TungstenModDataContainer.EXECUTOR.stop = true;
            stopRequested = true;
            tickCounter = 0;
        }
    }

    private static void startFind(WorldView world, ClientPlayerEntity player, Vec3d entityPos) {
        tickCounter = 0;
        lastTargetPos = entityPos;
        TungstenMod.TARGET = entityPos;
        TungstenModDataContainer.PATHFINDER.find(world, entityPos, player);
    }
}
