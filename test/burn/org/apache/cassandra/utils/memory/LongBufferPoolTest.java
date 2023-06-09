/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.utils.memory;

import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.util.concurrent.Uninterruptibles;
import org.junit.BeforeClass;
import org.junit.Test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.utils.DynamicList;

import static org.junit.Assert.*;

/**
 * Long BufferPool test - make sure that the BufferPool allocates and recycles
 * ByteBuffers under heavy concurrent usage.
 *
 * The test creates two groups of threads
 *
 * - the burn producer/consumer pair that allocates 1/10 poolSize and then returns
 *   all the memory to the pool. 50% is freed by the producer, 50% passed to the consumer thread.
 *
 * - a ring of worker threads that allocate buffers and either immediately free them,
 *   or pass to the next worker thread for it to be freed on it's behalf.  Periodically
 *   all memory is freed by the thread.
 *
 * While the burn/worker threads run, the original main thread checks that all of the threads are still
 * making progress every 10s (no locking issues, or exits from assertion failures),
 * and that every chunk has been freed at least once during the previous cycle (if that was possible).
 *
 * The test does not expect to survive out-of-memory errors, so needs sufficient heap memory
 * for non-direct buffers and the debug tracking objects that check the allocate buffers.
 * (The timing is very interesting when Xmx is lowered to increase garbage collection pauses, but do
 * not set it too low).
 */
public class LongBufferPoolTest
{
    @BeforeClass
    public static void setup() throws Exception
    {
        DatabaseDescriptor.daemonInitialization();
    }

    private static final Logger logger = LoggerFactory.getLogger(LongBufferPoolTest.class);

    private static final int AVG_BUFFER_SIZE = 16 << 10;
    private static final int STDEV_BUFFER_SIZE = 10 << 10; // picked to ensure exceeding buffer size is rare, but occurs
    private static final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

    @Test
    public void testAllocate() throws InterruptedException, ExecutionException, BrokenBarrierException, TimeoutException
    {
        testAllocate(Runtime.getRuntime().availableProcessors() * 2, TimeUnit.MINUTES.toNanos(2L), 16 << 20);
    }

    private static final class BufferCheck
    {
        final ByteBuffer buffer;
        final long val;
        DynamicList.Node<BufferCheck> listnode;

        private BufferCheck(ByteBuffer buffer, long val)
        {
            this.buffer = buffer;
            this.val = val;
        }

        void validate()
        {
            ByteBuffer read = buffer.duplicate();
            while (read.remaining() > 8)
                assert read.getLong() == val;
        }

        void init()
        {
            ByteBuffer write = buffer.duplicate();
            while (write.remaining() > 8)
                write.putLong(val);
        }
    }

    private static final class TestEnvironment
    {
        final int threadCount;
        final long duration;
        final int poolSize;
        final long until;
        final CountDownLatch latch;
        final SPSCQueue<BufferCheck>[] sharedRecycle;

        volatile boolean shouldFreeMemoryAndSuspend = false;

        final CyclicBarrier stopAllocationsBarrier;
        final CyclicBarrier freedAllMemoryBarrier;
        final CyclicBarrier resumeAllocationsBarrier;

        final ExecutorService executorService;
        final List<Future<Boolean>> threadResultFuture;
        final int targetSizeQuanta;

        TestEnvironment(int threadCount, long duration, int poolSize)
        {
            this.threadCount = threadCount;
            this.duration = duration;
            this.poolSize = poolSize;
            until = System.nanoTime() + duration;
            latch = new CountDownLatch(threadCount);
            sharedRecycle = new SPSCQueue[threadCount];

            // N worker threads + burner thread + main thread:
            stopAllocationsBarrier = new CyclicBarrier(threadCount + 2);
            freedAllMemoryBarrier = new CyclicBarrier(threadCount + 2);
            resumeAllocationsBarrier = new CyclicBarrier(threadCount + 2);

            executorService = Executors.newFixedThreadPool(threadCount + 2);
            threadResultFuture = new ArrayList<>(threadCount);

            for (int i = 0; i < sharedRecycle.length; i++)
            {
                sharedRecycle[i] = new SPSCQueue<>();
            }

            // Divide the poolSize across our threads, deliberately over-subscribing it.  Threads
            // allocate a different amount of memory each - 1*quanta, 2*quanta, ... N*quanta.
            // Thread0 is always going to be a single CHUNK, then to allocate increasing amounts
            // using their own algorithm the targetSize should be poolSize / targetSizeQuanta.
            //
            // This should divide double the poolSize across the working threads,
            // plus CHUNK_SIZE for thread0 and 1/10 poolSize for the burn producer/consumer pair.
            targetSizeQuanta = 2 * poolSize / sum1toN(threadCount - 1);
        }

