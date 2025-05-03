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
                "distance=" + distance +
                ", level=" + vertex.getMaxLevel() +
                ", metadata=" + vertex.getMetadata() +
                ", vertex=" + vertex +
                '}';
    }

    @Override
    public final boolean equals(Object o) {
        if (o instanceof VertexDistance that) {
            return vertex.equals(that.vertex);
        }
        if (o instanceof Vertex that) {
            return vertex.equals(that);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return vertex.hashCode();
    }
}

class Vertex {
    static final int ML = 32;

    private final double[] vector;
    private final Map<String, Object> metadata;
    private final List<VertexDistanceHeap> edges;
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
            edges.add(new VertexDistanceHeap());
        }
    }

    public VertexDistanceHeap getEdges(int level) {
        return edges.get(level);
    }

    public boolean addEdge(int level, VertexDistance neighbor) {
        while (edges.size() <= level) {
            edges.add(new VertexDistanceHeap());
        }
        if (this == neighbor.vertex) {
            // Don't add self as a neighbor
            return false;
        }
        return edges.get(level).addIfCloserAndTrim(neighbor, ML);
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
                "metadata=" + metadata +
                ", maxLevel=" + maxLevel +
                ", vector=" + Arrays.toString(vector) +
                ", edges=[" + edges.stream().map(x -> Integer.toString(x.size())).collect(Collectors.joining(",")) + ']' +
                '}';
    }
}

class VertexDistanceHeap extends TreeSet<VertexDistance> {
    private static final Comparator<VertexDistance> COMPARATOR =
            Comparator.comparingDouble(vd -> vd.distance);

    public VertexDistanceHeap() {
        super(COMPARATOR);
    }

    public VertexDistanceHeap(Collection<? extends VertexDistance> c) {
        super(c);
        addAll(c);
    }

    public boolean addIfCloserAndTrim(VertexDistance vd, int size) {
        assert size > -1;
        if (size() < size
                || last().distance > vd.distance) {
            add(vd);
            trimMax(size);
            return true;
        }
        return false;
    }

    public void trimMin(int size) {
        assert size > -1;
        while (size() > size) {
            pollFirst();
        }
    }

    public void trimMax(int size) {
        assert size > -1;
        while (size() > size) {
            pollLast();
        }
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

        final Set<Vertex> remapped = new HashSet<>();
        VertexDistanceHeap propagateBest = null;
        for (int currentLevel = getCurrentMaxLevel(); currentLevel >= 0; currentLevel--) {
            // If there are no nodes in this layer...
            if (layers.get(currentLevel).isEmpty()) {
                if (currentLevel == newVertex.getMaxLevel()) {
                    // ...then it's the first entry - add it.
                    layers.get(currentLevel).add(newVertex);
                }
                // ...there's nothing else to do
                continue;
            }

            final VertexDistanceHeap best = getVertexDistancesAtLayer(newVector, propagateBest, currentLevel);
            // For nodes that are underneath us in the layer, associate newer or better peers
            for (VertexDistance vdNeighbor : best) {
                // If the new vertex would exist in this level, create neighbors for it
                if (level >= currentLevel) {
                    newVertex.addEdge(currentLevel, vdNeighbor);
                }
                // Check the edges for this neighbor at the target level to see if this is an improvement.
                // Don't process nodes we've already touched again if they remain the best from a previous layer.
                if (!remapped.contains(vdNeighbor.vertex)) {
                    remapped.add(vdNeighbor.vertex);
                    vdNeighbor.vertex.addEdge(newVertex.getMaxLevel(), new VertexDistance(newVertex, vdNeighbor.distance));
                }
            }

            // Insert the new node into the level it belongs to
            if (currentLevel == newVertex.getMaxLevel()) {
                layers.get(currentLevel).add(newVertex);
            }

            propagateBest = best;
        }
    }

