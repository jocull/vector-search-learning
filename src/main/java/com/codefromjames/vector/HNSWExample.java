package com.codefromjames.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.lang.Math;
import java.util.stream.IntStream;

class CosineDistanceUtils {
    private CosineDistanceUtils() {
    }

    static double norm(double[] vector) {
        double sum = 0;
        for (double v : vector) {
            sum += v * v;
        }
        return Math.sqrt(sum);
    }

    static double cosineSimilarity(double[] v1, double[] v2) {
        double dotProduct = 0;
        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
        }
        final double norm1 = norm(v1);
        final double norm2 = norm(v2);
        return dotProduct / (norm1 * norm2);
    }

    static double cosineSimilarity(Vertex v1, Vertex v2) {
        return cosineSimilarity(v1.getVector(), v2.getVector());
    }

    static double cosineDistance(double[] v1, double[] v2) {
        return 1 - cosineSimilarity(v1, v2);
    }

    static double cosineDistance(Vertex v1, Vertex v2) {
        return cosineDistance(v1.getVector(), v2.getVector());
    }
}

class VertexDistance {
    final Vertex vertex;
    final double distance;

    VertexDistance(Vertex v, double distance) {
        this.vertex = v;
        this.distance = distance;
    }

    VertexDistance(Vertex v, double[] target) {
        this(v, CosineDistanceUtils.cosineDistance(v.getVector(), target));
    }

    @Override
    public String toString() {
        return "VertexDistance{" +
                "vertex=" + vertex +
                ", distance=" + distance +
                '}';
    }
}

class Vertex {
    private static final int MAX_EDGES = 32;

    private final double[] vector;
    private final Map<String, Object> metadata;
    private final List<PriorityQueue<VertexDistance>> edges;
    private int maxLevel;

    public Vertex(double[] vector) {
        this(vector, Map.of());
    }

    public Vertex(double[] vector, Map<String, Object> metadata) {
        this.vector = vector;
        this.metadata = new HashMap<>(metadata);
        this.maxLevel = 0;
        this.edges = new ArrayList<>();
    }

    public void setMaxLevel(int level) {
        this.maxLevel = level;
        hydrateLevel(level);
    }

    private void hydrateLevel(int level) {
        while (edges.size() <= level) {
            edges.add(new PriorityQueue<>(Comparator.<VertexDistance>comparingDouble(v -> v.distance).reversed()));
        }
    }

    public PriorityQueue<VertexDistance> getEdges(int level) {
        return edges.get(level);
    }

    public void addEdge(int level, VertexDistance neighbor) {
        hydrateLevel(level);

        final PriorityQueue<VertexDistance> layerEdges = edges.get(level);
        if (layerEdges.size() > MAX_EDGES
                && layerEdges.peek().distance > neighbor.distance) {
            layerEdges.poll();
        }
        layerEdges.add(neighbor);
    }

    public void removeEdge(int level, Vertex neighbor) {
        final PriorityQueue<VertexDistance> layerEdges = edges.get(level);
        layerEdges.stream()
                .filter(vd -> vd.vertex.equals(neighbor))
                .findFirst()
                .ifPresent(layerEdges::remove);

        // TODO: Recalculate new neighbors if we drop below threshold!
        //       Can that even happen here? It probably needs to happen elsewhere.
    }

    public double[] getVector() {
        return vector;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public int getMaxLevel() {
        return maxLevel;
    }

    public void setMetadata(String id, Object string) {
        metadata.put(id, string);
    }

    public Object getMetadata(String id) {
        return metadata.get(id);
    }

    public List<Vertex> findBestNeighbors(double[] target, int targetCount) {
        final PriorityQueue<VertexDistance> candidates = new PriorityQueue<>(Comparator.comparingDouble(vd -> vd.distance));
        final PriorityQueue<VertexDistance> bestNeighbors = new PriorityQueue<>(Comparator.<VertexDistance>comparingDouble(vd -> vd.distance).reversed());
        final Set<Vertex> visited = new HashSet<>();

        for (int level = getMaxLevel(); level >= 0; level--) {
            for (VertexDistance edge : this.getEdges(level)) {
                if (!visited.contains(edge.vertex)) {
                    visited.add(edge.vertex);
                    candidates.add(edge);
                }
            }
        }

        final List<Vertex> result = new ArrayList<>(targetCount);
        for (int i = 0; i < targetCount && i < candidates.size(); i++) {
            // TODO: Can this NPE, even with size check...?
            result.add(candidates.poll().vertex);
        }

        return result;
    }

    public Vertex findBestNeighbor(double[] targetVector, int level, Set<Vertex> visited) {
        Vertex bestNeighbor = null;
        double minDistance = CosineDistanceUtils.cosineDistance(this.getVector(), targetVector);
        double distance;
        for (VertexDistance neighbor : this.getEdges(level)) {
            if (visited.contains(neighbor.vertex)) {
                continue;
            }
            distance = CosineDistanceUtils.cosineDistance(neighbor.vertex.getVector(), targetVector);
            if (distance < minDistance) {
                minDistance = distance;
                bestNeighbor = neighbor.vertex;
            }
        }
        return bestNeighbor;
    }

    @Override
    public String toString() {
        return "Vertex{" +
                "vector=" + Arrays.toString(vector) +
                ", metadata=" + metadata +
                ", maxLevel=" + maxLevel +
                '}';
    }
}

class HNSWIndex {
    private static final double LEVEL_PROBABILITY = 0.1;
    private static final int MAX_LEVEL = 8;
    private static final int M = 5;

