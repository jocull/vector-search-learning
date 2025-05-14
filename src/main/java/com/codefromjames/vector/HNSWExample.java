package com.codefromjames.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
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

    static double dotProduct(double[] v1, double[] v2) {
        double dotProduct = 0;
        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
        }
        return dotProduct;
    }

    static double cosineSimilarity(double[] v1, double[] v2) {
        final double dotProduct = dotProduct(v1, v2);
        final double norm1 = norm(v1);
        final double norm2 = norm(v2);
        return dotProduct / (norm1 * norm2);
    }

    static double cosineSimilarity(Vertex vtx1, Vertex vtx2) {
        final double[] v1 = vtx1.getVector();
        final double[] v2 = vtx2.getVector();
        final double dotProduct = dotProduct(v1, v2);
        final double norm1 = vtx1.getNorm();
        final double norm2 = vtx2.getNorm();
        return dotProduct / (norm1 * norm2);
    }

    static double cosineDistance(double[] v1, double[] v2) {
        return 1 - cosineSimilarity(v1, v2);
    }

    static double cosineDistance(Vertex vtx1, Vertex vtx2) {
        return 1 - cosineSimilarity(vtx1, vtx2);
    }
}

class ListPartitioner {
    static <T> List<List<T>> partition(List<T> list, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be positive.");
        }
        final List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            final int end = Math.min(i + size, list.size());
            final List<T> sublist = new ArrayList<>();
            for (int j = i; j < end; j++) {
                sublist.add(list.get(j));
            }
            result.add(sublist);
        }
        return result;
    }

    static <T> List<List<T>> partitionIntoGroups(List<T> list, int groupCount) {
        if (groupCount <= 0) {
            throw new IllegalArgumentException("Group count must be positive.");
        }

        final int size = list.size();
        final int baseSize = size / groupCount;
        final int remainder = size % groupCount;

        List<List<T>> result = new ArrayList<>();
        int index = 0;

        for (int i = 0; i < groupCount; i++) {
            final int currentSize = baseSize + (i < remainder ? 1 : 0);
            final List<T> group = new ArrayList<>();
            for (int j = 0; j < currentSize; j++) {
                group.add(list.get(index));
                index++;
            }
            result.add(group);
        }
        return result;
    }
}

class VertexDistance {
    final Vertex vertex;
    final double distance;

    VertexDistance(Vertex source, double distance) {
        this.vertex = source;
        this.distance = distance;
    }

