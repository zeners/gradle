/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks.testing;

import org.gradle.api.tasks.testing.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages stealing test-classes not startet from busy processors.<br>
 * <br>
 * Collects planned {@link TestDefinition} with assigned processors and removes it, when started.<br>
 * To steal, it will repeat remove first entry until the other processor
 * {@link StealableWorker#handOver hand it over} or no more entries left.<br>
 * <br>
 * incompatible with {@link Test#getForkEvery()} > 0 and senseless, if {@link Test#getMaxParallelForks()} == 1
 *
 * <ul>
 *     <li>{@link #add} collects all {@link TestDefinition} in incoming order with assigned processor</li>
 *     <li>{@link #remove} removes started {@link TestDefinition} from {@link #plannedClasses}</li>
 *     <li>{@link #trySteal} manages stealing</li>
 *     <li>{@link #handOverTestDefinition} hand over result received</li>
 *     <li>{@link #stopped} signalling processor stopped, related entries cleaned up</li>
 * </ul>
 */
public class JvmTestDefinitionStealer implements TestDefinitionStealer<ClassTestDefinition> {

    private static final Logger LOGGER = LoggerFactory.getLogger(JvmTestDefinitionStealer.class);
    /**
     * serialize Tasks depending on stealing actions to avoid locking
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /**
     * planned test-classes with assigned processors, could contain always startet Work
     */
    private final Map<ClassTestDefinition, StealableWorker<ClassTestDefinition>> plannedClasses = new LinkedHashMap<>();

    /**
     * remember per target-processor which test-class is waiting for handover result, adding result, will release the trySteal waiting
     */
    private final Map<StealableWorker<ClassTestDefinition>, Map<ClassTestDefinition, BlockingQueue<Boolean>>> tryStealingMap =
        Collections.synchronizedMap(new HashMap<>());
    private final Set<StealableWorker<ClassTestDefinition>> stoppedProcessors = Collections.synchronizedSet(new HashSet<>());
    private final CallNextPlanned callNextPlanned = new CallNextPlanned();

    /**
     * add entry with testDefinition and processor to {@link #plannedClasses}
     *
     * @param testDefinition new planned test-class
     * @param processor processor planned to work on the testDefinition
     */
    @Override
    public void add(final ClassTestDefinition testDefinition, final StealableWorker<ClassTestDefinition> processor) {
        executor.submit(new RunAdd(testDefinition, processor));
    }

    /**
     * removes asynchron testDefinition from {@link #plannedClasses}
     */
    @Override
    public void remove(final ClassTestDefinition testDefinition) {
        executor.submit(new RunRemove(testDefinition));
    }

    @Override
    public void stopped(final StealableWorker<ClassTestDefinition> processor) {
        if (stoppedProcessors.add(processor)) {
            LOGGER.info("Stealer: clean process {}", processor);
            executor.submit(new RunStopped<>(processor));
        }
    }

    /**
     * looking for next test-class, that have been successful {@link StealableWorker#handOver hand over}
     *
     * @return next {@link StealableWorker#handOver hand over} test-class or null, if nothing left
     */
    @Override
    @Nullable
    public ClassTestDefinition trySteal() {
        try {
            NextPlanned nextPlanned;
            LOGGER.debug("Stealer: waiting for next...");
            do { // looping over async wait ...
                nextPlanned = executor.submit(callNextPlanned).get();
            } while (nextPlanned != null && !nextPlanned.handOver(this));
            LOGGER.info("Stealer: found {}", nextPlanned);
            return nextPlanned == null ? null : nextPlanned.testDefinition;
        } catch (InterruptedException e) {
            // ignore and clean the interrupted state
            //noinspection ResultOfMethodCallIgnored
            Thread.interrupted();
        } catch (Exception e) { // ExecutionException | ReflectiveOperationException only since java 1.7
            // ignore
        }
        return null;
    }

    @Override
    public void handOverTestDefinition(StealableWorker<ClassTestDefinition> worker, ClassTestDefinition testDefinition, boolean success) {
        LOGGER.debug("Stealer: handOver {} {}", testDefinition.getId(), success);
        synchronized (tryStealingMap) {
            final Map<ClassTestDefinition, BlockingQueue<Boolean>> map = tryStealingMap.get(worker);
            if (map == null) {
                return;
            }
            final BlockingQueue<Boolean> queue = map.get(testDefinition);
            if (queue != null) {
                queue.add(success);
            }
        }
    }

    private static class NextPlanned {
        private final StealableWorker<ClassTestDefinition> processor;
        private final ClassTestDefinition testDefinition;
        private final BlockingQueue<Boolean> queue;

        public NextPlanned(Map.Entry<ClassTestDefinition, StealableWorker<ClassTestDefinition>> entry, BlockingQueue<Boolean> queue) {
            this.processor = entry.getValue();
            this.testDefinition = entry.getKey();
            this.queue = queue;
        }

        @Override
        public String toString() {
            return String.format("%s from %s", testDefinition.getId(), processor);
        }

        private boolean handOver(JvmTestDefinitionStealer stealer) {
            try {
                LOGGER.debug("Stealer: wait for handOver {} from {}", testDefinition.getId(), processor);
                processor.handOver(testDefinition);
                return queue.take(); // stop searching if found
            } catch (Exception ignore) {
                // if anything go wrong, take next
                return true;
            } finally {
                synchronized (stealer.tryStealingMap) {
                    final Map<ClassTestDefinition, BlockingQueue<Boolean>> map = stealer.tryStealingMap.get(processor);
                    if (map != null) {
                        map.remove(testDefinition);
                        if (map.isEmpty()) {
                            stealer.tryStealingMap.remove(processor);
                        }
                    }
                }
            }
        }
    }

    private class RunAdd implements Runnable, Serializable {
        private final ClassTestDefinition testDefinition;
        private final StealableWorker<ClassTestDefinition> processor;

        public RunAdd(ClassTestDefinition testDefinition, StealableWorker<ClassTestDefinition> testWorker) {
            this.testDefinition = testDefinition;
            this.processor = testWorker;
        }

        @Override
        public void run() {
            plannedClasses.put(testDefinition, processor);
        }
    }

    private class RunRemove implements Runnable, Serializable {
        private final ClassTestDefinition testDefinition;

        public RunRemove(ClassTestDefinition testDefinition) {
            this.testDefinition = testDefinition;
        }

        @Override
        public void run() {
            plannedClasses.remove(testDefinition);
        }
    }

    private class CallNextPlanned implements Callable<NextPlanned>, Serializable {
        public CallNextPlanned() {
            // required to not produce subclass failing InternalNullabilityTest
        }

        @Override
        @Nullable
        public NextPlanned call() {
            if (plannedClasses.isEmpty()) {
                return null;
            }
            final Iterator<Map.Entry<ClassTestDefinition, StealableWorker<ClassTestDefinition>>> iterator = plannedClasses.entrySet().iterator();
            try {
                final ArrayBlockingQueue<Boolean> queue;
                final Map.Entry<ClassTestDefinition, StealableWorker<ClassTestDefinition>> entry = iterator.next();
                synchronized (tryStealingMap) {
                    Map<ClassTestDefinition, BlockingQueue<Boolean>> remoteStealings = tryStealingMap.computeIfAbsent(entry.getValue(), k -> new HashMap<>());
                    queue = new ArrayBlockingQueue<>(1);
                    remoteStealings.put(entry.getKey(), queue);
                }
                return new NextPlanned(entry, queue);
            } finally {
                iterator.remove();
            }
        }
    }

    private class RunStopped<D extends TestDefinition> implements Runnable {
        private final StealableWorker<D> processor;

        public RunStopped(StealableWorker<D> processor) {
            this.processor = processor;
        }

        @Override
        public void run() {
            if (!plannedClasses.isEmpty()) {
                plannedClasses.entrySet().removeIf(entry -> entry.getValue() == processor);
            }
            synchronized (tryStealingMap) {
                final Map<ClassTestDefinition, BlockingQueue<Boolean>> waitingMap = tryStealingMap.get(processor);
                if (waitingMap != null) {
                    for (BlockingQueue<Boolean> queue : waitingMap.values()) {
                        queue.add(false);
                    }
                }
            }
        }
    }
}
