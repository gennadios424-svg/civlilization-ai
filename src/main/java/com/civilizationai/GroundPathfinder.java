package com.civilizationai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Bounded A* ground navigator for fake player entities.
 * Nodes represent feet positions, not arbitrary air blocks. A route only
 * crosses loaded, dry spaces with room for a two-block-tall player.
 */
public final class GroundPathfinder {
    private static final int MAX_VISITED = 1800;
    private static final int MAX_HORIZONTAL_RANGE = 36;

    private GroundPathfinder() {}

    public static List<BlockPos> findPath(ServerLevel level, BlockPos start, BlockPos destination) {
        BlockPos origin = start.immutable();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.score));
        Map<BlockPos, Double> costs = new HashMap<>();
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        costs.put(origin, 0.0);
        open.add(new Node(origin, heuristic(origin, destination)));
        int visited = 0;

        while (!open.isEmpty() && visited++ < MAX_VISITED) {
            Node currentNode = open.poll();
            BlockPos current = currentNode.pos;
            if (!closed.add(current)) continue;

            if (isDestination(current, destination)) {
                return reconstruct(origin, current, parents);
            }

            if (Math.abs(current.getX() - origin.getX()) > MAX_HORIZONTAL_RANGE
                    || Math.abs(current.getZ() - origin.getZ()) > MAX_HORIZONTAL_RANGE) continue;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    // Permit small natural drops while still requiring a safe landing surface.
                    for (int dy = 1; dy >= -3; dy--) {
                        BlockPos next = current.offset(dx, dy, dz);
                        if (closed.contains(next) || !isWalkable(level, next)) continue;

                        // Diagonal movement may not cut through a solid corner.
                        if (dx != 0 && dz != 0
                                && (!isWalkable(level, current.offset(dx, dy, 0))
                                || !isWalkable(level, current.offset(0, dy, dz)))) continue;

                        double step = (dx != 0 && dz != 0 ? 1.414 : 1.0)
                                + (dy > 0 ? 1.2 : dy < 0 ? 0.65 + Math.abs(dy) * 0.45 : 0.0);
                        double candidate = costs.get(current) + step;
                        if (candidate >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;

                        costs.put(next, candidate);
                        parents.put(next, current);
                        open.add(new Node(next, candidate + heuristic(next, destination)));
                    }
                }
            }
        }
        return List.of();
    }

    private static boolean isWalkable(ServerLevel level, BlockPos feet) {
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(feet.above())
                || !level.hasChunkAt(feet.below())) return false;
        if (!level.getFluidState(feet).isEmpty()
                || !level.getFluidState(feet.above()).isEmpty()) return false;

        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(feet.above());
        BlockState floorState = level.getBlockState(feet.below());

        return feetState.getCollisionShape(level, feet).isEmpty()
                && headState.getCollisionShape(level, feet.above()).isEmpty()
                && !floorState.getCollisionShape(level, feet.below()).isEmpty();
    }

    private static boolean isDestination(BlockPos current, BlockPos destination) {
        int dx = Math.abs(current.getX() - destination.getX());
        int dz = Math.abs(current.getZ() - destination.getZ());
        int dy = Math.abs(current.getY() - destination.getY());
        return dx <= 1 && dz <= 1 && dy <= 1;
    }

    private static double heuristic(BlockPos from, BlockPos to) {
        int dx = Math.abs(from.getX() - to.getX());
        int dz = Math.abs(from.getZ() - to.getZ());
        int dy = Math.abs(from.getY() - to.getY());
        return Math.max(dx, dz) + 0.7 * Math.min(dx, dz) + dy * 1.5;
    }

    private static List<BlockPos> reconstruct(
            BlockPos start, BlockPos goal, Map<BlockPos, BlockPos> parents
    ) {
        List<BlockPos> reversed = new ArrayList<>();
        BlockPos cursor = goal;
        while (!cursor.equals(start)) {
            reversed.add(cursor);
            cursor = parents.get(cursor);
            if (cursor == null) return List.of();
        }
        List<BlockPos> path = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) path.add(reversed.get(i));
        return path;
    }

    private record Node(BlockPos pos, double score) {}
}
