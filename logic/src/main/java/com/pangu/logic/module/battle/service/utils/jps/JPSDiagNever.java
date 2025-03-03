package com.pangu.logic.module.battle.service.utils.jps;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author Kevin
 */
public class JPSDiagNever extends JPS {
    public JPSDiagNever(Graph graph) { super(graph); }

    @Override
    protected Set<Node> findNeighbors(Node node, Map<Node, Node> parentMap) {
        Set<Node> neighbors = new HashSet<>();

        Node parent = parentMap.get(node);

        // directed pruning: can ignore most neighbors, unless forced.
        if (parent != null) {
            final int x = node.x;
            final int y = node.y;
            // get normalized direction of travel
            final int dx = (x - parent.x) / Math.max(Math.abs(x - parent.x), 1);
            final int dy = (y - parent.y) / Math.max(Math.abs(y - parent.y), 1);

            // search horizontally/vertically
            if (dx != 0) {
                if (graph.isWalkable(x + dx, y))
                    neighbors.add(graph.getNode(x + dx, y));
                if (graph.isWalkable(x, y + 1))
                    neighbors.add(graph.getNode(x, y + 1));
                if (graph.isWalkable(x, y - 1))
                    neighbors.add(graph.getNode(x, y - 1));
            } else if (dy != 0) {
                if (graph.isWalkable(x, y + dy))
                    neighbors.add(graph.getNode(x, y + dy));
                if (graph.isWalkable(x + 1, y))
                    neighbors.add(graph.getNode(x + 1, y));
                if (graph.isWalkable(x - 1, y))
                    neighbors.add(graph.getNode(x - 1, y));
            }
        } else {
            // no parent, return all neighbors
            neighbors.addAll(graph.getNeighborsOf(node, Graph.Diagonal.NEVER));
        }

        return neighbors;
    }

    @Override
    protected Node jump(Node neighbor, Node current, Set<Node> goals) {
        if (neighbor == null || graph.exist(neighbor.x, neighbor.y)) return null;
        if (goals.contains(neighbor)) return neighbor;

        int dx = neighbor.x - current.x;
        int dy = neighbor.y - current.y;

        // check for forced neighbors
        // check horizontally/vertically
        if (dx != 0) {
            if ((graph.isWalkable(neighbor.x, neighbor.y + 1) && !graph.isWalkable(neighbor.x - dx, neighbor.y + 1)) ||
                    (graph.isWalkable(neighbor.x, neighbor.y - 1) && !graph.isWalkable(neighbor.x - dx, neighbor.y - 1))) {
                return neighbor;
            }
        } else if (dy != 0) {
            if ((graph.isWalkable(neighbor.x + 1, neighbor.y) && !graph.isWalkable(neighbor.x + 1, neighbor.y - dy)) ||
                    (graph.isWalkable(neighbor.x - 1, neighbor.y) && !graph.isWalkable(neighbor.x - 1, neighbor.y - dy))) {
                return neighbor;
            }
            // when moving vertically check for horizontal jump points
            if (jump(graph.getNode(neighbor.x + 1, neighbor.y), neighbor, goals) != null ||
                    jump(graph.getNode(neighbor.x - 1, neighbor.y), neighbor, goals) != null) {
                return neighbor;
            }
        } else {
            return null;
        }

        return jump(graph.getNode(neighbor.x + dx, neighbor.y + dy), neighbor, goals);
    }
}
