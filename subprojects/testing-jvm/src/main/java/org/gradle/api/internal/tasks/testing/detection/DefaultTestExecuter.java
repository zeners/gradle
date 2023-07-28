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

package org.gradle.api.internal.tasks.testing.detection;

import org.gradle.api.file.FileTree;
import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.api.internal.classpath.ModuleRegistry;
import org.gradle.api.internal.tasks.testing.JvmTestClassStealer;
import org.gradle.api.internal.tasks.testing.JvmTestExecutionSpec;
import org.gradle.api.internal.tasks.testing.TestClassProcessor;
import org.gradle.api.internal.tasks.testing.TestClassStealer;
import org.gradle.api.internal.tasks.testing.TestExecuter;
import org.gradle.api.internal.tasks.testing.TestFramework;
import org.gradle.api.internal.tasks.testing.TestResultProcessor;
import org.gradle.api.internal.tasks.testing.WorkerTestClassProcessorFactory;
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter;
import org.gradle.api.internal.tasks.testing.processors.MaxNParallelTestClassProcessor;
import org.gradle.api.internal.tasks.testing.processors.PatternMatchTestClassProcessor;
import org.gradle.api.internal.tasks.testing.processors.RestartEveryNTestClassProcessor;
import org.gradle.api.internal.tasks.testing.processors.RunPreviousFailedFirstTestClassProcessor;
import org.gradle.api.internal.tasks.testing.processors.TestMainAction;
import org.gradle.api.internal.tasks.testing.worker.ForkedTestClasspath;
import org.gradle.api.internal.tasks.testing.worker.ForkingTestClassProcessor;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.testing.TestClassSortAndAssignStrategy;
import org.gradle.internal.Factory;
import org.gradle.internal.actor.ActorFactory;
import org.gradle.internal.time.Clock;
import org.gradle.internal.work.WorkerLeaseService;
import org.gradle.process.internal.worker.WorkerProcessFactory;

import java.io.File;
import java.util.ArrayList;

/**
 * The default test class scanner factory.
 */
public class DefaultTestExecuter implements TestExecuter<JvmTestExecutionSpec> {

    private static final Logger LOGGER = Logging.getLogger(DefaultTestExecuter.class);

    private final WorkerProcessFactory workerFactory;
    private final ActorFactory actorFactory;
    private final ForkedTestClasspathFactory testClasspathFactory;
    private final WorkerLeaseService workerLeaseService;
    private final int maxWorkerCount;
    private final Clock clock;
    private final DocumentationRegistry documentationRegistry;
    private final DefaultTestFilter testFilter;
    private final TestClassSortAndAssignStrategy.StrategyFactory sortAndAssignStrategyFactory;
    private TestClassProcessor processor;

    public DefaultTestExecuter(
        WorkerProcessFactory workerFactory, ActorFactory actorFactory, ModuleRegistry moduleRegistry,
        WorkerLeaseService workerLeaseService, int maxWorkerCount,
        Clock clock, DocumentationRegistry documentationRegistry, DefaultTestFilter testFilter,
        TestClassSortAndAssignStrategy.StrategyFactory sortAndAssignStrategyFactory
    ) {
        this.workerFactory = workerFactory;
        this.actorFactory = actorFactory;
        this.testClasspathFactory = new ForkedTestClasspathFactory(moduleRegistry);
        this.workerLeaseService = workerLeaseService;
        this.maxWorkerCount = maxWorkerCount;
        this.clock = clock;
        this.documentationRegistry = documentationRegistry;
        this.testFilter = testFilter;
        this.sortAndAssignStrategyFactory = sortAndAssignStrategyFactory;
    }