    private final List<List<Vertex>> layers = new ArrayList<>();

    private int getCurrentMaxLevel() {
        return layers.size() - 1;
    }

    public void addVertex(double[] newVector, Map<String, Object> metadata) {
        final Vertex newVertex = new Vertex(newVector, metadata);
        int level = 0;
        while (Math.random() < LEVEL_PROBABILITY && level < MAX_LEVEL) {
            level++;
        }

        newVertex.setMaxLevel(level);
        while (getCurrentMaxLevel() <= level) {
            layers.add(new ArrayList<>());
        }

        // Create links to neighbors in each layer down from here
        Vertex bestNeighbor = null;
        final Set<Vertex> visitedNeighbors = new HashSet<>();
        for (int currentLevel = level; currentLevel >= 0; currentLevel--) {
            final List<VertexDistance> neighbors = findBestNeighborsInLayer(newVector, currentLevel, M, visitedNeighbors, bestNeighbor);
            if (!neighbors.isEmpty()) {
                // Maintain the best node returned for the next layer
                bestNeighbor = neighbors.get(0).vertex;

                for (VertexDistance vdNeighbor : neighbors) {
                    newVertex.addEdge(currentLevel, vdNeighbor);
                    vdNeighbor.vertex.addEdge(currentLevel, new VertexDistance(newVertex, vdNeighbor.distance));
                }
            }

            // Insert at the level we belong in, but not others
            if (currentLevel == newVertex.getMaxLevel()) {
                layers.get(currentLevel).add(newVertex);
            }
        }
    }

    private List<VertexDistance> findBestNeighborsInLayer(double[] newVector,
                                                          int currentLevel,
                                                          int targetCount,
                                                          Set<Vertex> visitedNeighbors,
                                                          Vertex bestNeighbor) {
        final PriorityQueue<VertexDistance> neighbors = new PriorityQueue<>(Comparator.<VertexDistance>comparingDouble(vd -> vd.distance).reversed());
        if (bestNeighbor == null) {
            bestNeighbor = layers.get(currentLevel).stream().findFirst().orElse(null);
        }
        if (bestNeighbor == null) {
            return Collections.emptyList();
        }

        boolean betterMatch = false;
        do {
            // Don't check the first time - it may be the passed in neighbor
            if (betterMatch && visitedNeighbors.contains(bestNeighbor)) {
                break;
            }
            visitedNeighbors.add(bestNeighbor);
            final VertexDistance current = new VertexDistance(bestNeighbor, newVector);
            if (neighbors.isEmpty()
                    || neighbors.size() < targetCount // Keep lesser matches if we have capacity
                    || neighbors.peek().distance > current.distance) {
                betterMatch = true;
                neighbors.add(current);
                if (neighbors.size() >= targetCount) {
                    neighbors.poll(); // Remove the worst match
                }
            }
            bestNeighbor = bestNeighbor.findBestNeighbor(newVector, currentLevel, visitedNeighbors);
            if (bestNeighbor == null) {
                break;
            }
        } while (betterMatch);

        final List<VertexDistance> result = new ArrayList<>(neighbors);
        result.sort(Comparator.comparingDouble(vd -> vd.distance));
        return result;
    }

    public List<Vertex> getAllVertex() {
        return layers.stream()
                .flatMap(Collection::stream)
                .toList();
    }

    public void removeVertex(Vertex v) {
        throw new UnsupportedOperationException(); // TODO
//        vertices.remove(v);
//        for (int l = 0; l <= currentMaxLevel; l++) {
//            layers.get(l).remove(v);
//        }
//
//        for (Vertex other : vertices) {
//            for (int l = 0; l <= other.getMaxLevel(); l++) {
//                other.removeEdge(l, v);
//            }
//        }
//
//        for (int l = 0; l <= v.getMaxLevel(); l++) {
//            v.getEdges(l).clear();
//        }
//
//        if (vertices.isEmpty()) {
//            currentMaxLevel = -1;
//        } else {
//            int newMax = 0;
//            for (Vertex vertex : vertices) {
//                if (vertex.getMaxLevel() > newMax) {
//                    newMax = vertex.getMaxLevel();
//                }
//            }
//            currentMaxLevel = newMax;
//        }
    }

    public void updateMetadata(Vertex v, Map<String, Object> newMetadata) {
        v.getMetadata().putAll(newMetadata);
    }

