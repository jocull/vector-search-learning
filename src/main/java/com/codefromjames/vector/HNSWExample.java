package com.codefromjames.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.lang.Math;
import java.util.stream.IntStream;

class Vertex {
    private double[] vector;
    private final double norm;
    private Map<String, Object> metadata;
    private int maxLevel;
    private List<List<Vertex>> edges;

    public Vertex(double[] vector) {
        this(vector, Map.of());
    }

    public Vertex(double[] vector, Map<String, Object> metadata) {
        this.vector = vector;
        this.norm = norm(vector);
        this.metadata = new HashMap<>(metadata);
        this.maxLevel = 0;
        this.edges = new ArrayList<>();
        for (int i = 0; i <= HNSWIndex.MAX_LEVEL; i++) {
            edges.add(new ArrayList<>());
        }
    }

    private static double norm(double[] vector) {
        double sum = 0;
        for (double val : vector) {
            sum += val * val;
        }
        return Math.sqrt(sum);
    }

    public void setMaxLevel(int level) {
        this.maxLevel = level;
        while (edges.size() <= level) {
            edges.add(new ArrayList<>());
        }
    }

    public double getNorm() {
        return norm;
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
    static final int MAX_LEVEL = 8;

    private static final double LEVEL_PROBABILITY = 0.1;
    private static final int M = 5;

    private List<Vertex> vertices = new ArrayList<>();
    private List<List<Vertex>> entryPoints = new ArrayList<>();
    private int currentMaxLevel = -1;

    public void addVertex(double[] vector, Map<String, Object> metadata) {
        Vertex newVertex = new Vertex(vector, metadata);
        int chosenLevel = 0;
        while (Math.random() < LEVEL_PROBABILITY && chosenLevel < MAX_LEVEL) {
            chosenLevel++;
        }
        newVertex.setMaxLevel(chosenLevel);

        if (chosenLevel > currentMaxLevel) {
            currentMaxLevel = chosenLevel;
            while (entryPoints.size() <= chosenLevel) {
                entryPoints.add(new ArrayList<>());
            }
        }

        for (int currentLevel = 0; currentLevel <= chosenLevel; currentLevel++) {
            List<Vertex> neighbors = greedySearch(newVertex, currentLevel);
            neighbors.sort(Comparator.comparingDouble(v -> cosineDistance(newVertex, v)));
            if (neighbors.size() > M) {
                neighbors = neighbors.subList(0, M);
            }

            for (Vertex neighbor : neighbors) {
                newVertex.addEdge(neighbor, currentLevel);
                neighbor.addEdge(newVertex, currentLevel);
            }
        }

        // Update entry points for the new level
        if (chosenLevel == entryPoints.size() - 1) {
            entryPoints.get(chosenLevel).add(newVertex);
        }

        vertices.add(newVertex);
    }

    private List<Vertex> greedySearch(Vertex target, int level) {
        Set<Vertex> visited = new HashSet<>();
        // Max-heap to track the M farthest candidates (so we can replace them with closer ones)
        PriorityQueue<Vertex> candidates = new PriorityQueue<>(
                M,
                Comparator.<Vertex>comparingDouble(v -> cosineDistance(target, v)).reversed()
        );

        Vertex currentEntryPoint = getEntryPoint(level);
        if (currentEntryPoint == null) {
            return new ArrayList<>();
        }

        Vertex current = currentEntryPoint;
        visited.add(current);
        updateCandidates(candidates, current, target);

        while (true) {
            Vertex bestNeighbor = findBestNeighbor(target, current, level, visited);
            if (bestNeighbor == null) {
                break;
            }

            visited.add(bestNeighbor);
            updateCandidates(candidates, bestNeighbor, target);

            // Early exit if the next neighbor is worse than the farthest in the candidates
            if (candidates.size() == M && cosineDistance(target, bestNeighbor) > cosineDistance(target, candidates.peek())) {
                break;
            }

            current = bestNeighbor;
        }

        // Convert the max-heap to a list sorted by closest first
        List<Vertex> result = new ArrayList<>(candidates);
        Collections.reverse(result); // Reverse to get closest first
        return result.subList(0, Math.min(result.size(), M));
    }

    private Vertex getEntryPoint(int level) {
        List<Vertex> entryList = entryPoints.get(level);
        return entryList.isEmpty() ? null : entryList.get(0);
    }

    private void updateCandidates(PriorityQueue<Vertex> candidates, Vertex vertex, Vertex target) {
        double newDist = cosineDistance(target, vertex);
        if (candidates.size() < M) {
            candidates.add(vertex);
        } else {
            Vertex farthest = candidates.peek();
            if (newDist < cosineDistance(target, farthest)) {
                candidates.poll();
                candidates.add(vertex);
            }
        }
    }

    static double cosineSimilarity(Vertex v1, Vertex v2) {
        double dotProduct = 0;
        for (int i = 0; i < v1.getVector().length; i++) {
            dotProduct += v1.getVector()[i] * v2.getVector()[i];
        }
        double norm1 = v1.getNorm();
        double norm2 = v2.getNorm();
        return dotProduct / (norm1 * norm2);
    }

    static double cosineDistance(Vertex v1, Vertex v2) {
        return 1 - cosineSimilarity(v1, v2);
    }

    private static double dotProduct(double[] v1, double[] v2) {
        double sum = 0;
        for (int i = 0; i < v1.length; i++) {
            sum += v1[i] * v2[i];
        }
        return sum;
    }

    public List<Vertex> getAllVertex() {
        return this.vertices;
    }

    private static class VertexDistance {
        final Vertex vertex;
        final double distance;

        VertexDistance(Vertex v, double d) {
            vertex = v;
            distance = d;
        }

        @Override
        public String toString() {
            return "VertexDistance{" +
                    "vertex=" + vertex +
                    ", distance=" + distance +
                    '}';
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

        Vertex currentEntryPoint = entryPoints.get(currentMaxLevel).get(0); // Start at top layer's entry point

        for (int level = currentMaxLevel; level >= 0; level--) {
            List<Vertex> levelCandidates = greedySearchForSearch(queryVertex, level, currentEntryPoint, k);
            candidates.addAll(levelCandidates);

            // Update entry point for next layer to closest node in current layer's results
            if (!levelCandidates.isEmpty()) {
                currentEntryPoint = levelCandidates.get(0); // Closest node becomes next entry point
            }
        }

        // Final sort and return top-k
        candidates.sort(getVertexComparator(queryVertex));
        return candidates.subList(0, Math.min(candidates.size(), k));
    }

    private List<Vertex> greedySearchForSearch(
            Vertex queryVertex,
            int level,
            Vertex entryPoint,
            int k
    ) {
        Set<Vertex> visited = new HashSet<>();
        // Max-heap to track top k candidates (stores largest distances first)
        PriorityQueue<Vertex> candidateHeap = new PriorityQueue<>(
                (a, b) -> {
                    double distA = cosineDistance(queryVertex, a);
                    double distB = cosineDistance(queryVertex, b);
                    return Double.compare(distB, distA); // Reverse order for max-heap
                }
        );

        Vertex current = entryPoint;
        visited.add(current);
        updateCandidateHeap(candidateHeap, queryVertex, current, k);

        while (true) {
            Vertex bestNeighbor = findBestNeighbor(queryVertex, current, level, visited);

            if (bestNeighbor == null) {
                break; // No better neighbor found
            }

            visited.add(bestNeighbor);
            updateCandidateHeap(candidateHeap, queryVertex, bestNeighbor, k);

            // Early stopping: if new neighbor is worse than current worst candidate
            if (candidateHeap.size() == k &&
                    cosineDistance(queryVertex, bestNeighbor) > cosineDistance(queryVertex, candidateHeap.peek())) {
                break;
            }

            current = bestNeighbor;
        }

        // Convert heap to sorted list (ascending order)
        List<Vertex> results = new ArrayList<>();
        while (!candidateHeap.isEmpty()) {
            results.add(candidateHeap.poll());
        }
        Collections.reverse(results); // Reverse to get ascending order
        return results.subList(0, Math.min(results.size(), k));
    }

    private Vertex findBestNeighbor(Vertex target, Vertex current, int level, Set<Vertex> visited) {
        Vertex bestNeighbor = null;
        double minDistance = Double.MAX_VALUE;
        double[] targetVector = target.getVector();
        double targetNorm = target.getNorm();

        for (Vertex neighbor : current.getEdges(level)) {
            if (!visited.contains(neighbor)) {
                double[] neighborVector = neighbor.getVector();
                double neighborNorm = neighbor.getNorm();
                double dot = dotProduct(targetVector, neighborVector);
                double distance = 1 - (dot / (targetNorm * neighborNorm));
                if (distance < minDistance) {
                    minDistance = distance;
                    bestNeighbor = neighbor;
                }
            }
        }

        return bestNeighbor;
    }

    private void updateCandidateHeap(
            PriorityQueue<Vertex> heap,
            Vertex queryVertex,
            Vertex vertex,
            int k
    ) {
        double currentDist = cosineDistance(vertex, queryVertex);

        if (heap.size() < k) {
            heap.add(vertex);
        } else {
            // Replace only if better than the worst candidate
            Vertex worstCandidate = heap.peek();
            if (currentDist < cosineDistance(worstCandidate, queryVertex)) {
                heap.poll();
                heap.add(vertex);
            }
        }
    }

    static Comparator<Vertex> getVertexComparator(final Vertex queryVertex) {
        return Comparator.comparingDouble(v -> cosineDistance(v, queryVertex));
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
