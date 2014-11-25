package com.thinkaurelius.titan.graphdb;

import com.thinkaurelius.titan.core.*;
import com.thinkaurelius.titan.core.schema.EdgeLabelMaker;
import com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration;
import com.thinkaurelius.titan.testcategory.PerformanceTests;
import com.thinkaurelius.titan.testutil.JUnitBenchmarkProvider;
import com.thinkaurelius.titan.testutil.RandomGenerator;
import com.tinkerpop.gremlin.structure.Edge;
import com.tinkerpop.gremlin.structure.Vertex;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.thinkaurelius.titan.testutil.TitanAssert.assertCount;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

/**
 * High concurrency test cases to spot deadlocks and other failures that can occur under high degrees of parallelism.
 */
@Category({PerformanceTests.class})
public abstract class TitanGraphConcurrentTest extends TitanGraphBaseTest {

    @Rule
    public TestRule benchmark = JUnitBenchmarkProvider.get();

    // Parallelism settings
    private static final int THREAD_COUNT = getThreadCount();
    private static final int TASK_COUNT = THREAD_COUNT * 256;

    // Graph structure settings
    private static final int VERTEX_COUNT = 1000;
    private static final int EDGE_COUNT = 5;
    private static final int REL_COUNT = 5;

    private static final Logger log =
            LoggerFactory.getLogger(TitanGraphConcurrentTest.class);