    private VertexDistanceHeap getVertexDistancesAtLayer(double[] newVector, Collection<VertexDistance> startingNodes, int level) {
        if (layers.size() - 1 < level || layers.get(level).isEmpty()) {
            return new VertexDistanceHeap();
        }

        final Set<Vertex> visited = new HashSet<>();
        final VertexDistanceHeap candidates = new VertexDistanceHeap();
        final VertexDistanceHeap best = new VertexDistanceHeap();
        if (startingNodes != null && !startingNodes.isEmpty()) {
            // Find the best neighbors in this level, searching from the previous level's matches
            candidates.addAll(startingNodes);
        } else {
            // Find the best neighbors in this level, searching from the entry point
            candidates.add(new VertexDistance(layers.get(level).get(0), newVector));
        }

        while (!candidates.isEmpty()) {
            final VertexDistance current = candidates.pollFirst();
            if (visited.contains(current.vertex)) {
                continue;
            }

            visited.add(current.vertex);
            best.addIfCloserAndTrim(current, Vertex.ML);

            for (VertexDistance edge : current.vertex.getEdges(level)) {
                if (visited.contains(edge.vertex)) {
                    continue;
                }

                final VertexDistance currentEdge = new VertexDistance(edge.vertex, newVector);
                visited.add(currentEdge.vertex);
                if (best.addIfCloserAndTrim(currentEdge, Vertex.ML)) {
                    candidates.add(currentEdge);
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
        final VertexDistanceHeap best = new VertexDistanceHeap();
        for (int currentLevel = getCurrentMaxLevel(); currentLevel >= 0; currentLevel--) {
            if (layers.get(currentLevel).isEmpty()) {
                continue;
            }
            final VertexDistanceHeap candidates = new VertexDistanceHeap();
            // Carry over best from the last layer rather than starting over
            candidates.addAll(best);
            if (candidates.isEmpty()) {
                // Start at the entry point of this layer if there was nothing to carry
                final VertexDistance entryVd = new VertexDistance(layers.get(currentLevel).get(0), queryVector);
                candidates.add(entryVd);
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("[{}] Entry candidate: {} @ {}", currentLevel, entryVd.vertex.getMetadata("id"), entryVd.distance);
                }
            }

            // Find the best neighbors in this level, searching from the entry point
            final Set<Vertex> visited = new HashSet<>();
            while (!candidates.isEmpty()) {
                final VertexDistance current = candidates.pollFirst();
                if (visited.contains(current.vertex)) {
                    continue;
                }

                visited.add(current.vertex);
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("[{}] {} Visited vertex @ {}", currentLevel, current.vertex.getMetadata("id"), current.distance);
                }
                if (best.addIfCloserAndTrim(current, k)) {
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("[{}] !!!! {} Vertex is better @ {} vs best[{}] @ {}", currentLevel, current.vertex.getMetadata("id"), current.distance, best.size(), best.pollFirst().distance);
                    }
                }

                for (VertexDistance edge : current.vertex.getEdges(currentLevel)) {
                    if (visited.contains(edge.vertex)) {
                        continue;
                    }

                    final VertexDistance currentEdge = new VertexDistance(edge.vertex, queryVector);
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("[{}] {} Visited edge @ {}", currentLevel, currentEdge.vertex.getMetadata("id"), currentEdge.distance);
                    }
                    if (best.addIfCloserAndTrim(currentEdge, k)) {
                        // Plan to visit this vertex to check all of its edges also
                        candidates.add(currentEdge);
                        if (LOGGER.isTraceEnabled()) {
                            LOGGER.trace("[{}] !!!! {} Vertex is better @ {} vs best[{}] @ {}", currentLevel, currentEdge.vertex.getMetadata("id"), currentEdge.distance, best.size(), best.pollFirst().distance);
                        }
                    }
                }
            }
        }

        return best.stream()
                .map(v -> v.vertex)
                .toList();
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

