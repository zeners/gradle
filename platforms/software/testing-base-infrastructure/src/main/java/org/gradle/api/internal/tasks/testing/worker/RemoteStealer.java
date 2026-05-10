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

package org.gradle.api.internal.tasks.testing.worker;

import org.gradle.api.internal.tasks.testing.TestDefinitionStealer;
import org.gradle.api.internal.tasks.testing.TestDefinition;

import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * interface for remote calling {@link TestDefinitionStealer}, calls don't block
 */
public interface RemoteStealer extends Serializable {
    /**
     * removes asynchron testClass from plannedClasses
     */
    void remove(TestDefinition testClass);

    /**
     * looking for next test-class, that have been successful {@link TestWorker#handOver hand over}<br>
     */
    void tryStealing();

    /**
     * result of hand over of giving test-class
     */
    void handOver(HandOverResult result);

    class HandOverResult implements Serializable {
        @Nullable
        private final TestDefinition testDefinition;
        private final boolean success;

        public HandOverResult(@Nullable TestDefinition testDefinition, boolean success) {
            this.testDefinition = testDefinition;
            this.success = success;
        }

        @Nullable
        public TestDefinition getTestDefinition() {
            return testDefinition;
        }

        @Override
        public String toString() {
            return String.format("HandOverResult %s %s", testDefinition == null ? "null" : testDefinition.getId(), success);
        }

        public boolean isSuccess() {
            return success;
        }

    }
}