    VertexDistance(Vertex source, Vertex target) {
        this.vertex = source;
        this.distance = CosineDistanceUtils.cosineDistance(source, target);
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
    private final double norm;
    private final Map<String, Object> metadata;
    private final List<VertexDistanceHeap> edges;
    private int maxLevel;

    public Vertex(double[] vector) {
        this(vector, Map.of());
    }

    public Vertex(double[] vector, Map<String, Object> metadata) {
        this.vector = vector;
        this.norm = CosineDistanceUtils.norm(vector);
        this.metadata = new HashMap<>(metadata);
        this.maxLevel = 0;
        this.edges = new ArrayList<>();
    }

    public void setMaxLevel(int level) {
        this.maxLevel = level;
        while (edges.size() <= level) {
            edges.add(VertexDistanceHeap.create());
        }
    }

    public VertexDistanceHeap getEdges(int level) {
        return edges.get(level);
    }

    public boolean addEdge(int level, VertexDistance neighbor) {
        while (edges.size() <= level) { // TODO: Can this just be done up front instead?
            edges.add(VertexDistanceHeap.create());
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

    public double getNorm() {
        return norm;
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
                ", vector=[" + vector.length + "]" +
                ", edges=[" + edges.stream().map(x -> Integer.toString(x.size())).collect(Collectors.joining(",")) + ']' +
                '}';
    }
}

interface VertexDistanceHeap {
    static VertexDistanceHeap create() {
        return new VertexDistanceTreeSet();
    }

    VertexDistance pollFirst();

    boolean addIfCloserAndTrim(VertexDistance current, int efSearch);

    boolean add(VertexDistance vd);

    void addAll(Iterable<VertexDistance> vertexDistances);

    boolean isEmpty();

    int size();

    // TODO: Wow, is this dirty?! But so powerful! The interface could be changed to expose less.
    void withLockHeldRun(Consumer<TreeSet<VertexDistance>> fnConsumer);

    <T> T withLockHeldGet(Function<TreeSet<VertexDistance>, T> fnConsumer);
}

class VertexDistanceTreeSet implements VertexDistanceHeap {
    private static final Comparator<VertexDistance> COMPARATOR = Comparator.comparingDouble(vd -> vd.distance);

    private final ReentrantLock lock = new ReentrantLock();
    private final TreeSet<VertexDistance> delegate = new TreeSet<>(COMPARATOR);

    public VertexDistanceTreeSet() {
    }

    public VertexDistanceTreeSet(Collection<? extends VertexDistance> c) {
        delegate.addAll(c);
    }

    @Override
    public String toString() {
        try {
            lock.lock();
            return delegate.toString();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public VertexDistance pollFirst() {
        try {
            lock.lock();
            return delegate.pollFirst();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean add(VertexDistance vd) {
        try {
            lock.lock();
            return delegate.add(vd);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isEmpty() {
        try {
            lock.lock();
            return delegate.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        try {
            lock.lock();
            return delegate.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void addAll(Iterable<VertexDistance> vertexDistances) {
        try {
            lock.lock();
            for (VertexDistance vd : vertexDistances) {
                delegate.add(vd);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean addIfCloserAndTrim(VertexDistance vd, int size) {
        try {
            lock.lock();
            if (delegate.size() < size
                    || delegate.last().distance > vd.distance) {
                delegate.add(vd);
                while (delegate.size() > size) {
                    delegate.pollLast();
                }
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void withLockHeldRun(Consumer<TreeSet<VertexDistance>> fnConsume) {
        withLockHeldGet(delegate -> {
            fnConsume.accept(delegate);
            return (Void) null;
        });
    }

    @Override
    public <T> T withLockHeldGet(Function<TreeSet<VertexDistance>, T> fnAccept) {
        try {
            lock.lock();
            return fnAccept.apply(delegate);
        } finally {
            lock.unlock();
        }
    }
}

class HNSWIndex {
    private static final Logger LOGGER = LoggerFactory.getLogger(HNSWIndex.class);

    static final Random RANDOM = new Random(261707535563309L); // Nice distribution off a recent run
    private static final double LEVEL_PROBABILITY = 0.1;
    private static final int MAX_LEVEL = 8;

    private static int EF_CONSTRUCTION = 100;
    private static int EF_SEARCH = 200;

    private final List<List<Vertex>> layers = new ArrayList<>();

    private int getCurrentMaxLevel() {
        return layers.size() - 1;
    }

    public void addVertices(Collection<Vertex> newVertices) {
        // Step 1:
        // Assign levels to the incoming batch of vertices and ensure we have the level prepared.
        int maxLevel = 0;
        for (Vertex newVertex : newVertices) {
            int level = 0;
            while (HNSWIndex.RANDOM.nextDouble() < LEVEL_PROBABILITY && level < MAX_LEVEL) {
                level++;
            }
            maxLevel = Math.max(maxLevel, level);
            newVertex.setMaxLevel(level);
        }
        while (layers.size() <= maxLevel) {
            layers.add(new ArrayList<>(1000));
        }

        // Step 2:
        // For each incoming vertex, compare it to all others in the *existing* graph.
        // We'll find the best relationships here first, to ensure we're really checking the graph
        // rather than giving up on a full buffer of existing connections.
        final Map<Vertex, Map<Integer, VertexDistanceHeap>> bestVertexInsertions = newVertices.parallelStream()
                .collect(Collectors.toMap(
                        k -> k,
                        newVertex -> {
                            final Map<Integer, VertexDistanceHeap> layerBest = new HashMap<>(getCurrentMaxLevel());
                            VertexDistanceHeap propagateBest = null;
                            for (int currentLevel = getCurrentMaxLevel(); currentLevel >= 0; currentLevel--) {
                                // If there are no nodes in this layer...
                                if (layers.get(currentLevel).isEmpty()) {
                                    // ...there's nothing else to do
                                    continue;
                                }

                                final VertexDistanceHeap best = getVertexDistancesAtLayer(newVertex, propagateBest, currentLevel);
                                if (LOGGER.isTraceEnabled()) {
                                    LOGGER.trace("{} : Retained {} best at layer {}", newVertex.getMetadata("id"), best.size(), currentLevel);
                                }
                                propagateBest = best;
                                layerBest.put(currentLevel, best);
                            }
                            return layerBest;
                        }));

        // Step 3:
        // For each incoming vertex, compare it to all others in the batch.
        // We need to do this to ensure that nodes batched together don't miss relationships between themselves.
        // If the incoming relationships are better than what's in the graph then we'll take them instead.
        // If they are not, they will be ignored.
        newVertices.parallelStream()
                .forEach(self -> {
                    // Brute force match this vertex with its closest peers in the batch.
                    // We do this because they won't be visible in the broader graph yet, so we
                    // precalculate them as edges to capture any potential close matches.
                    for (Vertex other : newVertices) {
                        if (self == other) {
                            continue; // Don't match self
                        }

                        // Find the common denominator level between the two and map down the levels from the top
                        final int commonLevel = Math.min(self.getMaxLevel(), other.getMaxLevel());
                        final double distance = CosineDistanceUtils.cosineDistance(self, other);
                        for (int level = commonLevel; level >= 0; level--) {
                            // TODO:
                            //  because self -> other and other -> self are the same relationship
                            //  we could probably optimize this somehow... maybe with caching?
                            //  not sure... and not sure if the overhead makes it worse anyways
                            self.addEdge(level, new VertexDistance(other, distance));
                        }
                    }
                });

        // Step 4:
        // Iterate over the best vertex matches at the layers we calculated above.
        // Update the edges on their targets, replacing any earlier relationships.
        bestVertexInsertions.entrySet()
                .parallelStream()
                .forEach(vertexMapEntry -> {
                    final Vertex newVertex = vertexMapEntry.getKey();
                    final Map<Integer, VertexDistanceHeap> bestByLayer = vertexMapEntry.getValue();
                    final Set<Vertex> remapped = new HashSet<>();
                    for (int currentLevel = getCurrentMaxLevel(); currentLevel >= 0; currentLevel--) {
                        // For nodes that are underneath us in the layer, associate newer or better peers
                        final VertexDistanceHeap best = bestByLayer.get(currentLevel);
                        if (best == null) {
                            continue;
                        }
                        final int thisLevel = currentLevel;
                        best.withLockHeldRun(neighbors -> {
                            for (VertexDistance vdNeighbor : neighbors) {
                                // If the new vertex would exist in this level, create neighbors for it
                                if (newVertex.getMaxLevel() >= thisLevel) {
                                    newVertex.addEdge(thisLevel, vdNeighbor);
                                }
                                // Check the edges for this neighbor at the target level to see if this is an improvement.
                                // Don't process nodes we've already touched again if they remain the best from a previous layer.
                                if (remapped.add(vdNeighbor.vertex)) {
                                    vdNeighbor.vertex.addEdge(newVertex.getMaxLevel(), new VertexDistance(newVertex, vdNeighbor.distance));
                                }
                            }
                        });
                    }
                });

        // Step 5:
        // Complete the process by adding them into the graph after connections are fully built.
        newVertices.forEach(newVertex ->
                layers.get(newVertex.getMaxLevel()).add(newVertex));
    }

    private VertexDistanceHeap getVertexDistancesAtLayer(Vertex newVertex, VertexDistanceHeap startingNodes, int level) {
        final VertexDistanceHeap best = VertexDistanceHeap.create();
        if (layers.size() - 1 < level || layers.get(level).isEmpty()) {
            return best;
        }

        final Set<Vertex> visited = new HashSet<>(EF_CONSTRUCTION);
        final VertexDistanceHeap candidates = VertexDistanceHeap.create();
        if (startingNodes != null) {
            // Find the best neighbors in this level, searching from the previous level's matches
            startingNodes.withLockHeldRun(candidates::addAll);
        }
        if (candidates.isEmpty()) {
            // Find the best neighbors in this level, searching from the entry point
            candidates.add(new VertexDistance(layers.get(level).get(0), newVertex));
        }

        while (!candidates.isEmpty()) {
            final VertexDistance current = candidates.pollFirst();
            if (!visited.add(current.vertex)) {
                continue;
            }
            if (!best.addIfCloserAndTrim(current, EF_CONSTRUCTION)) { // TODO: Construction specific value if this method is reused later!
                // If this vertex is not better, don't consider its edges either
                continue;
            }

            current.vertex.getEdges(level).withLockHeldRun(edges -> {
                for (VertexDistance edge : edges) {
                    if (visited.contains(edge.vertex)) {
                        continue;
                    }

                    final VertexDistance currentEdge = new VertexDistance(edge.vertex, newVertex);
                    candidates.add(currentEdge);
                }
            });
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
        final Vertex queryVertex = new Vertex(queryVector);
        final VertexDistanceHeap best = VertexDistanceHeap.create();
        for (int currentLevel = getCurrentMaxLevel(); currentLevel >= 0; currentLevel--) {
            if (layers.get(currentLevel).isEmpty()) {
                continue;
            }
            final VertexDistanceHeap candidates = VertexDistanceHeap.create();
            // Carry over best from the last layer rather than starting over
            best.withLockHeldRun(candidates::addAll);
            if (candidates.isEmpty()) {
                // Start at the entry point of this layer if there was nothing to carry
                final VertexDistance entryVd = new VertexDistance(layers.get(currentLevel).get(0), queryVertex);
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
                if (best.addIfCloserAndTrim(current, EF_SEARCH)) {
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("[{}] !!!! {} Vertex is better @ {} vs best[{}] @ {}", currentLevel, current.vertex.getMetadata("id"), current.distance, best.size(), best.pollFirst().distance);
                    }
                }

                final int thisLevel = currentLevel;
                current.vertex.getEdges(thisLevel).withLockHeldRun(edges -> {
                    for (VertexDistance edge : edges) {
                        if (visited.contains(edge.vertex)) {
                            continue;
                        }

                        final VertexDistance currentEdge = new VertexDistance(edge.vertex, queryVertex);
                        if (LOGGER.isTraceEnabled()) {
                            LOGGER.trace("[{}] {} Visited edge @ {}", thisLevel, currentEdge.vertex.getMetadata("id"), currentEdge.distance);
                        }
                        if (best.addIfCloserAndTrim(currentEdge, EF_SEARCH)) {
                            // Plan to visit this vertex to check all of its edges also
                            candidates.add(currentEdge);
                            if (LOGGER.isTraceEnabled()) {
                                LOGGER.trace("[{}] !!!! {} Vertex is better @ {} vs best[{}] @ {}", thisLevel, currentEdge.vertex.getMetadata("id"), currentEdge.distance, best.size(), best.pollFirst().distance);
                            }
                        }
                    }
                });
            }
        }

        return best.withLockHeldGet(nodes -> nodes.stream()
                .map(v -> v.vertex)
                .limit(k) // Clamp the EF_SEARCH to get the best set
                .toList());
    }

    @Override
    public String toString() {
        return "HNSWIndex{" +
                "maxLevels=" + MAX_LEVEL +
                ", probability=" + LEVEL_PROBABILITY +
                ", efConstruction=" + EF_CONSTRUCTION +
                ", efSearch=" + EF_SEARCH +
                ", maxEdges=" + Vertex.ML +
                '}';
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
        LOGGER.info("HNSW settings: {}", index);

        final int vectorWidth = 1_000;
        final int vectorRange = 100_000;
        LOGGER.info("Vectors generating... vector width = {} x range = {}", vectorWidth, vectorRange);
        final List<Vertex> data = IntStream.range(0, vectorRange)
                .mapToObj(i -> new Vertex(randomVector(vectorWidth), Map.of("id", i)))
                .toList();
        LOGGER.info("...done.");

        System.out.println();
        LOGGER.info("Layers generating...");
        {
            final List<List<Vertex>> partitions = ListPartitioner.partition(data, 256);
            int i = 0;
            for (List<Vertex> partition : partitions) {
                i += partition.size();
                index.addVertices(partition);
                LOGGER.info("{}...", i);
            }
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
        if (queryVector.length != data.get(0).getVector().length) {
            throw new IllegalStateException("Query vector didn't match data vector length! They cannot be compared!");
        }
        final Vertex queryVertex = new Vertex(queryVector);
        LOGGER.info("Searching vertex...");

        if (LOGGER.isDebugEnabled()) {
            System.out.println();
            LOGGER.debug("All vertex search begins...");
            for (int oLevel = layers.size() - 1; oLevel >= 0; oLevel--) {
                final List<Vertex> vertices = layers.get(oLevel).stream()
                        .sorted(Comparator.comparingDouble(v -> CosineDistanceUtils.cosineSimilarity(queryVector, v.getVector())))
                        .toList();
                LOGGER.debug("Layer {} begins with {} nodes...", oLevel, vertices.size());
                for (Vertex v : vertices) {
                    LOGGER.debug("    - Node: {} @ {}, {}", v.getMetadata("id"), oLevel, CosineDistanceUtils.cosineSimilarity(queryVertex, v));
                    for (int level = 0; level <= v.getMaxLevel(); level++) {
                        final String edgeIds = v.getEdges(level).withLockHeldGet(edges -> edges.stream().map(vd -> vd.vertex.getMetadata("id").toString()).sorted().collect(Collectors.joining(",")));
                        LOGGER.debug("        - Edges @ {}: {} : {}", level, edgeIds, CosineDistanceUtils.cosineSimilarity(v, queryVertex));
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

        System.out.println();
        LOGGER.info("Index searching best matches...");
        final List<Vertex> similarVertices = index.search(queryVector, topK);
        LOGGER.info("...done.");

        System.out.println();
        LOGGER.info("Accuracy out of {}:", allVertices.size());
        for (int i = 0; i < similarVertices.size(); i++) {
            final Vertex v = similarVertices.get(i);
            LOGGER.info("    - #{} is ID={} @ Brute-Force #{}", (i + 1), v.getMetadata("id"), (allVertices.indexOf(v) + 1));
        }
    }
}
