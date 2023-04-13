/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.api.internal.tasks.testing.processors;

import org.gradle.api.NonNullApi;
import org.gradle.api.internal.tasks.testing.TestClassProcessor;
import org.gradle.api.internal.tasks.testing.TestClassRunInfo;
import org.gradle.api.internal.tasks.testing.TestResultProcessor;
import org.gradle.api.tasks.testing.TestClassSortAndAssignStrategy;
import org.gradle.util.internal.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * In order to speed up the development feedback cycle, this class guarantee previous failed test classes
 * to be passed to its delegate first.
 */
public class RunPreviousFailedFirstTestClassProcessor implements TestClassProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(RunPreviousFailedFirstTestClassProcessor.class);
    private final Set<String> previousFailedTestClasses;
    private final TestClassSortAndAssignStrategy sortAndAssignStrategy;
    private final TestClassProcessor delegate;
    private final LinkedHashSet<TestClassRunInfo> prioritizedTestClasses = new LinkedHashSet<TestClassRunInfo>();
    private final LinkedHashSet<TestClassRunInfo> otherTestClasses = new LinkedHashSet<TestClassRunInfo>();
    @Nullable
    private final Comparator<TestClassRunInfo> sorter;

    public RunPreviousFailedFirstTestClassProcessor(Set<String> previousFailedTestClasses, @Nonnull TestClassSortAndAssignStrategy sortAndAssignStrategy, TestClassProcessor delegate) {
        this.previousFailedTestClasses = previousFailedTestClasses;
        this.sortAndAssignStrategy = sortAndAssignStrategy;
        this.delegate = delegate;
        sorter = TestClassRunInfoComparator.of(sortAndAssignStrategy.getSorter());
    }

    @Override
    public void startProcessing(TestResultProcessor resultProcessor) {
        delegate.startProcessing(resultProcessor);
        sortAndAssignStrategy.startProcessing();
    }

    @Override
    public void processTestClass(TestClassRunInfo testClass) {
        if (previousFailedTestClasses.contains(testClass.getTestClassName())) {
            prioritizedTestClasses.add(testClass);
        } else {
            otherTestClasses.add(testClass);
        }
    }

    @Override
    public void stop() {
        for (TestClassRunInfo test : sorting(prioritizedTestClasses)) {
            delegate.processTestClass(test);
        }
        for (TestClassRunInfo test : sorting(otherTestClasses)) {
            delegate.processTestClass(test);
        }
        delegate.stop();
    }

    @Override
    public void stopNow() {
        delegate.stopNow();
    }

    private Collection<TestClassRunInfo> sorting(Collection<TestClassRunInfo> classes) {
        if (sorter != null && !classes.isEmpty()) {
            LOGGER.info("Sorting {} classes ...", classes.size());
            return CollectionUtils.sort(classes, sorter);
        }
        return classes;
    }

    @NonNullApi
    private static class TestClassRunInfoComparator implements Comparator<TestClassRunInfo> {
        private final Comparator<String> comparator;

        private TestClassRunInfoComparator(Comparator<String> comparator) {
            this.comparator = comparator;
        }

        @Nullable
        private static Comparator<TestClassRunInfo> of(@Nullable final Comparator<String> comparator) {
            return comparator == null ? null : new TestClassRunInfoComparator(comparator);
        }

        @Override
        public int compare(TestClassRunInfo o1, TestClassRunInfo o2) {
            return comparator.compare(o1.getTestClassName(), o2.getTestClassName());
        }
    }
}
