package kaptainwutax.tungsten.task;

import java.util.Random;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
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
 * Follows a player by name. Handles re-discovery when the player disappears and returns.
 *
 * Modes:
 *   DYNAMIC  — normal follow with distance-based precision (fast for close/flat, slower for far)
 *   STATIC   — full pathfind to a fixed position (when 10s of no progress getting within 5 blocks)
 *
 * On failure in STATIC mode: add random XZ offset and retry.
 * When target starts moving again (in STATIC mode): switch back to DYNAMIC.
 * When target disappears: follow lastKnownPos until re-found.
 */
public class FollowPlayerTask {

    // --- constants ---
    private static final int RECALC_TICKS = 15;
    private static final double MIN_MOVE_DIST = 1.5;
    private static final double CLOSE_ENOUGH = 2.0;
    private static final int STUCK_TICKS = 30;
    private static final double PROGRESS_DIST = 5.0;
    private static final long NO_PROGRESS_MS = 10_000L;
    private static final long STATIC_TIMEOUT_MS = 10_000L;
    private static final double RANDOM_OFFSET_RANGE = 5.0;

    // --- persistent identity ---
    private static String targetName = null;
    private static Entity targetEntity = null;
    private static Vec3d lastKnownPos = null;
    private static boolean active = false;

    // --- recalc ---
    private static Vec3d lastTargetPos = null;
    private static int tickCounter = 0;
    private static boolean stopRequested = false;
    private static int stuckTicks = 0;

    // --- progress / mode ---
    private enum Mode { DYNAMIC, STATIC }
    private static Mode mode = Mode.DYNAMIC;
    private static long lastProgressTime = -1;
    private static long staticModeStartTime = -1;
    private static boolean staticModePathStarted = false;
    private static Vec3d staticModeTarget = null;
    private static final Random random = new Random();

    // -------------------------------------------------------------------------

    public static void start(String name) {
        targetName = name;
        targetEntity = null;
        lastKnownPos = null;
        lastTargetPos = null;
        tickCounter = 0;
        stuckTicks = 0;
        stopRequested = false;
        mode = Mode.DYNAMIC;
        lastProgressTime = System.currentTimeMillis();
        staticModePathStarted = false;
        staticModeTarget = null;
        active = true;
        Debug.logMessage("Following player: " + name);
    }

    public static void stop() {
        active = false;
        targetName = null;
        targetEntity = null;
        stopRequested = false;
        stuckTicks = 0;
        TungstenModDataContainer.PATHFINDER.stop.set(true);
        TungstenModDataContainer.EXECUTOR.stop = true;
        Debug.logMessage("FollowPlayer stopped.");
    }

    public static boolean isActive() { return active; }
    public static String getTargetName() { return targetName; }

    // -------------------------------------------------------------------------

    public static void tick(WorldView world, ClientPlayerEntity player) {
        if (!active) return;

        // Try to (re-)discover the target player each tick
        tryRediscover();

        Vec3d targetPos;
        boolean hasEntity;

        if (targetEntity != null && !targetEntity.isRemoved()) {
            // Snap to block center (XZ) to avoid infinite recalc when entity stands on block edges
            BlockPos bp = targetEntity.getBlockPos();
            targetPos = new Vec3d(bp.getX() + 0.5, targetEntity.getY(), bp.getZ() + 0.5);
            lastKnownPos = targetPos;
            hasEntity = true;
        } else if (lastKnownPos != null) {
            targetPos = lastKnownPos;
            hasEntity = false;
        } else {
            // No position known yet — keep searching
            return;
        }

        double dist = player.getPos().distanceTo(targetPos);

        // Progress tracking
        if (dist < PROGRESS_DIST) {
            lastProgressTime = System.currentTimeMillis();
            if (mode == Mode.STATIC) {
                mode = Mode.DYNAMIC;
                staticModePathStarted = false;
                TungstenMod.LOG.info("[FollowPlayer] Progress made, back to dynamic.");
            }
        }

        // Close enough — hold
        if (dist < CLOSE_ENOUGH && hasEntity) {
            if (TungstenModDataContainer.EXECUTOR.isRunning()) {
                TungstenModDataContainer.EXECUTOR.stop = true;
            }
            tickCounter = 0;
            stuckTicks = 0;
            return;
        }

        // Check no-progress timeout → switch to static mode
        if (mode == Mode.DYNAMIC && hasEntity
                && lastProgressTime > 0
                && System.currentTimeMillis() - lastProgressTime > NO_PROGRESS_MS) {
            mode = Mode.STATIC;
            staticModePathStarted = false;
            staticModeTarget = targetPos;
            staticModeStartTime = System.currentTimeMillis();
            TungstenModDataContainer.PATHFINDER.stop.set(true);
            TungstenModDataContainer.EXECUTOR.stop = true;
            stopRequested = false;
            TungstenMod.LOG.info("[FollowPlayer] No progress for 10s, switching to static pathfind...");
        }

        if (mode == Mode.STATIC) {
            handleStaticMode(world, player, targetPos, hasEntity);
        } else {
            handleDynamic(world, player, targetPos, dist);
        }
    }

    // -------------------------------------------------------------------------
    // Dynamic mode — follows moving target with recalculation
    // -------------------------------------------------------------------------