        void addCheckedFuture(Future<Boolean> future)
        {
            threadResultFuture.add(future);
        }


        int countDoneThreads()
        {
            int doneThreads = 0;
            for (Future<Boolean> r : threadResultFuture)
            {
                if (r.isDone())
                    doneThreads++;
            }
            return doneThreads;
        }

        void assertCheckedThreadsSucceeded()
        {
            try
            {
                for (Future<Boolean> r : threadResultFuture)
                    assertTrue(r.get());
            }
            catch (InterruptedException ex)
            {
                // If interrupted while checking, restart and check everything.
                assertCheckedThreadsSucceeded();
            }
            catch (ExecutionException ex)
            {
                fail("Checked thread threw exception: " + ex.toString());
            }
        }

        /**
         * Implementers must assure all buffers were returned to the buffer pool on run exit.
         */
        interface MemoryFreeTask
        {
            void run();
        }

        /**
         * If the main test loop requested stopping the threads by setting
         * {@link TestEnvironment#shouldFreeMemoryAndSuspend},
         * waits until all threads reach this call and then frees the memory by running the given memory free task.
         * After the task finishes, it waits on the {@link TestEnvironment#freedAllMemoryBarrier} and
         * {@link TestEnvironment#resumeAllocationsBarrier} to let the main test loop perform the post-free checks.
         * The call exits after {@link TestEnvironment#resumeAllocationsBarrier} is reached by all threads.
         *
         * @param task the task that should return all buffers held by this thread to the buffer pool
         */
        void maybeSuspendAndFreeMemory(MemoryFreeTask task) throws InterruptedException, BrokenBarrierException
        {
            if (shouldFreeMemoryAndSuspend)
            {
                try
                {
                    // Wait until allocations stop in all threads; this guanrantees this thread won't
                    // receive any new buffers from other threads while freeing memory.
                    stopAllocationsBarrier.await();
                    // Free our memory
                    task.run();
                    // Now wait for the other threads to free their memory
                    freedAllMemoryBarrier.await();
                    // Now all memory is freed, but let's not resume allocations until the main test thread
                    // performs the required checks.
                    // At this point, used memory indicated by the pool
                    // should be == 0 and all buffers should be recycled.
                    resumeAllocationsBarrier.await();
                }
                catch (BrokenBarrierException | InterruptedException e)
                {
                    // At the end of the test some threads may have already exited,
                    // so they can't arrive at one of the barriers, and we may end up here.
                    // This is fine if this happens after the test deadline, and we
                    // just allow the test worker to exit cleanly.
                    // It must not happen before the test deadline though, it would likely be a bug,
                    // so we rethrow in that case.
                    if (System.nanoTime() < until)
                        throw e;
                }
            }
        }
    }

