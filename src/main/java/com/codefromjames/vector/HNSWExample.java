package com.codefromjames.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.lang.Math;
import java.util.stream.IntStream;

class Vertex {
    private double[] vector;
    private Map<String, Object> metadata;
    private int maxLevel;
    private List<List<Vertex>> edges;

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
            edges.add(new ArrayList<>());
        }
    }

    public List<Vertex> getEdges(int level) {
        return edges.get(level);
    }

    public void addEdge(Vertex neighbor, int level) {
        edges.get(level).add(neighbor);
    }

    public void removeEdge(Vertex neighbor, int level) {
        edges.get(level).remove(neighbor);
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

    private List<Vertex> vertices = new ArrayList<>();
    private List<List<Vertex>> entryPoints = new ArrayList<>();
    private int currentMaxLevel = -1;

    public void addVertex(double[] vector, Map<String, Object> metadata) {
        Vertex newVertex = new Vertex(vector, metadata);
        int level = 0;
        while (Math.random() < LEVEL_PROBABILITY && level < MAX_LEVEL) {
            level++;
        }
        newVertex.setMaxLevel(level);

        if (level > currentMaxLevel) {
            currentMaxLevel = level;
            while (entryPoints.size() <= level) {
                entryPoints.add(new ArrayList<>());
            }
        }

        for (int currentLevel = newVertex.getMaxLevel(); currentLevel >= 0; currentLevel--) {
            List<Vertex> neighbors = greedySearch(newVertex, currentLevel);
            neighbors.sort(Comparator.comparingDouble(v -> cosineDistance(newVertex, v)));
            if (neighbors.size() > M) {
                neighbors = neighbors.subList(0, M);
            }

            for (Vertex neighbor : neighbors) {
                newVertex.addEdge(neighbor, currentLevel);
                neighbor.addEdge(newVertex, currentLevel);
            }

            if (currentLevel == newVertex.getMaxLevel()) {
                entryPoints.get(currentLevel).add(newVertex);
            }
        }

        vertices.add(newVertex);
    }

    private List<Vertex> greedySearch(Vertex target, int level) {
        Set<Vertex> visited = new HashSet<>();
        PriorityQueue<VertexDistance> candidates = new PriorityQueue<>(Comparator.comparingDouble(vd -> vd.distance));

        for (Vertex entry : entryPoints.get(level)) {
            candidates.add(new VertexDistance(entry, cosineDistance(target, entry)));
        }

        List<Vertex> best = new ArrayList<>();
        while (!candidates.isEmpty()) {
            VertexDistance current = candidates.poll();
            Vertex v = current.vertex;
            if (visited.contains(v)) continue;
            visited.add(v);
            best.add(v);

            for (Vertex neighbor : v.getEdges(level)) {
                if (!visited.contains(neighbor)) {
                    double dist = cosineDistance(target, neighbor);
                    candidates.add(new VertexDistance(neighbor, dist));
                }
            }
        }

        best.sort(Comparator.comparingDouble(v -> cosineDistance(target, v)));
        return best.subList(0, Math.min(best.size(), M));
    }

    static double cosineSimilarity(Vertex v1, Vertex v2) {
        double dotProduct = 0;
        for (int i = 0; i < v1.getVector().length; i++) {
            dotProduct += v1.getVector()[i] * v2.getVector()[i];
        }
        double norm1 = norm(v1.getVector());
        double norm2 = norm(v2.getVector());
        return dotProduct / (norm1 * norm2);
    }

    static double cosineDistance(Vertex v1, Vertex v2) {
        return 1 - cosineSimilarity(v1, v2);
    }

    private static double norm(double[] vector) {
        double sum = 0;
        for (double v : vector) {
            sum += v * v;
        }
        return Math.sqrt(sum);
    }

    public List<Vertex> getAllVertex() {
        return this.vertices;
    }

    private static class VertexDistance {
        Vertex vertex;
        double distance;

        VertexDistance(Vertex v, double d) {
            vertex = v;
            distance = d;
        }
    }

    public void removeVertex(Vertex v) {
        vertices.remove(v);
        for (int l = 0; l <= currentMaxLevel; l++) {
            entryPoints.get(l).remove(v);
        }

        for (Vertex other : vertices) {
            for (int l = 0; l <= other.getMaxLevel(); l++) {
                other.removeEdge(v, l);
            }
        }

        for (int l = 0; l <= v.getMaxLevel(); l++) {
            v.getEdges(l).clear();
        }

        if (vertices.isEmpty()) {
            currentMaxLevel = -1;
        } else {
            int newMax = 0;
            for (Vertex vertex : vertices) {
                if (vertex.getMaxLevel() > newMax) {
                    newMax = vertex.getMaxLevel();
                }
            }
            currentMaxLevel = newMax;
        }
    }

    public void updateMetadata(Vertex v, Map<String, Object> newMetadata) {
        v.getMetadata().putAll(newMetadata);
    }

    public List<Vertex> search(double[] queryVector, int k) {
        Vertex queryVertex = new Vertex(queryVector, new HashMap<>());
        List<Vertex> candidates = new ArrayList<>();

        for (int level = currentMaxLevel; level >= 0; level--) {
            List<Vertex> initialCandidates = entryPoints.get(level);
            List<Vertex> levelCandidates = greedySearchForSearch(queryVertex, level, initialCandidates);
            candidates.addAll(levelCandidates);
        }

        candidates.sort(getVertexComparator(queryVertex));
        return candidates.subList(0, Math.min(candidates.size(), k));
    }

    static Comparator<Vertex> getVertexComparator(Vertex queryVertex) {
        return Comparator.comparingDouble(v -> cosineDistance(v, queryVertex));
    }

    private List<Vertex> greedySearchForSearch(Vertex queryVertex, int level, List<Vertex> initialCandidates) {
        Set<Vertex> visited = new HashSet<>();
        PriorityQueue<VertexDistance> candidates = new PriorityQueue<>(Comparator.comparingDouble(vd -> vd.distance));

        for (Vertex start : initialCandidates) {
            candidates.add(new VertexDistance(start, cosineDistance(queryVertex, start)));
        }

        List<Vertex> best = new ArrayList<>();
        while (!candidates.isEmpty()) {
            VertexDistance current = candidates.poll();
            Vertex v = current.vertex;
            if (visited.contains(v)) continue;
            visited.add(v);
            best.add(v);

            for (Vertex neighbor : v.getEdges(level)) {
                if (!visited.contains(neighbor)) {
                    double dist = cosineDistance(queryVertex, neighbor);
                    candidates.add(new VertexDistance(neighbor, dist));
                }
            }
        }

        best.sort(Comparator.comparingDouble(v -> cosineDistance(queryVertex, v)));
        return best;
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
            index.addVertex(vector, Map.of("id", Arrays.toString(vector)));
        }

        double[] queryVector = {5.0, 6.0, 7.0};
        Vertex queryVertex = new Vertex(queryVector);
        LOGGER.info("Searching vertex: {}, Metadata: {}", Arrays.toString(queryVertex.getVector()), queryVertex.getMetadata("id"));

        System.out.println();
        final List<Vertex> similarVertices = index.search(queryVector, 5);
        for (Vertex v : similarVertices) {
            LOGGER.info("Similar vertex: {}, Metadata: {}, Distance: {}", Arrays.toString(v.getVector()), v.getMetadata("id"), HNSWIndex.cosineSimilarity(queryVertex, v));
        }

        System.out.println();
        for (Vertex v : index.getAllVertex().stream().sorted(HNSWIndex.getVertexComparator(queryVertex)).limit(similarVertices.size() * 3L).toList()) {
            LOGGER.info("All vertex: {}, Metadata: {}, Distance: {}", Arrays.toString(v.getVector()), v.getMetadata("id"), HNSWIndex.cosineSimilarity(queryVertex, v));
        }
    }
}