    private ExecutorService executor;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        executor = Executors.newFixedThreadPool(THREAD_COUNT);
    }

    private void initializeGraph() {
        //Create schema
        for (int i = 0; i < REL_COUNT; i++) {
            makeLabel("rel" + i);
        }
        makeVertexIndexedUniqueKey("uid",Integer.class);
        finishSchema();

        // Generate synthetic graph
        Vertex vertices[] = new Vertex[VERTEX_COUNT];
        for (int i = 0; i < VERTEX_COUNT; i++) {
            vertices[i] = tx.addVertex("uid", i);
        }
        for (int i = 0; i < VERTEX_COUNT; i++) {
            for (int r = 0; r < REL_COUNT; r++) {
                for (int j = 1; j <= EDGE_COUNT; j++) {
                    vertices[i].addEdge("rel"+r, vertices[wrapAround(i + j, VERTEX_COUNT)]);
                }
            }
        }

        // Get a new transaction
        clopen();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        executor.shutdown();
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            log.error("Abnormal executor shutdown");
            Thread.dumpStack();
        } else {
            log.debug("Test executor completed normal shutdown");
        }
        super.tearDown();
    }

    @Test
    public void concurrentTxRead() throws Exception {
        final int numTypes = 20;
        final int numThreads = 100;
        for (int i = 0; i < numTypes / 2; i++) {
            if (i%4 == 0) makeVertexIndexedUniqueKey("test"+i, String.class);
            else makeKey("test"+i,String.class);
        }
        for (int i = numTypes / 2; i < numTypes; i++) {
            EdgeLabelMaker tm = mgmt.makeEdgeLabel("test" + i);
            if (i % 4 == 1) tm.unidirected();
            tm.make();
        }
        finishSchema();
        clopen();

        Thread[] threads = new Thread[numThreads];
        for (int t = 0; t < numThreads; t++) {
            threads[t] = new Thread(new Runnable() {
                @Override
                public void run() {
                    TitanTransaction tx = graph.newTransaction();
                    for (int i = 0; i < numTypes; i++) {
                        RelationType type = tx.getRelationType("test" + i);
                        if (i < numTypes / 2) assertTrue(type.isPropertyKey());
                        else assertTrue(type.isEdgeLabel());
                    }
                    tx.commit();
                }
            });
            threads[t].start();
        }
        for (int t = 0; t < numThreads; t++) {
            threads[t].join();
        }
    }


    /**
     * Insert an extremely simple graph and start
     * TASK_COUNT simultaneous readers in an executor with
     * THREAD_COUNT threads.
     *
     * @throws Exception
     */
    @Test
    public void concurrentReadsOnSingleTransaction() throws Exception {
        initializeGraph();

        PropertyKey id = tx.getPropertyKey("uid");

        // Tail many concurrent readers on a single transaction
        CountDownLatch startLatch = new CountDownLatch(TASK_COUNT);
        CountDownLatch stopLatch = new CountDownLatch(TASK_COUNT);
        for (int i = 0; i < TASK_COUNT; i++) {
            int vertexid = RandomGenerator.randomInt(0, VERTEX_COUNT);
            EdgeLabel elabel = tx.getEdgeLabel("rel" + RandomGenerator.randomInt(0, REL_COUNT));
            executor.execute(new SimpleReader(tx, startLatch, stopLatch, vertexid, elabel.name(), EDGE_COUNT * 2, id.name()));
            startLatch.countDown();
        }
        stopLatch.await();
    }

    /**
     * Tail many readers, as in {@link #concurrentReadsOnSingleTransaction()},
     * but also start some threads that add and remove relationships and
     * properties while the readers are working; all tasks share a common
     * transaction.
     * <p/>
     * The readers do not look for the properties or relationships the
     * writers are mutating, since this is all happening on a common transaction.
     *
     * @throws Exception
     */
    @Test
    public void concurrentReadWriteOnSingleTransaction() throws Exception {
        initializeGraph();

        mgmt.getPropertyKey("uid");
        makeVertexIndexedUniqueKey("dummyProperty",String.class);
        makeLabel("dummyRelationship");
        finishSchema();

        PropertyKey id = tx.getPropertyKey("uid");
        Runnable propMaker = new RandomPropertyMaker(tx, VERTEX_COUNT, id.name(), "dummyProperty");
        Runnable relMaker = new FixedRelationshipMaker(tx, id.name(), "dummyRelationship");

        Future<?> propFuture = executor.submit(propMaker);
        Future<?> relFuture = executor.submit(relMaker);

        CountDownLatch startLatch = new CountDownLatch(TASK_COUNT);
        CountDownLatch stopLatch = new CountDownLatch(TASK_COUNT);
        for (int i = 0; i < TASK_COUNT; i++) {
            int vertexid = RandomGenerator.randomInt(0, VERTEX_COUNT);
            EdgeLabel elabel = tx.getEdgeLabel("rel" + RandomGenerator.randomInt(0, REL_COUNT));
            executor.execute(new SimpleReader(tx, startLatch, stopLatch, vertexid, elabel.name(), EDGE_COUNT * 2, id.name()));
            startLatch.countDown();
        }
        stopLatch.await();

        propFuture.cancel(true);
        relFuture.cancel(true);
    }

    @Test
    public void concurrentIndexReadWriteTest() throws Exception {
        clopen(option(GraphDatabaseConfiguration.ADJUST_LIMIT),false);

        PropertyKey k = mgmt.makePropertyKey("k").dataType(Integer.class).cardinality(Cardinality.SINGLE).make();
        PropertyKey q = mgmt.makePropertyKey("q").dataType(Long.class).cardinality(Cardinality.SINGLE).make();
        mgmt.buildIndex("byK",Vertex.class).addKey(k).buildCompositeIndex();
        finishSchema();

        final AtomicBoolean run = new AtomicBoolean(true);
        final int batchV = 10;
        final int batchR = 10;
        final int maxK = 5;
        final int maxQ = 2;
        final Random random = new Random();
        final AtomicInteger duplicates = new AtomicInteger(0);

        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                while (run.get()) {
                    TitanTransaction tx = graph.newTransaction();
                    try {
                        for (int i = 0; i < batchV; i++) {
                            Vertex v = tx.addVertex();
                            v.property("k", random.nextInt(maxK));
                            v.property("q", random.nextInt(maxQ));
                        }
                        tx.commit();
                    } catch (Throwable e) {
                        e.printStackTrace();
                    } finally {
                        if (tx.isOpen()) tx.rollback();
                    }
                }
            }
        });
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                while (run.get()) {
                    TitanTransaction tx = graph.newTransaction();
                    try {
                        for (int i = 0; i < batchR; i++) {
                            Set<Vertex> vs = new HashSet<Vertex>();
                            Iterable<TitanVertex> vertices = tx.query().has("k",random.nextInt(maxK)).has("q",random.nextInt(maxQ)).vertices();
                            for (TitanVertex v : vertices) {
                                if (!vs.add(v)) {
                                    duplicates.incrementAndGet();
                                    System.err.println("Duplicate vertex: " + v);
                                }
                            }
                        }
                        tx.commit();
                    } catch (Throwable e) {
                        e.printStackTrace();
                    } finally {
                        if (tx.isOpen()) tx.rollback();
                    }
                }
            }
        });
        writer.start();
        reader.start();

        Thread.sleep(10000);
        run.set(false);
        writer.join();
        reader.join();

        assertEquals(0,duplicates.get());
    }

    /**
     * Load-then-read test of standard-indexed vertex properties. This test
     * contains no edges.
     * <p/>
     * The load stage is serial. The read stage is concurrent.
     * <p/>
     * Create a set of vertex property types with standard indices
     * (threadPoolSize * 5 by default) serially. Serially write 1k vertices with
     * values for all of the indexed property types. Concurrently query the
     * properties. Each thread uses a single, distinct transaction for all index
     * retrievals in that thread.
     *
     * @throws ExecutionException
     * @throws InterruptedException
     */
    @Test
    public void testStandardIndexVertexPropertyReads() throws InterruptedException, ExecutionException {
        final int propCount = THREAD_COUNT * 5;
        final int vertexCount = 1 * 1000;
        // Create props with standard indexes
        log.info("Creating types");
        for (int i = 0; i < propCount; i++) {
            makeVertexIndexedUniqueKey("p"+i,String.class);
        }
        finishSchema();

        log.info("Creating vertices");
        // Write vertices with indexed properties
        for (int i = 0; i < vertexCount; i++) {
            Vertex v = tx.addVertex();
            for (int p = 0; p < propCount; p++) {
                v.property("p" + p, i);
            }
        }
        newTx();
        log.info("Querying vertex property indices");
        // Execute runnables
        Collection<Future<?>> futures = new ArrayList<Future<?>>(TASK_COUNT);
        for (int i = 0; i < TASK_COUNT; i++) {
            futures.add(executor.submit(new VertexPropertyQuerier(propCount, vertexCount)));
        }
        for (Future<?> f : futures) {
            f.get();
        }
    }

    private static class RandomPropertyMaker implements Runnable {
        private final TitanTransaction tx;
        private final int nodeCount; //inclusive
        private final String idKey;
        private final String randomKey;

        public RandomPropertyMaker(TitanTransaction tx, int nodeCount,
                                   String idKey, String randomKey) {
            this.tx = tx;
            this.nodeCount = nodeCount;
            this.idKey = idKey;
            this.randomKey = randomKey;
        }

        @Override
        public void run() {
            while (true) {
                // Set propType to a random value on a random node
                Vertex n = getOnlyElement(tx.V().has(idKey, RandomGenerator.randomInt(0, nodeCount)));
                String propVal = RandomGenerator.randomString();
                n.property(randomKey, propVal);
                if (Thread.interrupted())
                    break;

                // Is creating the same property twice an error?
            }
        }
    }

    /**
     * For two nodes whose ID-property, provided at construction,
     * has the value either 0 or 1, break all existing relationships
     * from 0-node to 1-node and create a relationship of a type
     * provided at construction in the same direction.
     */
    private static class FixedRelationshipMaker implements Runnable {

        private final TitanTransaction tx;
        //		private final int nodeCount; //inclusive
        private final String idKey;
        private final String elabel;

        public FixedRelationshipMaker(TitanTransaction tx,
                                      String id, String elabel) {
            this.tx = tx;
            this.idKey = id;
            this.elabel = elabel;
        }

        @Override
        public void run() {
            while (true) {
                // Make or break relType between two (possibly same) random nodes
                Vertex source = getOnlyElement(tx.V().has(idKey, 0));
                Vertex sink = getOnlyElement(tx.V().has(idKey, 1));
                for (Edge r : source.outE(elabel).toList()) {
                    if (getId(r.inV().next()) == getId(sink)) {
                        r.remove();
                        continue;
                    }
                }
                source.addEdge(elabel, sink);
                if (Thread.interrupted())
                    break;
            }
        }

    }

    private static class SimpleReader extends BarrierRunnable {

        private final int vertexid;
        private final String label2Traverse;
        private final long nodeTraversalCount = 256;
        private final int expectedEdges;
        private final String idKey;

        public SimpleReader(TitanTransaction tx, CountDownLatch startLatch,
                            CountDownLatch stopLatch, int startNodeId, String label2Traverse, int expectedEdges, String idKey) {
            super(tx, startLatch, stopLatch);
            this.vertexid = startNodeId;
            this.label2Traverse = label2Traverse;
            this.expectedEdges = expectedEdges;
            this.idKey = idKey;
        }

        @Override
        protected void doRun() throws Exception {
            Vertex v = getOnlyElement(tx.V().has(idKey, vertexid));

            for (int i = 0; i < nodeTraversalCount; i++) {
                assertCount(expectedEdges, v.bothE(label2Traverse));
                for (Edge r : v.outE(label2Traverse).toList()) {
                    v = r.inV().next();
                }
            }
        }
    }

    private abstract static class BarrierRunnable implements Runnable {

        protected final TitanTransaction tx;
        protected final CountDownLatch startLatch;
        protected final CountDownLatch stopLatch;

        public BarrierRunnable(TitanTransaction tx, CountDownLatch startLatch, CountDownLatch stopLatch) {
            this.tx = tx;
            this.startLatch = startLatch;
            this.stopLatch = stopLatch;
        }

        protected abstract void doRun() throws Exception;

        @Override
        public void run() {
            try {
                startLatch.await();
            } catch (Exception e) {
                throw new RuntimeException("Interrupted while waiting for peers to start");
            }

            try {
                doRun();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            stopLatch.countDown();
        }
    }

    /**
     * See {@line #testStandardIndex()}
     */
    private class VertexPropertyQuerier implements Runnable {

        private final int propCount;
        private final int vertexCount;

        public VertexPropertyQuerier(int propCount, int vertexCount) {
            this.propCount = propCount;
            this.vertexCount = vertexCount;
        }

        @Override
        public void run() {
            for (int i = 0; i < vertexCount; i++) {
                for (int p = 0; p < propCount; p++) {
                    tx.V().has("p" + p, i).count().next();
                }
            }
        }
    }
}
