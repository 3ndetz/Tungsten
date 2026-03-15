package kaptainwutax.tungsten.path;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import kaptainwutax.tungsten.util.GoalFollowEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Thin wrapper around Baritone's public API.
 * All methods are guarded with try-catch to survive disconnect/null state.
 */
public class BaritoneDelegate {

    private BaritoneDelegate() {}

    private static IBaritone get() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    /** Follow an entity, stopping when within closeEnough blocks. */
    public static void followEntity(Entity entity, double closeEnough) {
        try { get().getCustomGoalProcess().setGoalAndPath(new GoalFollowEntity(entity, closeEnough)); } catch (Exception ignored) {}
    }

    /** Pathfind to a position, stopping within radius blocks. */
    public static void goToBlock(Vec3d target, int radius) {
        try {
            BlockPos bp = BlockPos.ofFloored(target.x, target.y, target.z);
            get().getCustomGoalProcess().setGoalAndPath(
                    radius <= 0 ? new GoalBlock(bp) : new GoalNear(bp, radius));
        } catch (Exception ignored) {}
    }

    /** Cancel all Baritone pathing immediately. */
    public static void stop() {
        try { get().getPathingBehavior().forceCancel(); } catch (Exception ignored) {}
    }

    /** True if Baritone is actively executing a path. */
    public static boolean isPathing() {
        try { return get().getPathingBehavior().isPathing(); } catch (Exception e) { return false; }
    }

    /** True if the custom goal process has a goal set. */
    public static boolean isActive() {
        try { return get().getCustomGoalProcess().isActive(); } catch (Exception e) { return false; }
    }
}
