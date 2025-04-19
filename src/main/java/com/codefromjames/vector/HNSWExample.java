package com.codefromjames.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.lang.Math;

class Vertex {
    private final double[] vector;
    private final Map<String, Object> metadata;

    public Vertex(double[] vector) {
        this.vector = vector;
        this.metadata = new HashMap<>();
    }

    public void setMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    public Object getMetadata(String key) {
        return metadata.get(key);
    }

    public double[] getVector() {
        return vector.clone(); // Return a copy to avoid external modifications
    }

    public int getDimension() {
        return vector.length;
    }

    @Override
    public final boolean equals(Object o) {
        if (!(o instanceof Vertex vertex)) return false;

        return Arrays.equals(vector, vertex.vector) && metadata.equals(vertex.metadata);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(vector);
        result = 31 * result + metadata.hashCode();
        return result;
    }
}

class HNSWIndex {
    private static final int MAX_LEVEL = 16;
    private List<List<Vertex>> levels;
    private Random random;

    public HNSWIndex() {
        this.levels = new ArrayList<>();
        for (int i = 0; i <= MAX_LEVEL; i++) {
            levels.add(new ArrayList<>());
        }
        this.random = new Random();
    }

    public List<Vertex> getAllVertex() {
        return levels.stream()
                .flatMap(Collection::stream)
                .distinct()
                .toList();
    }

    // Insert a vertex into the index
    public void insert(Vertex v) {
        int level = randomLevel();
        while (levels.size() <= level) {
            levels.add(new ArrayList<>());
        }
        insert(v, level);
    }

    private void insert(Vertex v, int level) {
        if (level == 0) {
            List<Vertex> layer = levels.get(0);
            int idx = findEntryPoint(v).getDimension();
            if (idx < layer.size()) {
                layer.add(idx + 1, v);
            } else {
                layer.add(v);
            }
            return;
        }

        Vertex entryPoint = findEntryPoint(v);
        List<Vertex> currentLayer = levels.get(level);

        // Search the higher level to find the correct insertion point
        int idx = binarySearch(currentLayer, v);

        if (idx < 0) {
            idx = -idx - 1;
        }
        currentLayer.add(idx, v);

        insert(v, level - 1);
    }

    private Vertex findEntryPoint(Vertex v) {
        // For simplicity, we use the first vertex in the highest non-empty level as entry point
        for (int i = levels.size() - 1; i >= 0; i--) {
            List<Vertex> layer = levels.get(i);
            if (!layer.isEmpty()) {
                return layer.get(0);
            }
        }
        // If no vertices are present, use a dummy vertex // TODO: Why not just return `v`?
        return new Vertex(new double[v.getDimension()]);
    }

    private int binarySearch(List<Vertex> layer, Vertex v) {
        int left = 0;
        int right = layer.size() - 1;

        while (left <= right) {
            int mid = (left + right) / 2;
            if (cosineSimilarity(layer.get(mid), v) < 0) {
                left = mid + 1;
            } else {
                right = mid - 1;
            }
        }

        return -(left + 1);
    }

    // Remove a vertex from the index
    public void remove(Vertex v) {
        for (int i = levels.size() - 1; i >= 0; i--) {
            List<Vertex> layer = levels.get(i);
            int idx = findVertexIndex(layer, v);
            if (idx != -1) {
                layer.remove(idx);
            }
        }
    }

    // Update a vertex in the index
    public void update(Vertex oldV, Vertex newV) {
        remove(oldV);
        insert(newV);
    }

    // Query for similar vertices
    public List<Vertex> search(Vertex query, int k) {
        PriorityQueue<Vertex> result = new PriorityQueue<>(k, getVertexComparator(query));
        Vertex entryPoint = findEntryPoint(query);

        // Perform a greedy search from the highest level to the lowest
        for (int i = levels.size() - 1; i >= 0; i--) {
            List<Vertex> layer = levels.get(i);
            int idx = findVertexIndex(layer, entryPoint);
            while (idx >= 0 && idx < layer.size()) {
                Vertex v = layer.get(idx);
                if (!result.contains(v)) {
                    result.add(v);
                    if (result.size() > k) {
                        result.poll();
                    }
                }
                // Move to the next vertex in the current layer
                idx++;
            }
        }

        return new ArrayList<>(result);
    }

    static Comparator<Vertex> getVertexComparator(Vertex query) {
        return Comparator.comparingDouble(v -> -cosineSimilarity(query, v));
    }

    static double cosineSimilarity(Vertex a, Vertex b) {
        double[] vecA = a.getVector();
        double[] vecB = b.getVector();

        if (vecA.length != vecB.length) {
            throw new IllegalArgumentException("Vectors must be of the same dimension");
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vecA.length; i++) {
            dotProduct += vecA[i] * vecB[i];
            normA += Math.pow(vecA[i], 2);
            normB += Math.pow(vecB[i], 2);
        }

        if (normA == 0 || normB == 0) {
            return 0.0; // To avoid division by zero
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private int randomLevel() {
        int level = 0;
        while (random.nextDouble() < 0.5 && level < MAX_LEVEL) {
            level++;
        }
        return level;
    }

    private int findVertexIndex(List<Vertex> layer, Vertex v) {
        for (int i = 0; i < layer.size(); i++) {
            if (Arrays.equals(layer.get(i).getVector(), v.getVector())) {
                return i;
            }
        }
        return -1;
    }
}

public class HNSWExample {
    static final Logger LOGGER = LoggerFactory.getLogger(Math.class);

    public static void main(String[] args) {
        final HNSWIndex index = new HNSWIndex();

        final double[][] data = {
                {1.0, 2.0, 3.0},
                {4.0, 5.0, 6.0},
                {7.0, 8.0, 9.0},
                {2.0, 3.0, 4.0},
                {5.0, 6.0, 7.0},
                {8.0, 9.0, 10.0},
                {3.0, 4.0, 5.0},
                {6.0, 7.0, 8.0},
                {9.0, 10.0, 11.0},
                {4.0, 5.0, 6.0},
                {10.0, 11.0, 12.0},
                {5.0, 6.0, 7.0},
                {11.0, 12.0, 13.0},
                {6.0, 7.0, 8.0},
                {12.0, 13.0, 14.0},
                {7.0, 8.0, 9.0},
                {13.0, 14.0, 15.0},
                {8.0, 9.0, 10.0},
                {14.0, 15.0, 16.0},
                {9.0, 10.0, 11.0},
        };

        for (double[] vector : data) {
            Vertex v = new Vertex(vector);
            v.setMetadata("id", Arrays.toString(vector));
            index.insert(v);
        }

        double[] queryVector = {5.0, 6.0, 7.0};
        Vertex queryVertex = new Vertex(queryVector);
        LOGGER.info("Searching vertex: {}, Metadata: {}", Arrays.toString(queryVertex.getVector()), queryVertex.getMetadata("id"));

        System.out.println();
        final List<Vertex> similarVertices = index.search(queryVertex, 2);
        for (Vertex v : similarVertices) {
            LOGGER.info("Similar vertex: {}, Metadata: {}, Distance: {}", Arrays.toString(v.getVector()), v.getMetadata("id"), HNSWIndex.cosineSimilarity(queryVertex, v));
        }

        System.out.println();
        for (Vertex v : index.getAllVertex().stream().sorted(HNSWIndex.getVertexComparator(queryVertex)).toList()) {
            LOGGER.info("All vertex: {}, Metadata: {}, Distance: {}", Arrays.toString(v.getVector()), v.getMetadata("id"), HNSWIndex.cosineSimilarity(queryVertex, v));
        }
    }
}