    public void testAllocate(int threadCount, long duration, int poolSize) throws InterruptedException, ExecutionException, BrokenBarrierException, TimeoutException
    {
        System.out.println(String.format("%s - testing %d threads for %dm",
                                         DATE_FORMAT.format(new Date()),
                                         threadCount,
                                         TimeUnit.NANOSECONDS.toMinutes(duration)));
        long prevPoolSize = BufferPool.MEMORY_USAGE_THRESHOLD;
        logger.info("Overriding configured BufferPool.MEMORY_USAGE_THRESHOLD={} and enabling BufferPool.DEBUG", poolSize);
        BufferPool.MEMORY_USAGE_THRESHOLD = poolSize;
        BufferPool.DEBUG = true;

        TestEnvironment testEnv = new TestEnvironment(threadCount, duration, poolSize);

        startBurnerThreads(testEnv);

        for (int threadIdx = 0; threadIdx < threadCount; threadIdx++)
            testEnv.addCheckedFuture(startWorkerThread(testEnv, threadIdx));

        while (!testEnv.latch.await(1L, TimeUnit.SECONDS))
        {
            try
            {
                // request all threads to release all buffers to the bufferPool
                testEnv.shouldFreeMemoryAndSuspend = true;
                testEnv.stopAllocationsBarrier.await(10, TimeUnit.SECONDS);
                // wait until all memory released
                testEnv.freedAllMemoryBarrier.await(10, TimeUnit.SECONDS);
                // now all buffers should be back in the pool, and no more allocations happening
                BufferPool.assertAllRecycled();
                // resume threads only after debug.cycleRound has been increased
                testEnv.shouldFreeMemoryAndSuspend = false;
                testEnv.resumeAllocationsBarrier.await(10, TimeUnit.SECONDS);
            }
            catch (TimeoutException e)
            {
                // a thread that is done will not reach the barriers, so timeout is unexpected only if
                // all threads are still running
                if (testEnv.countDoneThreads() == 0)
                {
                    logger.error("Some threads have stalled and didn't reach the barrier", e);
                    return;
                }
            }
        }

        for (SPSCQueue<BufferCheck> queue : testEnv.sharedRecycle)
        {
            BufferCheck check;
            while ( null != (check = queue.poll()) )
            {
                check.validate();
                BufferPool.put(check.buffer);
            }
        }

        assertEquals(0, testEnv.executorService.shutdownNow().size());

        logger.info("Reverting BufferPool.MEMORY_USAGE_THRESHOLD={}", prevPoolSize);
        BufferPool.MEMORY_USAGE_THRESHOLD = prevPoolSize;
        BufferPool.DEBUG = false;

        testEnv.assertCheckedThreadsSucceeded();

        System.out.println(String.format("%s - finished.",
                                         DATE_FORMAT.format(new Date())));
    }

