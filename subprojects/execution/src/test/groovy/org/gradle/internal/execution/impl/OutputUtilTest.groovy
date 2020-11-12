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

package org.gradle.internal.execution.impl

import org.gradle.api.internal.file.TestFiles
import org.gradle.internal.MutableReference
import org.gradle.internal.snapshot.CompleteFileSystemLocationSnapshot
import org.gradle.internal.snapshot.FileSystemSnapshot
import org.gradle.internal.snapshot.SnapshotVisitorUtil
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.junit.Rule
import spock.lang.Specification

import static OutputUtil.filterOutputWithOverlapAfterExecution
import static OutputUtil.filterOutputWithOverlapBeforeExecution
import static org.gradle.internal.snapshot.FileSystemSnapshot.EMPTY

class OutputUtilTest extends Specification {

    @Rule
    final TestNameTestDirectoryProvider temporaryFolder = TestNameTestDirectoryProvider.newInstance(getClass())

    def virtualFileSystem = TestFiles.virtualFileSystem()
    def fileSystemAccess = TestFiles.fileSystemAccess(virtualFileSystem)

    def "pre-existing directories are filtered"() {
        def outputDir = temporaryFolder.file("outputDir").createDir()
        def beforeExecution = snapshotOutput(outputDir)
        outputDir.file()

        when:
        def filteredOutputs = filterOutputWithOverlapAfterExecution(EMPTY, beforeExecution, beforeExecution)
        then:
        collectFiles(filteredOutputs) == [outputDir]

        when:
        def outputDirFile = outputDir.file("in-output-dir").createFile()
        virtualFileSystem.invalidateAll()
        def afterExecution = snapshotOutput(outputDir)
        filteredOutputs = filterOutputWithOverlapAfterExecution(EMPTY, beforeExecution, afterExecution)
        then:
        collectFiles(filteredOutputs) == [outputDir, outputDirFile]
    }

    def "only newly created files in directory are part of filtered outputs"() {
        def outputDir = temporaryFolder.file("outputDir").createDir()
        outputDir.file("outputOfOther").createFile()
        def beforeExecution = snapshotOutput(outputDir)

        when:
        def filteredOutputs = filterOutputWithOverlapAfterExecution(EMPTY, beforeExecution, beforeExecution)
        then:
        collectFiles(filteredOutputs) == [outputDir]

        when:
        def outputOfCurrent = outputDir.file("outputOfCurrent").createFile()
        def afterExecution = snapshotOutput(outputDir)
        filteredOutputs = filterOutputWithOverlapAfterExecution(EMPTY, beforeExecution, afterExecution)
        then:
        collectFiles(filteredOutputs) == [outputDir, outputOfCurrent]
    }

    def "previous outputs remain outputs"() {
        def outputDir = temporaryFolder.file("outputDir").createDir()
        def outputDirFile = outputDir.file("outputOfCurrent").createFile()
        def previousExecution = snapshotOutput(outputDir)
        outputDir.file("outputOfOther").createFile()
        def beforeExecution = snapshotOutput(outputDir)

        when:
        def filteredOutputs = filterOutputWithOverlapAfterExecution(previousExecution, beforeExecution, beforeExecution)
        then:
        collectFiles(filteredOutputs) == [outputDir, outputDirFile]
    }

    def "missing files are ignored"() {
        def missingFile = temporaryFolder.file("missing")
        def beforeExecution = snapshotOutput(missingFile)
        expect:
        filterOutputWithOverlapAfterExecution(EMPTY, beforeExecution, beforeExecution) == EMPTY
    }

    def "added empty dir is captured"() {
        def emptyDir = temporaryFolder.file("emptyDir").createDir()
        def afterExecution = snapshotOutput(emptyDir)
        def beforeExecution = EMPTY
        expect:
        collectFiles(filterOutputWithOverlapAfterExecution(EMPTY, beforeExecution, afterExecution)) == [emptyDir]
        collectFiles(filterOutputWithOverlapAfterExecution(EMPTY, afterExecution, afterExecution)) == [emptyDir]
    }

    def "updated files in output directory are part of the output"() {
        def outputDir = temporaryFolder.createDir("outputDir")
        def existingFile = outputDir.file("some").createFile()
        def beforeExecution = snapshotOutput(outputDir)
        existingFile << "modified"
        def afterExecution = snapshotOutput(outputDir)
        expect:
        collectFiles(filterOutputWithOverlapAfterExecution(EMPTY, beforeExecution, afterExecution)) == [outputDir, existingFile]
    }

    def "updated files are part of the output"() {
        def existingFile = temporaryFolder.file("some").createFile()
        def beforeExecution = snapshotOutput(existingFile)
        existingFile << "modified"
        def afterExecution = snapshotOutput(existingFile)
        expect:
        collectFiles(filterOutputWithOverlapAfterExecution(EMPTY, beforeExecution, afterExecution)) == [existingFile]
    }

    def "removed files are not considered outputs"() {
        def outputDir = temporaryFolder.createDir("outputDir")
        def outputDirFile = outputDir.file("toBeDeleted").createFile()
        def previousExecution = snapshotOutput(outputDir)
        def beforeExecution = snapshotOutput(outputDir)
        outputDirFile.delete()
        def afterExecution = snapshotOutput(outputDir)

        expect:
        collectFiles(filterOutputWithOverlapAfterExecution(previousExecution, beforeExecution, afterExecution)) == [outputDir]
        collectFiles(filterOutputWithOverlapAfterExecution(EMPTY, previousExecution, afterExecution)) == [outputDir]
    }

    def "overlapping directories are not included"() {
        def outputDir = temporaryFolder.createDir("outputDir")
        outputDir.createDir("output-dir-2")
        def beforeExecution = snapshotOutput(outputDir)
        def outputDirFile = outputDir.createFile("outputDirFile")
        def afterExecution = snapshotOutput(outputDir)

        expect:
        collectFiles(filterOutputWithOverlapAfterExecution(EMPTY, beforeExecution, afterExecution)) == [outputDir, outputDirFile]
    }

    def "overlapping files are not part of the before execution snapshot"() {
        def outputDir = temporaryFolder.file("outputDir").createDir()
        def outputDirFile = outputDir.createFile("outputDirFile")
        def previousExecution = snapshotOutput(outputDir)
        outputDir.createFile("not-in-output")
        def beforeExecution = snapshotOutput(outputDir)

        expect:
        collectFiles(filterOutputWithOverlapBeforeExecution(previousExecution, beforeExecution)) == [outputDir, outputDirFile]
    }

    private FileSystemSnapshot snapshotOutput(File output) {
        virtualFileSystem.invalidateAll()
        MutableReference<CompleteFileSystemLocationSnapshot> result = MutableReference.empty()
        fileSystemAccess.read(output.getAbsolutePath(), result.&set)
        return result.get()
    }

    private static List<File> collectFiles(FileSystemSnapshot fileSystemSnapshots) {
        SnapshotVisitorUtil.getAbsolutePaths(fileSystemSnapshots, true).collect { new File(it) }
    }
}