    @Override
    public void execute(final JvmTestExecutionSpec testExecutionSpec, TestResultProcessor testResultProcessor) {
        final TestFramework testFramework = testExecutionSpec.getTestFramework();
        final WorkerTestClassProcessorFactory testInstanceFactory = testFramework.getProcessorFactory();

        final ForkedTestClasspath classpath = testClasspathFactory.create(
            testExecutionSpec.getClasspath(), testExecutionSpec.getModulePath(),
            testFramework, testExecutionSpec.getTestIsModule()
        );
        final int maxParallelForks = getMaxParallelForks(testExecutionSpec);
        final TestClassStealer testClassStealer = // only if not disabled by user, senseless in serialised environment, incompatible with forkEvery > 0
            testExecutionSpec.isAllowTestClassStealing() && maxParallelForks > 1 && testExecutionSpec.getForkEvery() == 0  ? new JvmTestClassStealer() : null;

        final Factory<TestClassProcessor> forkingProcessorFactory = new Factory<TestClassProcessor>() {
            @Override
            public TestClassProcessor create() {
                return new ForkingTestClassProcessor(workerLeaseService, workerFactory, testInstanceFactory, testExecutionSpec.getJavaForkOptions(),
                    classpath, testFramework.getWorkerConfigurationAction(), documentationRegistry, testClassStealer);
            }
        };
        final Factory<TestClassProcessor> reforkingProcessorFactory = new Factory<TestClassProcessor>() {
            @Override
            public TestClassProcessor create() {
                return new RestartEveryNTestClassProcessor(forkingProcessorFactory, testExecutionSpec.getForkEvery());
            }
        };
        TestClassSortAndAssignStrategy sortAndAssignStrategy = sortAndAssignStrategyFactory.create();
        if (sortAndAssignStrategy == null || maxParallelForks == 1 && !sortAndAssignStrategy.isFallBack()) {
            LOGGER.info("{}, so falling back to UNSORTED_ROUND_ROBIN", sortAndAssignStrategy == null ? "no sortAndAssignStrategy generated" : "sequential run");
            sortAndAssignStrategy = TestClassSortAndAssignStrategy.WellKnown.UNSORTED_ROUND_ROBIN.create();
        } else {
            LOGGER.info("use {} as sortAndAssignStrategy", sortAndAssignStrategy.getClass().getSimpleName());
        }
        processor =
            new PatternMatchTestClassProcessor(testFilter,
                new RunPreviousFailedFirstTestClassProcessor(testExecutionSpec.getPreviousFailedTestClasses(), sortAndAssignStrategy,
                    new MaxNParallelTestClassProcessor(maxParallelForks, reforkingProcessorFactory, actorFactory, sortAndAssignStrategy)));

        final FileTree testClassFiles = testExecutionSpec.getCandidateClassFiles();

        Runnable detector;
        if (testExecutionSpec.isScanForTestClasses() && testFramework.getDetector() != null) {
            TestFrameworkDetector testFrameworkDetector = testFramework.getDetector();
            testFrameworkDetector.setTestClasses(new ArrayList<File>(testExecutionSpec.getTestClassesDirs().getFiles()));
            testFrameworkDetector.setTestClasspath(classpath.getApplicationClasspath());
            detector = new DefaultTestClassScanner(testClassFiles, testFrameworkDetector, processor);
        } else {
            detector = new DefaultTestClassScanner(testClassFiles, null, processor);
        }

        new TestMainAction(detector, processor, testResultProcessor, workerLeaseService, clock, testExecutionSpec.getPath(), "Gradle Test Run " + testExecutionSpec.getIdentityPath()).run();
    }

    @Override
    public void stopNow() {
        if (processor != null) {
            processor.stopNow();
        }
    }

    private int getMaxParallelForks(JvmTestExecutionSpec testExecutionSpec) {
        int maxParallelForks = testExecutionSpec.getMaxParallelForks();
        if (maxParallelForks > maxWorkerCount) {
            LOGGER.info("{}.maxParallelForks ({}) is larger than max-workers ({}), forcing it to {}", testExecutionSpec.getPath(), maxParallelForks, maxWorkerCount, maxWorkerCount);
            maxParallelForks = maxWorkerCount;
        }
        return maxParallelForks;
    }
}