    private Future<Boolean> startWorkerThread(TestEnvironment testEnv, final int threadIdx)
    {
        return testEnv.executorService.submit(new TestUntil(testEnv.until)
        {
            final int targetSize = threadIdx == 0 ? BufferPool.CHUNK_SIZE : testEnv.targetSizeQuanta * threadIdx;
            final SPSCQueue<BufferCheck> shareFrom = testEnv.sharedRecycle[threadIdx];
            final DynamicList<BufferCheck> checks = new DynamicList<>((int) Math.max(1, targetSize / (1 << 10)));
            final SPSCQueue<BufferCheck> shareTo = testEnv.sharedRecycle[(threadIdx + 1) % testEnv.threadCount];
            final Future<Boolean> neighbourResultFuture = testEnv.threadResultFuture.get((threadIdx + 1) % testEnv.threadCount);
            final ThreadLocalRandom rand = ThreadLocalRandom.current();
            int totalSize = 0;
            int freeingSize = 0;
            int size = 0;

            void testOne() throws Exception
            {
                testEnv.maybeSuspendAndFreeMemory(this::freeAll);

                long currentTargetSize = rand.nextInt(testEnv.poolSize / 1024) == 0 ? 0 : targetSize;
                int spinCount = 0;
                while (totalSize > currentTargetSize - freeingSize)
                {
                    // Don't get stuck in this loop if other threads might be suspended:
                    if (testEnv.shouldFreeMemoryAndSuspend)
                        return;

                    // Don't get stuck in this loop if the neighbour thread exited:
                    if (neighbourResultFuture.isDone())
                        return;

                    // free buffers until we're below our target size
                    if (checks.size() == 0)
                    {
                        // if we're out of buffers to free, we're waiting on our neighbour to free them;
                        // first check if the consuming neighbour has caught up, and if so mark that free
                        if (shareTo.exhausted)
                        {
                            totalSize -= freeingSize;
                            freeingSize = 0;
                        }
                        else if (!recycleFromNeighbour())
                        {
                            if (++spinCount > 1000 && System.nanoTime() > until)
                                return;
                            // otherwise, free one of our other neighbour's buffers if can; and otherwise yield
                            Thread.yield();
                        }
                        continue;
                    }

                    // pick a random buffer, with preference going to earlier ones
                    BufferCheck check = sample();
                    checks.remove(check.listnode);
                    check.validate();

                    size = BufferPool.roundUpNormal(check.buffer.capacity());
                    if (size > BufferPool.CHUNK_SIZE)
                        size = 0;

                    // either share to free, or free immediately
                    if (rand.nextBoolean())
                    {
                        shareTo.add(check);
                        freeingSize += size;
                        // interleave this with potentially messing with the other neighbour's stuff
                        recycleFromNeighbour();
                    }
                    else
                    {
                        check.validate();
                        BufferPool.put(check.buffer);
                        totalSize -= size;
                    }
                }

                // allocate a new buffer
                size = (int) Math.max(1, AVG_BUFFER_SIZE + (STDEV_BUFFER_SIZE * rand.nextGaussian()));
                if (size <= BufferPool.CHUNK_SIZE)
                {
                    totalSize += BufferPool.roundUpNormal(size);
                    allocate(size);
                }
                else if (rand.nextBoolean())
                {
                    allocate(size);
                }
                else
                {
                    // perform a burst allocation to exhaust all available memory
                    while (totalSize < testEnv.poolSize)
                    {
                        size = (int) Math.max(1, AVG_BUFFER_SIZE + (STDEV_BUFFER_SIZE * rand.nextGaussian()));
                        if (size <= BufferPool.CHUNK_SIZE)
                        {
                            allocate(size);
                            totalSize += BufferPool.roundUpNormal(size);
                        }
                    }
                }

                // validate a random buffer we have stashed
                checks.get(rand.nextInt(checks.size())).validate();

                // free all of our neighbour's remaining shared buffers
                while (recycleFromNeighbour());
            }

            /**
             * Returns all allocated buffers back to the buffer pool.
             */
            void freeAll()
            {
                while (checks.size() > 0)
                {
                    BufferCheck check = sample();
                    checks.remove(check.listnode);
                    check.validate();
                    BufferPool.put(check.buffer);
                }

                BufferCheck check;
                while ((check = shareFrom.poll()) != null)
                {
                    check.validate();
                    BufferPool.put(check.buffer);
                }

                BufferPool.releaseLocal();
            }

            void cleanup()
            {
                while (checks.size() > 0)
                {
                    BufferCheck check = checks.get(0);
                    BufferPool.put(check.buffer);
                    checks.remove(check.listnode);
                }
                testEnv.latch.countDown();
            }

            boolean recycleFromNeighbour()
            {
                BufferCheck check = shareFrom.poll();
                if (check == null)
                    return false;
                check.validate();
                BufferPool.put(check.buffer);
                return true;
            }

            BufferCheck allocate(int size)
            {
                ByteBuffer buffer = BufferPool.get(size);
                assertNotNull(buffer);
                BufferCheck check = new BufferCheck(buffer, rand.nextLong());
                assertEquals(size, buffer.capacity());
                assertEquals(0, buffer.position());
                check.init();
                check.listnode = checks.append(check);
                return check;
            }

            BufferCheck sample()
            {
                // sample with preference to first elements:
                // element at index n will be selected with likelihood (size - n) / sum1ToN(size)
                int size = checks.size();

                // pick a random number between 1 and sum1toN(size)
                int sampleRange = sum1toN(size);
                int sampleIndex = rand.nextInt(sampleRange);

                // then binary search for the N, such that [sum1ToN(N), sum1ToN(N+1)) contains this random number
                int moveBy = Math.max(size / 4, 1);
                int index = size / 2;
                while (true)
                {
                    int baseSampleIndex = sum1toN(index);
                    int endOfSampleIndex = sum1toN(index + 1);
                    if (sampleIndex >= baseSampleIndex)
                    {
                        if (sampleIndex < endOfSampleIndex)
                            break;
                        index += moveBy;
                    }
                    else index -= moveBy;
                    moveBy = Math.max(moveBy / 2, 1);
                }

                // this gives us the inverse of our desired value, so just subtract it from the last index
                index = size - (index + 1);

                return checks.get(index);
            }
        });
    }

