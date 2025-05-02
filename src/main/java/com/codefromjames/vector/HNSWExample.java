package com.codefromjames.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
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
        if (o instanceof Vertex that) {
            return vertex.equals(that);
        }
        if (o instanceof VertexDistance that) {
            return vertex.equals(that.vertex);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return vertex.hashCode();
    }
}

class Vertex {
    static final int ML = 16;

    private final double[] vector;
    private final Map<String, Object> metadata;
    private final List<VertexMaxHeap> edges;
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
            edges.add(new VertexMaxHeap());
        }
    }

    public PriorityQueue<VertexDistance> getEdges(int level) {
        return edges.get(level);
    }

    public void addEdge(int level, VertexDistance neighbor) {
        while (edges.size() <= level) {
            edges.add(new VertexMaxHeap());
        }

        final VertexMaxHeap layerEdges = edges.get(level);
        for (VertexDistance layerEdge : layerEdges) {
            if (layerEdge.vertex == neighbor.vertex) {
                // Exists already
                return;
            }
        }
        layerEdges.addAndTrimIfBetterThanWorst(neighbor, ML);
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
                ", edges=[" + edges.stream().map(x -> Integer.toString(x.size())).collect(Collectors.joining(",")) + ']' +
                '}';
    }
}

class VertexMinHeap extends PriorityQueue<VertexDistance> {
    private static final Comparator<VertexDistance> COMPARATOR =
            Comparator.comparingDouble(vd -> vd.distance);

    public VertexMinHeap() {
        super(COMPARATOR);
    }

