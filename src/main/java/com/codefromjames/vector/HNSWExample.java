package com.codefromjames.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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
        while (edges.size() <= level) {
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
                ", vector=" + Arrays.toString(vector) +
                ", edges=[" + edges.stream().map(x -> Integer.toString(x.size())).collect(Collectors.joining(",")) + ']' +
                '}';
    }
}

class VertexDistancePriorityQueue {
    private static final Comparator<VertexDistance> COMPARATOR = Comparator.comparingDouble(vd -> vd.distance);

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition newItem = lock.newCondition();
    private final Set<VertexDistance> existing = new HashSet<>();
    private final PriorityQueue<VertexDistance> queue = new PriorityQueue<>(COMPARATOR);

    public int size() {
        try {
            lock.lock();
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        try {
            lock.lock();
            return queue.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    public boolean add(VertexDistance vertexDistance) {
        try {
            lock.lock();
            if (existing.add(vertexDistance)) {
                queue.add(vertexDistance);
                newItem.signal();
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    public void addAll(Iterable<VertexDistance> vertexDistances) {
        try {
            lock.lock();
            for (VertexDistance vd : vertexDistances) {
                if (existing.add(vd)) {
                    queue.add(vd);
                    newItem.signal();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public VertexDistance pollFirst() {
        try {
            lock.lock();
            final VertexDistance polled = queue.poll();
            if (polled != null) {
                existing.remove(polled);
            }
            return polled;
        } finally {
            lock.unlock();
        }
    }

    public VertexDistance awaitFirst() {
        try {
            lock.lock();
            while (queue.isEmpty()) {
                newItem.await();
            }
            final VertexDistance polled = queue.poll();
            if (polled != null) {
                existing.remove(polled);
            }
            return polled;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }
}

interface VertexDistanceHeap extends Iterable<VertexDistance> {
    static VertexDistanceHeap create() {
        return new VertexDistanceTreeSet();
    }

    VertexDistance pollFirst();

    boolean addIfCloserAndTrim(VertexDistance current, int efSearch);

    boolean add(VertexDistance vd);

    void addAll(Iterable<VertexDistance> vertexDistances);

    boolean isEmpty();

    Stream<VertexDistance> stream();

    int size();
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
    public Stream<VertexDistance> stream() {
        return delegate.stream();
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
    public Iterator<VertexDistance> iterator() {
        return delegate.iterator();
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

    static class GraphTraversalTask extends RecursiveTask<VertexDistanceHeap> {
        private final Vertex newVertex;
        private final int level;
        private final VertexDistancePriorityQueue candidates;
        private final Set<Vertex> visited;

        public GraphTraversalTask(Vertex newVertex, int level, VertexDistancePriorityQueue candidates, Set<Vertex> visited) {
            this.newVertex = newVertex;
            this.level = level;
            this.candidates = candidates;
            this.visited = visited;
        }

        @Override
        protected VertexDistanceHeap compute() {
            final VertexDistanceHeap best = VertexDistanceHeap.create();
            while (!candidates.isEmpty()) {
                final VertexDistance current = candidates.pollFirst();
                if (current == null
                        || visited.contains(current.vertex)) {
                    continue;
                }

                visited.add(current.vertex);
                best.addIfCloserAndTrim(current, EF_CONSTRUCTION); // TODO: Construction specific value if this method is reused later!


                final VertexDistanceHeap edges = current.vertex.getEdges(level);
                final List<GraphTraversalTask> tasks = new ArrayList<>(edges.size());
                for (VertexDistance edge : edges) {
                    if (visited.contains(edge.vertex)) {
                        continue;
                    }

                    final VertexDistance currentEdge = new VertexDistance(edge.vertex, newVertex);
                    visited.add(currentEdge.vertex);
                    if (best.addIfCloserAndTrim(currentEdge, EF_CONSTRUCTION)) { // TODO: Construction specific value if this method is reused later!
                        if (candidates.add(currentEdge)) {
                            GraphTraversalTask task = new GraphTraversalTask(newVertex, level, candidates, visited);
                            task.fork(); // Begin now to process the candidate that was just added
                            tasks.add(task);
                        }
                    }
                }
                // Join in the best from each branch to aggregate into this one
                // TODO: This is probably waaaaay too greedy and branches out to everything...
                for (GraphTraversalTask task : tasks) {
                    VertexDistanceHeap bestNeighbors = task.join();
                    for (VertexDistance bestNeighbor : bestNeighbors) {
                        best.addIfCloserAndTrim(bestNeighbor, EF_CONSTRUCTION); // TODO: Construction specific value if this method is reused later!
                    }
                }
            }
            return best;
        }
    }

    public void addVertex(final Vertex newVertex) {
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

            final VertexDistanceHeap best = getVertexDistancesAtLayer(newVertex, propagateBest, currentLevel);
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("{} : Retained {} best at layer {}", newVertex.getMetadata("id"), best.size(), currentLevel);
            }
            // For nodes that are underneath us in the layer, associate newer or better peers
            for (VertexDistance vdNeighbor : best) {
                // If the new vertex would exist in this level, create neighbors for it
                if (newVertex.getMaxLevel() >= currentLevel) {
                    newVertex.addEdge(currentLevel, vdNeighbor);
                }
                // Check the edges for this neighbor at the target level to see if this is an improvement.
                // Don't process nodes we've already touched again if they remain the best from a previous layer.
                if (remapped.add(vdNeighbor.vertex)) {
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

    private VertexDistanceHeap getVertexDistancesAtLayer(Vertex newVertex, Iterable<VertexDistance> startingNodes, int level) {
        if (layers.size() - 1 < level || layers.get(level).isEmpty()) {
            return VertexDistanceHeap.create();
        }

        final Set<Vertex> visited = Collections.synchronizedSet(new HashSet<>());
        final VertexDistancePriorityQueue candidates = new VertexDistancePriorityQueue();
        if (startingNodes != null) {
            // Find the best neighbors in this level, searching from the previous level's matches
            candidates.addAll(startingNodes);
        }
        if (candidates.isEmpty()) {
            // Find the best neighbors in this level, searching from the entry point
            candidates.add(new VertexDistance(layers.get(level).get(0), newVertex));
        }

        // Entry point
        return ForkJoinPool.commonPool().invoke(new GraphTraversalTask(newVertex, level, candidates, visited));
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
            candidates.addAll(best);
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

                for (VertexDistance edge : current.vertex.getEdges(currentLevel)) {
                    if (visited.contains(edge.vertex)) {
                        continue;
                    }

                    final VertexDistance currentEdge = new VertexDistance(edge.vertex, queryVertex);
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("[{}] {} Visited edge @ {}", currentLevel, currentEdge.vertex.getMetadata("id"), currentEdge.distance);
                    }
                    if (best.addIfCloserAndTrim(currentEdge, EF_SEARCH)) {
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
                .limit(k) // Clamp the EF_SEARCH to get the best set
                .toList();
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

        final AtomicInteger selfIncr = new AtomicInteger(0);
        final AtomicReference<Instant> lastTime = new AtomicReference<>(Instant.now());
        final Map<Vertex, VertexDistanceHeap> distances = new ConcurrentHashMap<>(data.size());
        data.parallelStream()
                .forEach(self -> {
                    final VertexDistanceHeap selfDistances = distances.computeIfAbsent(self, k -> VertexDistanceHeap.create());
                    for (Vertex other : data) {
                        if (self == other) {
                            continue; // don't evaluate self
                        }
                        // TODO: IS THIS CORRECT?
                        //       Should it actually be EF_CONSTRUCTION / EF_SEARCH?
                        //       Or is this correct since it's the max number of edges each node should have and
                        //       that's what we're brute-forcing?
                        selfDistances.addIfCloserAndTrim(new VertexDistance(self, other), Vertex.ML);
                    }
                    final int itr = selfIncr.incrementAndGet();
                    if (itr > 0 && itr % 100 == 0) {
                        final Instant now = Instant.now();
                        final Instant then = lastTime.getAndSet(now);
                        LOGGER.info("Mapping {} / {}... ({}%, {}ms cycle)", itr, data.size(), String.format("%.2f", (itr / (double) data.size() * 100.0)), now.toEpochMilli() - then.toEpochMilli());
                    }
                });

        System.out.println();
        LOGGER.info("Layers generating...");
        for (int i = 0; i < data.size(); i++) {
            final Vertex newVertex = data.get(i);
            index.addVertex(newVertex);
            if (i > 0 && i % 1000 == 0) {
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
//        for (Vertex v : allVerticesDisplaySet) {
//            LOGGER.info("All vertex: Metadata: {} @ {}, Distance: {}", v.getMetadata("id"), v.getMaxLevel(), CosineDistanceUtils.cosineSimilarity(queryVertex, v));
//            for (int level = 0; level <= v.getMaxLevel(); level++) {
//                LOGGER.info("    - Edges @ {}: {}", level, v.getEdges(level).stream().map(vd -> vd.vertex.getMetadata("id").toString()).sorted().collect(Collectors.joining(",")));
//            }
//        }

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

//        System.out.println();
//        for (Vertex v : similarVertices) {
//            LOGGER.info("Similar vertex: Metadata: {}, Distance: {}", v.getMetadata("id"), CosineDistanceUtils.cosineSimilarity(queryVertex, v));
//            for (int level = 0; level <= v.getMaxLevel(); level++) {
//                LOGGER.info("    - Edges @ {}: {}", level, v.getEdges(level).stream().map(vd -> vd.vertex.getMetadata("id").toString()).sorted().collect(Collectors.joining(",")));
//            }
//        }
    }
}