    private void startBurnerThreads(TestEnvironment testEnv)
    {
        // setup some high churn allocate/deallocate, without any checking

        final AtomicLong pendingBuffersCount = new AtomicLong(0);
        final SPSCQueue<ByteBuffer> burn = new SPSCQueue<>();
        final CountDownLatch doneAdd = new CountDownLatch(1);
        testEnv.addCheckedFuture(testEnv.executorService.submit(new TestUntil(testEnv.until)
        {
            int count = 0;
            final ThreadLocalRandom rand = ThreadLocalRandom.current();
            void testOne() throws Exception
            {
                if (count * BufferPool.CHUNK_SIZE >= testEnv.poolSize / 10)
                {
                    if (pendingBuffersCount.get() == 0)
                    {
                        count = 0;
                        testEnv.maybeSuspendAndFreeMemory(BufferPool::releaseLocal);
                    }
                    else
                    {
                        Thread.yield();
                    }
                    return;
                }

                ByteBuffer buffer = BufferPool.tryGet(BufferPool.CHUNK_SIZE);
                if (buffer == null)
                {
                    Thread.yield();
                    return;
                }

                // 50/50 chance of returning the buffer from the producer thread, or
                // pass it on to the consumer.
                if (rand.nextBoolean())
                    BufferPool.put(buffer);
                else
                {
                    pendingBuffersCount.incrementAndGet();
                    burn.add(buffer);
                }
                count++;
            }
            void cleanup()
            {
                doneAdd.countDown();
            }
        }));
        testEnv.threadResultFuture.add(testEnv.executorService.submit(new TestUntil(testEnv.until)
        {
            void testOne() throws Exception
            {
                ByteBuffer buffer = burn.poll();
                if (buffer == null)
                {
                    Thread.yield();
                    return;
                }
                BufferPool.put(buffer);
                pendingBuffersCount.decrementAndGet();
            }
            void cleanup()
            {
                Uninterruptibles.awaitUninterruptibly(doneAdd);
            }
        }));
    }

    static abstract class TestUntil implements Callable<Boolean>
    {
        final long until;
        protected TestUntil(long until)
        {
            this.until = until;
        }

        abstract void testOne() throws Exception;
        void checkpoint() {}
        void cleanup() {}

        public Boolean call() throws Exception
        {
            try
            {
                while (System.nanoTime() < until)
                {
                    checkpoint();
                    for (int i = 0 ; i < 100 ; i++)
                        testOne();
                }
            }
            catch (Exception ex)
            {
                logger.error("Got exception {}, current chunk {}",
                             ex.getMessage(),
                             BufferPool.currentChunk());
                ex.printStackTrace();
                return false;
            }
            catch (Throwable tr) // for java.lang.OutOfMemoryError
            {
                logger.error("Got throwable {}, current chunk {}",
                             tr.getMessage(),
                             BufferPool.currentChunk());
                tr.printStackTrace();
                return false;
            }
            finally
            {
                cleanup();
            }
            return true;
        }
    }

    public static void main(String[] args)
    {
        try
        {
            new LongBufferPoolTest().testAllocate(Runtime.getRuntime().availableProcessors(),
                                                  TimeUnit.HOURS.toNanos(2L), 16 << 20);
            System.exit(0);
        }
        catch (Throwable tr)
        {
            System.out.println(String.format("Test failed - %s", tr.getMessage()));
            System.exit(1); // Force exit so that non-daemon threads like REQUEST-SCHEDULER do not hang the process on failure
        }
    }

    /**
     * A single producer, single consumer queue.
     */
    private static final class SPSCQueue<V>
    {
        static final class Node<V>
        {
            volatile Node<V> next;
            final V value;
            Node(V value)
            {
                this.value = value;
            }
        }

        private volatile boolean exhausted = true;
        Node<V> head = new Node<>(null);
        Node<V> tail = head;

        void add(V value)
        {
            exhausted = false;
            tail = tail.next = new Node<>(value);
        }

        V poll()
        {
            Node<V> next = head.next;
            if (next == null)
            {
                // this is racey, but good enough for our purposes
                exhausted = true;
                return null;
            }
            head = next;
            return next.value;
        }
    }

    private static int sum1toN(int n)
    {
        return (n * (n + 1)) / 2;
    }
}
