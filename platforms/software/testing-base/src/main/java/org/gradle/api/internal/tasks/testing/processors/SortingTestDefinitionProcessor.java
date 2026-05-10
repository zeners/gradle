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

package org.gradle.api.internal.tasks.testing.processors;

import org.gradle.api.internal.tasks.testing.TestDefinition;
import org.gradle.api.internal.tasks.testing.TestDefinitionProcessor;
import org.gradle.api.internal.tasks.testing.TestResultProcessor;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

public class SortingTestDefinitionProcessor<D extends TestDefinition> implements TestDefinitionProcessor<D> {

    private final Map<String, Long> estimatedDurations;
    private final TestDefinitionProcessor<D> processor;
    private final SortedSet<Map.Entry<D, Long>> withKnownDuration = new TreeSet<>(Map.Entry.comparingByValue());
    private final Set<D> firstRuns = new LinkedHashSet<>();

    public SortingTestDefinitionProcessor(Map<String, Long> estimatedDurations, TestDefinitionProcessor<D> processor) {
        this.estimatedDurations = estimatedDurations;
        this.processor = processor;
    }

    @Override
    public void startProcessing(TestResultProcessor resultProcessor) {
        processor.startProcessing(resultProcessor);
    }

    @Override
    public void processTestDefinition(D testDefinition) {
        Long duration = estimatedDurations.getOrDefault(testDefinition.getId(), 0L);
        if (duration <= 0L) {
            firstRuns.add(testDefinition);
        } else {
            withKnownDuration.add(Map.entry(testDefinition, duration));
        }
    }

    @Override
    public void stop() {
        firstRuns.forEach(processor::processTestDefinition);
        withKnownDuration.forEach(e -> processor.processTestDefinition(e.getKey()));
        processor.stop();
    }

    @Override
    public void stopNow() {
        processor.stopNow();
    }
}
