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
    private static final int MAX_EDGES = 8;

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

    public void addEdge(int level, Vertex neighbor, double distance) {
        hydrateLevel(level);

        final PriorityQueue<VertexDistance> layerEdges = edges.get(level);
        layerEdges.add(new VertexDistance(neighbor, distance));

        while (layerEdges.size() > MAX_EDGES) {
            layerEdges.poll();
        }
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

        // We'll go up and down no more than one level from the current layer to create connections.
        // After that it's a graph from the node we're connected to.
        final int vertexTopLevel = Math.min(getCurrentMaxLevel(), newVertex.getMaxLevel() + 1);
        final int vertexBottomLevel = Math.max(0, newVertex.getMaxLevel() - 1);
        for (int currentLevel = vertexTopLevel; currentLevel >= vertexBottomLevel; currentLevel--) {
            final PriorityQueue<VertexDistance> neighbors = new PriorityQueue<>(Comparator.<VertexDistance>comparingDouble(vd -> vd.distance).reversed());
            for (Vertex neighbor : layers.get(currentLevel)) {
                neighbors.add(new VertexDistance(neighbor, newVector));
                while (neighbors.size() > M) {
                    neighbors.poll(); // Remove the worst match
                }
            }

            for (VertexDistance vdNeighbor : neighbors) {
                Vertex neighbor = vdNeighbor.vertex;
                double distance = vdNeighbor.distance;
                newVertex.addEdge(currentLevel, neighbor, distance);
                neighbor.addEdge(currentLevel, newVertex, distance);
            }

            // Insert at the level we belong in, but not others
            if (currentLevel == newVertex.getMaxLevel()) {
                layers.get(currentLevel).add(newVertex);
            }
        }
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
        VertexDistance bestDistance = null;
        for (int level = getCurrentMaxLevel(); level >= 0; level--) {
            for (Vertex vertex : layers.get(level)) {
                if (bestDistance == null) {
                    bestDistance = new VertexDistance(vertex, queryVector);
                } else {
                    VertexDistance currentDistance = new VertexDistance(vertex, queryVector);
                    if (currentDistance.distance < bestDistance.distance) {
                        bestDistance = currentDistance;
                    }
                }
            }
            if (bestDistance != null) {
                break;
            }
        }

        // No nodes in index
        if (bestDistance == null) {
            return new ArrayList<>();
        }

        // Starting from the best distance we were able to find, well traverse neighbors looking for better connections
        final PriorityQueue<VertexDistance> best = new PriorityQueue<>(Comparator.<VertexDistance>comparingDouble(vd -> vd.distance).reversed());
        boolean betterFound;
        do {
            betterFound = false;

            // Evaluate local edge for the best
            for (int level = bestDistance.vertex.getMaxLevel(); level >= 0; level--) {
                for (VertexDistance edge : bestDistance.vertex.getEdges(level)) {
                    VertexDistance currentDistance = new VertexDistance(edge.vertex, queryVector);
                    if (currentDistance.distance < bestDistance.distance) {
                        bestDistance = currentDistance;
                        betterFound = true;

                        best.add(bestDistance);
                        while (best.size() > k) {
                            best.poll();
                        }
                    }
                }
            }
        } while (betterFound);

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
