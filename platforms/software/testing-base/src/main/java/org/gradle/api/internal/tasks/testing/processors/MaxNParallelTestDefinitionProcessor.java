/*
 * Copyright 2010 the original author or authors.
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
import org.gradle.internal.Cast;
import org.gradle.internal.Factory;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.actor.Actor;
import org.gradle.internal.actor.ActorFactory;
import org.gradle.internal.concurrent.CompositeStoppable;
import org.gradle.internal.dispatch.DispatchException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import static java.util.Comparator.comparing;

/**
 * Manages a set of parallel {@link TestDefinitionProcessor}s.
 * <p>
 * Uses a simple round-robin algorithm to assign test definitions to processors.
 */
public class MaxNParallelTestDefinitionProcessor<D extends TestDefinition> implements TestDefinitionProcessor<D> {
    private final int maxProcessors;
    // In theory this should be a Factory<? extends TestDefinitionProcessor<? extends D>>
    // for full compatibility, but in practice we don't need it.
    private final Factory<TestDefinitionProcessor<D>> factory;
    private final ActorFactory actorFactory;
    private final Map<String, Long> estimatedTestClassDurations;
    private final List<TestDefinitionProcessor<D>> processors = new ArrayList<>();
    private final List<TestDefinitionProcessor<D>> rawProcessors = new ArrayList<>();
    private final List<Actor> actors = new ArrayList<Actor>();
    private final SortedSet<EstimatedProcessorEntry<D>> estimatedProcessorDuration = new TreeSet<>(comparing(EstimatedProcessorEntry::getDuration));
    private TestResultProcessor resultProcessor;
    private int pos;
    private Actor resultProcessorActor;
    private volatile boolean stoppedNow;

    public MaxNParallelTestDefinitionProcessor(int maxProcessors, Factory<TestDefinitionProcessor<D>> factory, ActorFactory actorFactory) {
        this(maxProcessors, factory, actorFactory, Map.of());
    }

    public MaxNParallelTestDefinitionProcessor(int maxProcessors, Factory<TestDefinitionProcessor<D>> factory, ActorFactory actorFactory, Map<String, Long> estimatedTestClassDurations) {
        this.maxProcessors = maxProcessors;
        this.factory = factory;
        this.actorFactory = actorFactory;
        this.estimatedTestClassDurations = estimatedTestClassDurations;
    }

    @Override
    public void startProcessing(TestResultProcessor resultProcessor) {
        // Create a processor that processes events in its own thread
        resultProcessorActor = actorFactory.createActor(resultProcessor);
        this.resultProcessor = resultProcessorActor.getProxy(TestResultProcessor.class);
    }

    @Override
    public void processTestDefinition(D testDefinition) {
        if (stoppedNow) {
            return;
        }

        Long estimatedDuration = estimatedTestClassDurations.getOrDefault(testDefinition.getId(), 0L);
        TestDefinitionProcessor<D> processor;
        if (processors.size() < maxProcessors) {
            processor = factory.create();
            rawProcessors.add(processor);
            Actor actor = actorFactory.createActor(processor);
            processor = Cast.uncheckedNonnullCast(actor.getProxy(TestDefinitionProcessor.class));
            actors.add(actor);
            processors.add(processor);
            processor.startProcessing(resultProcessor);
            estimatedProcessorDuration.add(new EstimatedProcessorEntry<>(processor, estimatedDuration));
        } else if (estimatedDuration <= 0L) {
            processor = processors.get(pos);
            pos = (pos + 1) % processors.size();
        } else {
            processor = estimatedProcessorDuration.first().add(estimatedDuration);
        }
        processor.processTestDefinition(testDefinition);
    }

    @Override
    public void stop() {
        try {
            CompositeStoppable.stoppable(processors).add(actors).add(resultProcessorActor).stop();
        } catch (DispatchException e) {
            throw UncheckedException.throwAsUncheckedException(e.getCause());
        }
    }

    @Override
    public void stopNow() {
        stoppedNow = true;
        for (TestDefinitionProcessor<D> processor : rawProcessors) {
            processor.stopNow();
        }
    }

    static class EstimatedProcessorEntry<D extends TestDefinition> {
        final TestDefinitionProcessor<D> processor;
        long duration;

        public EstimatedProcessorEntry(TestDefinitionProcessor<D> processor, long duration) {
            this.processor = processor;
            this.duration = duration;
        }

        public TestDefinitionProcessor<D> getProcessor() {
            return processor;
        }
        public TestDefinitionProcessor<D> add(long duration) {
            this.duration +=duration;
            return processor;
        }

        public long getDuration() {
            return duration;
        }

        public void setDuration(long duration) {
            this.duration = duration;
        }
    }
}
