package kaptainwutax.tungsten.util;

import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.util.math.Vec3d;

/**
 * Utility methods for Baritone heuristic calculations.
 * Ported from altoclef's BaritoneHelper.
 */
public class BaritoneHelper {

    /** Use when accessing Minecraft world data from Baritone's pathfinding thread. */
    public static final Object MINECRAFT_LOCK = new Object();

    /** Returns Baritone's A* heuristic approximation from start to target. */
    public static double calculateGenericHeuristic(Vec3d start, Vec3d target) {
        return calculateGenericHeuristic(start.x, start.y, start.z, target.x, target.y, target.z);
    }

    public static double calculateGenericHeuristic(double xStart, double yStart, double zStart,
                                                    double xTarget, double yTarget, double zTarget) {
        double xDiff = xTarget - xStart;
        int yDiff = (int) yTarget - (int) yStart;
        double zDiff = zTarget - zStart;
        return GoalBlock.calculate(xDiff, yDiff < 0 ? yDiff - 1 : yDiff, zDiff);
    }
}
