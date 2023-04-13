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

package org.gradle.api.internal.tasks.testing.processors

import org.gradle.api.internal.tasks.testing.DefaultTestClassRunInfo
import org.gradle.api.internal.tasks.testing.TestClassProcessor
import org.gradle.api.internal.tasks.testing.TestResultProcessor
import org.gradle.api.tasks.testing.TestClassSortAndAssignStrategy
import spock.lang.Specification

class RunPreviousFailedFirstTestClassProcessorTest extends Specification {
    TestClassProcessor delegate = Mock()
    TestResultProcessor testResultProcessor = Mock()
    RunPreviousFailedFirstTestClassProcessor processor
    TestClassSortAndAssignStrategy strategy

    def 'previous failed test classes should be passed to delegate first'() {
        given:
        strategy = TestClassSortAndAssignStrategy.WellKnown.sortByDurations([Class3: 1L, Class1: 1L, Class5: 2L]).create()
        processor = new RunPreviousFailedFirstTestClassProcessor(['Class3', 'Class4'] as Set, strategy, delegate)

        when:
        processor.startProcessing(testResultProcessor)
        ['Class1', 'Class2', 'Class3', 'Class4', 'Class5'].each { processor.processTestClass(new DefaultTestClassRunInfo(it)) }
        processor.stop()

        then:
        1 * delegate.startProcessing(testResultProcessor)
        then:
        1 * delegate.processTestClass(new DefaultTestClassRunInfo('Class4'))
        then:
        1 * delegate.processTestClass(new DefaultTestClassRunInfo('Class3'))
        then:
        1 * delegate.processTestClass(new DefaultTestClassRunInfo('Class2'))
        then:
        1 * delegate.processTestClass(new DefaultTestClassRunInfo('Class5'))
        then:
        1 * delegate.processTestClass(new DefaultTestClassRunInfo('Class1'))
        then:
        1 * delegate.stop()
    }
}