    public List<Vertex> search(double[] queryVector, int k) {
        // To perform the search we need to start at the highest available layer and search for the best initial node
        final PriorityQueue<VertexDistance> best = new PriorityQueue<>(Comparator.<VertexDistance>comparingDouble(vd -> vd.distance).reversed());

        // TODO: We need to replace this with a graph search starting at top layer.
        //       `Vertex.findBestNeighbors(...)` and then work your way down from all found.
        //       Still not sure exactly what that algorithm looks like...

        List<Vertex> lastLayerBestNeighbors = Collections.emptyList();
        final Set<Vertex> visitedNeighbors = new HashSet<>();
        final Set<Vertex> distinctBest = new HashSet<>();
        for (int level = getCurrentMaxLevel(); level >= 0; level--) {
            final int thisLevel = level;
            final List<VertexDistance> bestNeighborsInLayer;
            if (lastLayerBestNeighbors.isEmpty()) {
                bestNeighborsInLayer = findBestNeighborsInLayer(queryVector, thisLevel, k, visitedNeighbors, null);
            } else {
                bestNeighborsInLayer = lastLayerBestNeighbors.stream()
                        .flatMap(bestNeighbor -> findBestNeighborsInLayer(queryVector, thisLevel, k, visitedNeighbors, bestNeighbor).stream())
                        .toList();
            }
            lastLayerBestNeighbors = bestNeighborsInLayer.stream().map(vd -> vd.vertex).toList(); // TODO: Too many streams, none of this can perform very well...
            for (VertexDistance neighbor : bestNeighborsInLayer) {
                if (!best.isEmpty()
                        && best.size() > k
                        && best.peek().distance > neighbor.distance) {
                    best.poll();
                }
                if (!distinctBest.contains(neighbor.vertex)) {
                    distinctBest.add(neighbor.vertex);
                    best.add(neighbor);
                }
            }
        }

        return best.stream()
                .sorted(Comparator.comparingDouble(vd -> vd.distance))
                .map(vd -> vd.vertex)
                .toList();
    }

    static Comparator<Vertex> getVertexComparator(Vertex queryVertex) {
        return Comparator.comparingDouble(v -> CosineDistanceUtils.cosineDistance(v, queryVertex));
    }

}

public class HNSWExample {
    static final Logger LOGGER = LoggerFactory.getLogger(Math.class);
    static final Random RANDOM = new Random();

    static double[] randomVector() {
        return new double[]{
                RANDOM.nextDouble(-1000, 1000),
                RANDOM.nextDouble(-1000, 1000),
                RANDOM.nextDouble(-1000, 1000),
        };
    }

    public static void main(String[] args) {
        final HNSWIndex index = new HNSWIndex();

//        final double[][] data = {
//                {1.0, 2.0, 3.0},
//                {4.0, 5.0, 6.0},
//                {7.0, 8.0, 9.0},
//                {2.0, 3.0, 4.0},
//                {5.0, 6.0, 7.0},
//                {8.0, 9.0, 10.0},
//                {3.0, 4.0, 5.0},
//                {6.0, 7.0, 8.0},
//                {9.0, 10.0, 11.0},
//                {4.0, 5.0, 6.0},
//                {10.0, 11.0, 12.0},
//                {5.0, 6.0, 7.0},
//                {11.0, 12.0, 13.0},
//                {6.0, 7.0, 8.0},
//                {12.0, 13.0, 14.0},
//                {7.0, 8.0, 9.0},
//                {13.0, 14.0, 15.0},
//                {8.0, 9.0, 10.0},
//                {14.0, 15.0, 16.0},
//                {9.0, 10.0, 11.0},
//        };

        List<double[]> data = IntStream.range(0, 10000)
                .mapToObj(i -> randomVector())
                .toList();

        for (double[] vector : data) {
            Vertex v = new Vertex(vector);
            index.addVertex(vector, Map.of());
        }

        double[] queryVector = {5.0, 6.0, 7.0};
        Vertex queryVertex = new Vertex(queryVector);
        LOGGER.info("Searching vertex: {}, Metadata: {}", Arrays.toString(queryVertex.getVector()), queryVertex.getMetadata("id"));

        System.out.println();
        final List<Vertex> similarVertices = index.search(queryVector, 5);
        for (Vertex v : similarVertices) {
            LOGGER.info("Similar vertex: {}, Metadata: {}, Distance: {}", Arrays.toString(v.getVector()), v.getMetadata("id"), CosineDistanceUtils.cosineSimilarity(queryVertex, v));
        }

        System.out.println();
        for (Vertex v : index.getAllVertex().stream().sorted(HNSWIndex.getVertexComparator(queryVertex)).limit(similarVertices.size() * 3L).toList()) {
            LOGGER.info("All vertex: {}, Metadata: {}, Distance: {}", Arrays.toString(v.getVector()), v.getMetadata("id"), CosineDistanceUtils.cosineSimilarity(queryVertex, v));
        }
    }
}
