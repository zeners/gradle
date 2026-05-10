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

import org.jspecify.annotations.Nullable;

import javax.annotation.CheckForNull;


/**
 * Interface to implement stealing of {@link TestDefinition} in parallel execution environment
 */

public interface TestDefinitionStealer<D extends TestDefinition> {
    final class NoSteal<D extends TestDefinition> implements TestDefinitionStealer<D> {
        @Override
        public void add(D testDefinition, StealableWorker<D> processor) {
            // do nothing
        }

        @Override
        public void remove(D testDefinition) {
            // do nothing
        }

        @Override
        public void stopped(StealableWorker<D> processor) {
            // do nothing
        }

        @CheckForNull
        @Override
        public D trySteal() {
            return null;
        }

        @Override
        public void handOverTestDefinition(StealableWorker<D> testWorker, D testDefinition, boolean success) {
            // do nothing
        }
    }

    /**
     * add new stealable testDefinition to planned classes, wich can be "stolen"
     *
     * @param testDefinition added class
     * @param processor processor, from which testDefinition can be stolen
     */
    void add(D testDefinition, StealableWorker<D> processor);

    /**
     * remove testDefinition from planned classes when startet
     *
     * @param testDefinition startet test class
     */
    void remove(D testDefinition);

    /**
     * remove all references to this processor, because he stopped
     *
     * @param processor stopped processor, for which cleanup should be done
     */
    void stopped(StealableWorker<D> processor);

    /**
     * looking for next test-definition, that have been successful {@link StealableWorker#handOver hand over}
     *
     * @return next {@link StealableWorker#handOver hand over} test-class or null, if nothing left
     */
    @Nullable
    D trySteal();

    /**
     * result of {@link StealableWorker#handOver} request
     *
     * @param testWorker processor, which should hand over the testDefinition
     * @param testDefinition requested test class
     * @param success success of hand over, false means, class was startet since request was initiated
     */
    void handOverTestDefinition(StealableWorker<D> testWorker, D testDefinition, boolean success);

    interface StealableWorker<D extends TestDefinition> {
        void handOver(D testDefinition);
    }
}