    private static void handleDynamic(WorldView world, ClientPlayerEntity player, Vec3d targetPos, double dist) {
        tickCounter++;
        boolean pathfinderFree = !TungstenModDataContainer.PATHFINDER.active.get();
        boolean executorFree = !TungstenModDataContainer.EXECUTOR.isRunning();

        // Nothing running → start immediately
        if (pathfinderFree && executorFree && !stopRequested) {
            stuckTicks = 0;
            startFind(world, player, targetPos, dist);
            return;
        }

        // Recalc ready
        if (stopRequested && pathfinderFree) {
            stopRequested = false;
            stuckTicks = 0;
            startFind(world, player, targetPos, dist);
            return;
        }

        // Target moved → abort calculation, keep executor running
        if (!stopRequested && tickCounter >= RECALC_TICKS
                && lastTargetPos != null
                && targetPos.distanceTo(lastTargetPos) > MIN_MOVE_DIST) {
            TungstenModDataContainer.PATHFINDER.stop.set(true);
            stopRequested = true;
            tickCounter = 0;
        }

        // Stuck detection: executor idle but pathfinder busy too long
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

    // -------------------------------------------------------------------------
    // Static mode — full pathfind to fixed position, retry with offset on fail
    // -------------------------------------------------------------------------

    private static void handleStaticMode(WorldView world, ClientPlayerEntity player, Vec3d targetPos, boolean hasEntity) {
        // If target started moving again → back to dynamic
        if (hasEntity && staticModeTarget != null
                && targetPos.distanceTo(staticModeTarget) > MIN_MOVE_DIST * 2) {
            mode = Mode.DYNAMIC;
            staticModePathStarted = false;
            lastProgressTime = System.currentTimeMillis();
            TungstenModDataContainer.PATHFINDER.stop.set(true);
            stopRequested = false;
            TungstenMod.LOG.info("[FollowPlayer] Target moving again, back to dynamic.");
            return;
        }

        boolean pathfinderFree = !TungstenModDataContainer.PATHFINDER.active.get();
        boolean executorRunning = TungstenModDataContainer.EXECUTOR.isRunning();
        long elapsed = System.currentTimeMillis() - staticModeStartTime;

        if (!staticModePathStarted) {
            if (!pathfinderFree) return; // wait for pathfinder to free up
            // Launch full static pathfind
            TungstenModDataContainer.PATHFINDER.searchTimeoutMs = STATIC_TIMEOUT_MS;
            TungstenModDataContainer.PATHFINDER.minPathSizeForTimeout = 20;
            Vec3d dest = staticModeTarget != null ? staticModeTarget : targetPos;
            TungstenMod.TARGET = dest;
            TungstenModDataContainer.PATHFINDER.find(world, dest, player);
            staticModePathStarted = true;
            staticModeStartTime = System.currentTimeMillis();
            return;
        }

        // Detect failure: pathfinder done, executor not running, and we've waited long enough
        if (pathfinderFree && !executorRunning && elapsed > STATIC_TIMEOUT_MS + 3000) {
            // Failed — pick a random offset position and retry
            double ox = (random.nextDouble() - 0.5) * RANDOM_OFFSET_RANGE * 2;
            double oz = (random.nextDouble() - 0.5) * RANDOM_OFFSET_RANGE * 2;
            staticModeTarget = targetPos.add(ox, 0, oz);
            staticModePathStarted = false;
            staticModeStartTime = System.currentTimeMillis();
            TungstenMod.LOG.info("[FollowPlayer] Static pathfind failed, retrying with offset (" + (int)ox + "," + (int)oz + ")...");
        }
    }

    // -------------------------------------------------------------------------

    private static void startFind(WorldView world, ClientPlayerEntity player, Vec3d targetPos, double dist) {
        tickCounter = 0;
        lastTargetPos = targetPos;
        TungstenMod.TARGET = targetPos;

        if (dist < 6 && hasLineOfSight(player, targetPos)) {
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

        TungstenModDataContainer.PATHFINDER.find(world, targetPos, player);
    }

    /** True if no solid block obstructs the line from player's eyes to targetPos. */
    private static boolean hasLineOfSight(ClientPlayerEntity player, Vec3d targetPos) {
        Vec3d eyePos = player.getEyePos();
        RaycastContext ctx = new RaycastContext(eyePos, targetPos,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player);
        return TungstenMod.mc.world.raycast(ctx).getType() == HitResult.Type.MISS;
    }

    /** Scan nearby players each tick to (re-)find target by name. */
    private static void tryRediscover() {
        if (targetName == null) return;
        // If we already have a valid entity, keep it
        if (targetEntity != null && !targetEntity.isRemoved()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p.getName().getString().equalsIgnoreCase(targetName)) {
                if (targetEntity == null) {
                    TungstenMod.LOG.info("[FollowPlayer] Found player: " + targetName);
                } else {
                    TungstenMod.LOG.info("[FollowPlayer] Re-found player: " + targetName);
                }
                targetEntity = p;
                // Reset no-progress timer when we re-find them
                lastProgressTime = System.currentTimeMillis();
                return;
            }
        }
        targetEntity = null; // not in range
    }
}