    public VertexMinHeap(Collection<? extends VertexDistance> c) {
        this();
        addAll(c);
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

    @Override
    public boolean contains(Object o) {
        if (o instanceof Vertex v) {
            for (VertexDistance vd : this) {
                if (vd.vertex == v) {
                    return true;
                }
            }
            return false;
        }
        return super.contains(o);
    }

    public void addAndTrimIfBetterThanWorst(VertexDistance vd, int size) {
        if (!addIfBetterThanWorst(vd)) {
            return;
        }
        trim(size);
    }

    public void addAndTrim(VertexDistance currentEdge, int size) {
        add(currentEdge);
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
    private static final Logger LOGGER = LoggerFactory.getLogger(HNSWIndex.class);

    static final Random RANDOM = new Random(261707535563309L); // Nice distribution off a recent run
    private static final double LEVEL_PROBABILITY = 0.5;
    private static final int MAX_LEVEL = 16;

    private final List<List<Vertex>> layers = new ArrayList<>();

    private int getCurrentMaxLevel() {
        return layers.size() - 1;
    }

    public void addVertex(double[] newVector, Map<String, Object> metadata) {
        final Vertex newVertex = new Vertex(newVector, metadata);
        int level = 0;
        while (HNSWIndex.RANDOM.nextDouble() < LEVEL_PROBABILITY && level < MAX_LEVEL) {
            level++;
        }

        newVertex.setMaxLevel(level);
        while (layers.size() <= level) {
            layers.add(new ArrayList<>());
        }

        VertexMaxHeap propagateBest = null;
        for (int currentLevel = level; currentLevel >= 0; currentLevel--) {
            // If there are no nodes in this layer, then it's the first entry. Just add it.
            if (layers.get(currentLevel).isEmpty()) {
                layers.get(currentLevel).add(newVertex);
                continue;
            }

            final VertexMaxHeap best = getVertexDistancesAtLayer(newVector, propagateBest, currentLevel);
            for (VertexDistance vdNeighbor : best) {
                newVertex.addEdge(currentLevel, vdNeighbor);
                vdNeighbor.vertex.addEdge(currentLevel, new VertexDistance(newVertex, vdNeighbor.distance));
            }

            // Insert the new node into the level it belongs to
            if (currentLevel == newVertex.getMaxLevel()) {
                layers.get(currentLevel).add(newVertex);
            }

            propagateBest = best;
        }

        // Check up one level also for updates to parent vertex
        {
            final int levelUp = level + 1;
            final VertexMaxHeap best = getVertexDistancesAtLayer(newVector, null, levelUp);
            for (VertexDistance vdNeighbor : best) {
                newVertex.addEdge(level, vdNeighbor);
                vdNeighbor.vertex.addEdge(level, new VertexDistance(newVertex, vdNeighbor.distance));
            }
        }
    }

    private VertexMaxHeap getVertexDistancesAtLayer(double[] newVector, Collection<VertexDistance> startingNodes, int level) {
        if (layers.size() - 1 < level || layers.get(level).isEmpty()) {
            return new VertexMaxHeap();
        }

        final Set<Vertex> visited = new HashSet<>();
        final VertexMinHeap candidates = new VertexMinHeap();
        final VertexMaxHeap best = new VertexMaxHeap();
        if (startingNodes != null && !startingNodes.isEmpty()) {
            // Find the best neighbors in this level, searching from the previous level's matches
            candidates.addAll(startingNodes);
        } else {
            // Find the best neighbors in this level, searching from the entry point
            candidates.add(new VertexDistance(layers.get(level).get(0), newVector));
        }

        while (!candidates.isEmpty()) {
            final VertexDistance current = candidates.poll();
            if (visited.contains(current.vertex)) {
                continue;
            }

            visited.add(current.vertex);
            if (best.isEmpty()
                    || best.size() < Vertex.ML
                    || best.peek().distance > current.distance) {
                best.addAndTrim(current, Vertex.ML);
            }

            for (VertexDistance edge : current.vertex.getEdges(level)) {
                if (visited.contains(edge.vertex)) {
                    continue;
                }

                final VertexDistance currentEdge = new VertexDistance(edge.vertex, newVector);
                if (best.isEmpty()
                        || best.size() < Vertex.ML
                        || best.peek().distance > currentEdge.distance) {
                    best.addAndTrim(currentEdge, Vertex.ML);
                    candidates.add(currentEdge);
                    visited.add(currentEdge.vertex);
                }
            }
        }
        return best;
    }

    public List<List<Vertex>> getAllLayers() {
        return List.copyOf(layers);
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
        final VertexMaxHeap best = new VertexMaxHeap();
        for (int currentLevel = getCurrentMaxLevel(); currentLevel >= 0; currentLevel--) {
            if (layers.get(currentLevel).isEmpty()) {
                continue;
            }
            final VertexMinHeap candidates = new VertexMinHeap();
            // Carry over best from the last layer rather than starting over
            candidates.addAll(best);
            if (candidates.isEmpty()) {
                // Start at the entry point of this layer if there was nothing to carry
                candidates.add(new VertexDistance(layers.get(currentLevel).get(0), queryVector));
                LOGGER.debug("[{}] Entry candidate: {} @ {}", currentLevel, candidates.peek().vertex.getMetadata("id"), candidates.peek().distance);
            }

            // Find the best neighbors in this level, searching from the entry point
            final Set<Vertex> visited = new HashSet<>();
            while (!candidates.isEmpty()) {
                final VertexDistance current = candidates.poll();
                if (visited.contains(current.vertex)) {
                    continue;
                }

                visited.add(current.vertex);
                LOGGER.debug("[{}] {} Visited vertex @ {}", currentLevel, current.vertex.getMetadata("id"), current.distance);
                if (best.isEmpty()
                        || best.size() < k
                        || best.peek().distance > current.distance) {
                    if (!best.contains(current.vertex)) {
                        best.addAndTrim(current, k);
                        LOGGER.debug("[{}] !!!! {} Vertex is better @ {} vs best[{}] @ {}", currentLevel, current.vertex.getMetadata("id"), current.distance, best.size(), new VertexMinHeap(best).peek().distance);
                    }
                }

                for (VertexDistance edge : current.vertex.getEdges(currentLevel)) {
                    if (visited.contains(edge.vertex)) {
                        continue;
                    }

                    final VertexDistance currentEdge = new VertexDistance(edge.vertex, queryVector);
                    LOGGER.debug("[{}] {} Visited edge @ {}", currentLevel, currentEdge.vertex.getMetadata("id"), currentEdge.distance);
                    if (best.isEmpty()
                            || best.size() < k
                            || best.peek().distance > currentEdge.distance) {
                        if (!best.contains(currentEdge.vertex)) {
                            best.addAndTrim(currentEdge, k);
                            candidates.add(currentEdge);
                            LOGGER.debug("[{}] !!!! {} Edge is better @ {} vs best[{}] @ {}", currentLevel, currentEdge.vertex.getMetadata("id"), currentEdge.distance, best.size(), new VertexMinHeap(best).peek().distance);
                        }
                    }
                }
            }
        }

        final List<Vertex> result = new ArrayList<>(best.size());
        while (!best.isEmpty()) {
            result.add(best.poll().vertex);
        }
        Collections.reverse(result);
        return result;
    }
}

public class HNSWExample {
    static final Logger LOGGER = LoggerFactory.getLogger(HNSWExample.class);

    static double[] randomVector(int width) {
        final double[] vector = new double[width];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = HNSWIndex.RANDOM.nextDouble(-1000, 1000);
        }
        return vector;
    }

    public static void main(String[] args) {
        final HNSWIndex index = new HNSWIndex();

        final int vectorSize = 10;
        LOGGER.info("Vectors generating... vector size = {}", vectorSize);
        final List<double[]> data = IntStream.range(0, 1_000_000)
                .mapToObj(i -> randomVector(vectorSize))
                .toList();

        LOGGER.info("...done. Layers generating...");
        for (int i = 0; i < data.size(); i++) {
            final double[] vector = data.get(i);
            index.addVertex(vector, Map.of("id", i));
        }
        LOGGER.info("...done.");

        System.out.println();
        LOGGER.info("Exploring layer density...");
        final List<List<Vertex>> layers = index.getAllLayers();
        for (int layer = layers.size() - 1; layer >= 0; layer--) {
            LOGGER.info("    - Layer {} : {}", layer, layers.get(layer).size());
        }

        System.out.println();
        final double[] queryVector = new double[vectorSize];
        for (int i = 0; i < queryVector.length; i++) {
            queryVector[i] = 5.0 + i; // 5.0, 6.0, 7.0, ...
        }
        if (queryVector.length != data.get(0).length) {
            throw new IllegalStateException("Query vector didn't match data vector length! They cannot be compared!");
        }
        final Vertex queryVertex = new Vertex(queryVector);
        LOGGER.info("Searching vertex: {}", Arrays.toString(queryVertex.getVector()));

        if (LOGGER.isDebugEnabled()) {
            System.out.println();
            LOGGER.debug("All vertex search begins...");
            for (int oLevel = layers.size() - 1; oLevel >= 0; oLevel--) { // For each layer...;
                final List<Vertex> vertices = layers.get(oLevel).stream()
                        .sorted(Comparator.comparingDouble(v -> CosineDistanceUtils.cosineSimilarity(queryVector, v.getVector())))
                        .toList();
                LOGGER.debug("Layer {} begins with {} nodes...", oLevel, vertices.size());
                for (Vertex v : vertices) {
                    LOGGER.debug("    - Node: {} @ {}, {} : {}", v.getMetadata("id"), oLevel, Arrays.toString(v.getVector()), CosineDistanceUtils.cosineSimilarity(queryVertex, v));
                    for (int level = 0; level <= v.getMaxLevel(); level++) {
                        LOGGER.debug("        - Edges @ {}: {} : {}", level, v.getEdges(level).stream().map(vd -> vd.vertex.getMetadata("id").toString()).sorted().collect(Collectors.joining(",")), CosineDistanceUtils.cosineSimilarity(v, queryVertex));
                    }
                }
                LOGGER.debug("Layer {} ends with {} nodes...", oLevel, vertices.size());
            }
        }

        System.out.println();
        LOGGER.info("Brute-force searching best matches...");
        final int topK = 5;
        final List<Vertex> allVertices = index.getAllVertex().stream()
                .sorted(Comparator.comparingDouble(v1 -> CosineDistanceUtils.cosineDistance(v1, queryVertex)))
                .limit(topK * 3L)
                .toList();
        for (Vertex v : allVertices) {
            LOGGER.info("All vertex: {}, Metadata: {} @ {}, Distance: {}", Arrays.toString(v.getVector()), v.getMetadata("id"), v.getMaxLevel(), CosineDistanceUtils.cosineSimilarity(queryVertex, v));
        }

        System.out.println();
        LOGGER.info("Index searching best matches...");
        final List<Vertex> similarVertices = index.search(queryVector, topK);
        for (Vertex v : similarVertices) {
            LOGGER.info("Similar vertex: {}, Metadata: {}, Distance: {}", Arrays.toString(v.getVector()), v.getMetadata("id"), CosineDistanceUtils.cosineSimilarity(queryVertex, v));
            for (int level = 0; level <= v.getMaxLevel(); level++) {
                LOGGER.info("    - Edges @ {}: {}", level, v.getEdges(level).stream().map(vd -> vd.vertex.getMetadata("id").toString()).sorted().collect(Collectors.joining(",")));
            }
        }
    }
}
