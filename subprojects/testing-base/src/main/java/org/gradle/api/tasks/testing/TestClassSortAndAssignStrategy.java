/*
 * Copyright 2023 the original author or authors.
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

package org.gradle.api.tasks.testing;

import org.gradle.api.Incubating;
import org.gradle.api.NonNullApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.Map;

/**
 * Defines strategy to sort test-classes and assigning to processors<br>
 * Simplest behaviour is to not sort and use round-robin in {@link WellKnown#UNSORTED_ROUND_ROBIN UNSORTED_ROUND_ROBIN}
 *
 * @since 8.2
 */
@Incubating
public abstract class TestClassSortAndAssignStrategy {
    /**
     * max processors used
     *
     * @since 8.2
     */
    @Incubating
    protected int count = 1;

    /**
     * new sort and assigning strategy
     *
     * @since 8.2
     */
    @Incubating
    protected TestClassSortAndAssignStrategy() {
    }

    /**
     * will be set after sort and bevor any assigning, can be used to reset state information
     *
     * @since 8.2
     */
    @Incubating
    public void maxProcessorsUsed(int processorCount) {
        count = Math.max(processorCount, 1);
    }

    /**
     * Comparator for sorting planned test classes
     *
     * @return Comparator to sort test classes or null, to keep as is
     *
     * @since 8.2
     */
    @Incubating
    @Nullable
    public Comparator<String> getSorter() {
        return null;
    }

    /**
     * assigning test class to processor
     *
     * @param testClass test-class to assign to processor, will be created, if not returned before
     * @return zero-based index for assigned processor, will be cut to not less than zero at minimum
     * and current maximum + 1, but not more than max, at maximum
     * @since 8.2
     */
    @Incubating
    public abstract int assign(String testClass);

    /**
     * will be called before any sort
     *
     * new sort and assigning strategy
     *
     * @since 8.2
     */
    @Incubating
    public void startProcessing() {
    }

    /**
     * don't recreate, if fallback is needed
     *
     * @since 8.2
     */
    @Incubating
    public final boolean isFallBack() {
        return this instanceof WellKnown.UnsortedRoundRobin;
    }

    /**
     * Lists default strategies, should expand with the concept of PR #12012
     *
     * @since 8.2
     */
    @Incubating
    @NonNullApi
    public enum WellKnown implements StrategyFactory {
        /**
         * use default round-robin strategy without sorting
         *
         * @since 8.2
         */
        @Incubating
        UNSORTED_ROUND_ROBIN {
            @Override
            @Nonnull
            public TestClassSortAndAssignStrategy create() {
                return new UnsortedRoundRobin();
            }
        };

        /**
         * create sorting and assigning strategy based on estimated test class run time durations
         *
         * @param durations estimated test class run time durations to be used
         * @since 8.2
         */
        @Incubating
        public static StrategyFactory sortByDurations(final Map<String, Long> durations) {
            return new StrategyFactory() {
                @Override
                public TestClassSortAndAssignStrategy create() {
                    return new DurationSortedRoundRobinFallBack(durations);
                }
            };
        }

        @Nonnull
        @Override
        public abstract TestClassSortAndAssignStrategy create();

        private static class UnsortedRoundRobin extends TestClassSortAndAssignStrategy {
            int last = -1;

            @Override
            public int assign(String testClass) {
                last = (last + 1) % count;
                return last;
            }

            @Override
            public void startProcessing() {
                last = -1;
            }
        }

        static class DurationSortedRoundRobinFallBack extends TestClassSortAndAssignStrategy {
            private static final Logger LOGGER = LoggerFactory.getLogger(DurationSortedRoundRobinFallBack.class);
            private final Map<String, Long> durations;
            private final TestClassSortAndAssignStrategy fallBack;
            private final ClassByDurationComparator sorter = new ClassByDurationComparator();
            private long[] assignedDurations;

            public DurationSortedRoundRobinFallBack(Map<String, Long> durations) {
                this.durations = durations;
                fallBack = UNSORTED_ROUND_ROBIN.create();
                LOGGER.info("Initialize duration-sorted-strategy with {} classes", durations.size());
            }

            @Override
            public void maxProcessorsUsed(int processorCount) {
                super.maxProcessorsUsed(processorCount);
                fallBack.maxProcessorsUsed(processorCount);
                assignedDurations = new long[processorCount];
            }

            @Override
            public Comparator<String> getSorter() {
                return sorter;
            }

            @Override
            public int assign(String testClass) {
                final Long lastDuration = durations.get(testClass);
                if (lastDuration == null || lastDuration <= 0) {
                    return fallBack.assign(testClass);
                } else {
                    int index = 0;
                    long currMin = assignedDurations[0];
                    for (int idx = 1; idx < count; idx++) {
                        if (assignedDurations[idx] < currMin) {
                            currMin = assignedDurations[idx];
                            index = idx;
                        }
                    }
                    assignedDurations[index] += lastDuration;
                    return index;
                }
            }

            @NonNullApi
            private class ClassByDurationComparator implements Comparator<String> {
                public ClassByDurationComparator() {
                }

                @Override
                public int compare(String o1, String o2) {
                    Long d1 = durations.get(o1);
                    Long d2 = durations.get(o2);
                    if (d1 == null && d2 == null) {
                        return 0;
                    } else if (d1 != null && d2 != null) {
                        return d2.compareTo(d1);
                    } else if (d1 == null) {
                        return -1;
                    } else {
                        return 1;
                    }
                }
            }
        }
    }

    /**
     * Builder for {@link TestClassSortAndAssignStrategy}
     *
     * @since 8.2
     */
    @Incubating
    public interface StrategyFactory {
        /**
         * build strategy everytime needed
         *
         * @since 8.2
         */
        @Incubating
        TestClassSortAndAssignStrategy create();
    }
}
