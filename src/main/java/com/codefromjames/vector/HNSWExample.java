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

    @Override
    public final boolean equals(Object o) {
        if (!(o instanceof VertexDistance that)) return false;

        // Defer equality checks to the underlying vertices
        return Objects.equals(vertex, that.vertex);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(vertex);
    }
}

class Vertex {
    static final int ML = 32;

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
        while (edges.size() <= level) {
            edges.add(new PriorityQueue<>(Comparator.<VertexDistance>comparingDouble(v -> v.distance).reversed()));
        }
    }

    public PriorityQueue<VertexDistance> getEdges(int level) {
        return edges.get(level);
    }

    public void addEdge(int level, VertexDistance neighbor) {
        while (edges.size() <= level) {
            edges.add(new PriorityQueue<>(Comparator.<VertexDistance>comparingDouble(v -> v.distance).reversed()));
        }

        final PriorityQueue<VertexDistance> layerEdges = edges.get(level);
        if (layerEdges.size() > ML
                && layerEdges.peek().distance > neighbor.distance) {
            layerEdges.poll();
        }
        layerEdges.add(neighbor);
    }

    public void removeEdge(int level, Vertex neighbor) {
        throw new UnsupportedOperationException(); // TODO
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

class VertexMinHeap extends PriorityQueue<VertexDistance> {
    private static final Comparator<VertexDistance> COMPARATOR =
            Comparator.comparingDouble(vd -> vd.distance);

    public VertexMinHeap() {
        super(COMPARATOR);
    }
}

class VertexMaxHeap extends PriorityQueue<VertexDistance> {
    private static final Comparator<VertexDistance> COMPARATOR =
            Comparator.<VertexDistance>comparingDouble(vd -> vd.distance).reversed();

    public VertexMaxHeap() {
        super(COMPARATOR);
    }

    public VertexMaxHeap(Collection<? extends VertexDistance> c) {
        this();
        addAll(c);
    }

    void addAndTrimIfBetterThanWorst(VertexDistance vd, int size) {
        if (!addIfBetterThanWorst(vd)) {
            return;
        }
        trim(size);
    }

    private void trim(int size) {
        assert size >= 0;
        while (size() > size) {
            poll();
        }
    }

    private boolean addIfBetterThanWorst(VertexDistance vd) {
        if (!isEmpty()) {
            if (peek().distance <= vd.distance) {
                return false;
            }
        }
        add(vd);
        return true;
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

        VertexMaxHeap propagateBest = null;
        for (int currentLevel = level; currentLevel >= 0; currentLevel--) {
            // If there are no nodes in this layer, then it's the first entry. Just add it.
            if (layers.get(currentLevel).isEmpty()) {
                layers.get(currentLevel).add(newVertex);
                continue;
            }

            final Set<Vertex> visited = new HashSet<>();
            final VertexMinHeap candidates = new VertexMinHeap();
            final VertexMaxHeap best = new VertexMaxHeap();
            if (propagateBest != null && !propagateBest.isEmpty()) {
                // Find the best neighbors in this level, searching from the previous level's matches
                candidates.addAll(propagateBest);
            } else {
                // Find the best neighbors in this level, searching from the entry point
                candidates.add(new VertexDistance(layers.get(currentLevel).get(0), newVector));
            }

            while (!candidates.isEmpty()) {
                VertexDistance current = candidates.poll();
                if (visited.contains(current.vertex)) {
                    continue;
                }

                visited.add(current.vertex);
                if (best.isEmpty()
                        || best.size() < Vertex.ML
                        || best.peek().distance > current.distance) {
                    best.add(current);
                }

                for (VertexDistance edge : current.vertex.getEdges(currentLevel)) {
                    if (visited.contains(edge.vertex)) {
                        continue;
                    }

                    if (best.isEmpty()
                            || best.size() < Vertex.ML
                            || best.peek().distance > edge.distance) {
                        best.add(edge);
                        candidates.add(edge);
                        visited.add(edge.vertex);
                    }
                }
            }

            for (VertexDistance vdNeighbor : best) {
                newVertex.addEdge(currentLevel, vdNeighbor);
                vdNeighbor.vertex.addEdge(currentLevel, new VertexDistance(newVertex, vdNeighbor.distance));
            }

            propagateBest = best;
        }
    }

    public List<Vertex> getAllVertex() {
        return layers.stream()
                .flatMap(Collection::stream)
                .toList();
    }

    public void removeVertex(Vertex v) {
        throw new UnsupportedOperationException(); // TODO
    }

    public void updateMetadata(Vertex v, Map<String, Object> newMetadata) {
        v.getMetadata().putAll(newMetadata);
    }

    public List<Vertex> search(double[] queryVector, int k) {
        throw new UnsupportedOperationException();
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
        final List<Vertex> allVertices = index.getAllVertex().stream()
                .sorted(Comparator.comparingDouble(v1 -> CosineDistanceUtils.cosineDistance(v1, queryVertex)))
                .limit(similarVertices.size() * 3L)
                .toList();
        for (Vertex v : allVertices) {
            LOGGER.info("All vertex: {}, Metadata: {}, Distance: {}", Arrays.toString(v.getVector()), v.getMetadata("id"), CosineDistanceUtils.cosineSimilarity(queryVertex, v));
        }
    }
}