        final int vectorWidth = 1000;
        LOGGER.info("Vectors generating... vector width = {}", vectorWidth);
        final List<double[]> data = IntStream.range(0, 10_000)
                .mapToObj(i -> randomVector(vectorWidth))
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
        final double[] queryVector = new double[vectorWidth];
        for (int i = 0; i < queryVector.length; i++) {
            queryVector[i] = 5.0 + i; // 5.0, 6.0, 7.0, ...
        }
        if (queryVector.length != data.get(0).length) {
            throw new IllegalStateException("Query vector didn't match data vector length! They cannot be compared!");
        }
        final Vertex queryVertex = new Vertex(queryVector);
        LOGGER.info("Searching vertex..."); // , Arrays.toString(queryVertex.getVector()));

        if (LOGGER.isDebugEnabled()) {
            System.out.println();
            LOGGER.debug("All vertex search begins...");
            for (int oLevel = layers.size() - 1; oLevel >= 0; oLevel--) { // For each layer...;
                final List<Vertex> vertices = layers.get(oLevel).stream()
                        .sorted(Comparator.comparingDouble(v -> CosineDistanceUtils.cosineSimilarity(queryVector, v.getVector())))
                        .toList();
                LOGGER.debug("Layer {} begins with {} nodes...", oLevel, vertices.size());
                for (Vertex v : vertices) {
                    LOGGER.debug("    - Node: {} @ {}, {}", v.getMetadata("id"), oLevel, CosineDistanceUtils.cosineSimilarity(queryVertex, v));
                    for (int level = 0; level <= v.getMaxLevel(); level++) {
                        LOGGER.debug("        - Edges @ {}: {} : {}", level, v.getEdges(level).stream().map(vd -> vd.vertex.getMetadata("id").toString()).sorted().collect(Collectors.joining(",")), CosineDistanceUtils.cosineSimilarity(v, queryVertex));
                    }
                }
                LOGGER.debug("Layer {} ends with {} nodes...", oLevel, vertices.size());
            }
        }

        System.out.println();
        LOGGER.info("Brute-force searching best matches...");
        final int topK = 20;
        final List<Vertex> allVertices = index.getAllVertex().stream()
                .sorted(Comparator.comparingDouble(v1 -> CosineDistanceUtils.cosineDistance(v1, queryVertex)))
                .toList();
        final List<Vertex> allVerticesDisplaySet = allVertices.stream()
                .limit(topK)
                .toList();
        for (Vertex v : allVerticesDisplaySet) {
            LOGGER.info("All vertex: Metadata: {} @ {}, Distance: {}", v.getMetadata("id"), v.getMaxLevel(), CosineDistanceUtils.cosineSimilarity(queryVertex, v));
            for (int level = 0; level <= v.getMaxLevel(); level++) {
                LOGGER.info("    - Edges @ {}: {}", level, v.getEdges(level).stream().map(vd -> vd.vertex.getMetadata("id").toString()).sorted().collect(Collectors.joining(",")));
            }
        }

        System.out.println();
        LOGGER.info("Index searching best matches...");
        final List<Vertex> similarVertices = index.search(queryVector, topK);
        LOGGER.info("...done. Accuracy out of {}:", allVertices.size());
        for (int i = 0; i < similarVertices.size(); i++) {
            final Vertex v = similarVertices.get(i);
            LOGGER.info("    - #{} is ID={} @ Brute-Force #{}", (i + 1), v.getMetadata("id"), (allVertices.indexOf(v) + 1));
        }

        System.out.println();
        for (Vertex v : similarVertices) {
            LOGGER.info("Similar vertex: Metadata: {}, Distance: {}", v.getMetadata("id"), CosineDistanceUtils.cosineSimilarity(queryVertex, v));
            for (int level = 0; level <= v.getMaxLevel(); level++) {
                LOGGER.info("    - Edges @ {}: {}", level, v.getEdges(level).stream().map(vd -> vd.vertex.getMetadata("id").toString()).sorted().collect(Collectors.joining(",")));
            }
        }
    }
}
