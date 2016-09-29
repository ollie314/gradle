/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.groovy.compile

import groovy.transform.NotYetImplemented
import org.gradle.AbstractCachedCompileIntegrationTest
import org.gradle.test.fixtures.file.TestFile

class CachedGroovyCompileIntegrationTest extends AbstractCachedCompileIntegrationTest {
    String compilationTask = ':compileGroovy'
    String compiledFile = "build/classes/main/Hello.class"

    @Override
    def setupProjectInDirectory(TestFile project = temporaryFolder.testDirectory) {
        project.with {
            file('build.gradle').text = """
            plugins {
                id 'groovy'
                id 'application'
            }

            mainClassName = "Hello"

            repositories {
                mavenCentral()
            }

            dependencies {
                compile 'org.codehaus.groovy:groovy-all:2.4.7'
            }
        """.stripIndent()

        file('src/main/groovy/Hello.groovy') << """
            class Hello {
                public static void main(String... args) {
                    println "Hello!"
                }
            }
        """.stripIndent()
        }
    }

    def "compilation is cached if location of the Groovy library is different"() {
        given:
        populateCache()

        executer.requireOwnGradleUserHomeDir() // dependency will be downloaded into a different directory

        when:
        succeedsWithCache compilationTask

        then:
        compileIsCached()
    }

    def "compilation is not cached if we change the version of the Groovy library"() {
        given:
        populateCache()
        buildFile.text = """
            plugins { id 'groovy' }

            repositories { mavenCentral() }
            dependencies { compile 'org.codehaus.groovy:groovy-all:2.4.5' }
        """.stripIndent()

        when:
        succeedsWithCache compilationTask

        then:
        compileIsNotCached()
    }

    @NotYetImplemented
    def "joint Java and Groovy compilation can be cached"() {
        given:
        buildScript """
            plugins {
                id 'groovy'
            }

            dependencies {
                compile localGroovy()
            }
        """
        file('src/main/java/RequiredByGroovy.java') << """
            public class RequiredByGroovy {
                public static void printSomething() {
                    java.lang.System.out.println("Hello from Java");
                }
            }
        """
        file('src/main/java/RequiredByGroovy.java').makeOlder()

        file('src/main/groovy/UsesJava.groovy') << """
            @groovy.transform.CompileStatic
            class UsesJava {
                public void printSomething() {
                    RequiredByGroovy.printSomething()
                }
            }
        """
        file('src/main/groovy/UsesJava.groovy').makeOlder()
        def compiledJavaClass = file('build/classes/main/RequiredByGroovy.class')
        def compiledGroovyClass = file('build/classes/main/UsesJava.class')

        when:
        succeedsWithCache ':compileJava', ':compileGroovy'

        then:
        compiledJavaClass.exists()
        compiledGroovyClass.exists()

        when:
        succeedsWithCache ':clean', ':compileJava'

        then:
        skippedTasks.contains(':compileJava')

        when:
        // This line is crucial to expose the bug
        // When doing this and then loading the classes for
        // compileGroovy from the cache the compiled java
        // classes are replaced and recorded as changed
        compiledJavaClass.makeOlder()
        succeedsWithCache ':compileGroovy'

        then:
        skippedTasks.containsAll([':compileJava', ':compileGroovy'])

        when:
        file('src/main/java/RequiredByGroovy.java').text = """
            public class RequiredByGroovy {
                public static void printSomethingNew() {
                    java.lang.System.out.println("Hello from Java");
                    // Different
                }
            }
        """
        file('src/main/groovy/UsesJava.groovy').text = """
            @groovy.transform.CompileStatic
            class UsesJava {
                public void printSomething() {
                    RequiredByGroovy.printSomethingNew()
                    // Some comment
                }
            }
        """

        succeedsWithCache ':compileGroovy'

        then:
        compiledJavaClass.exists()
        compiledGroovyClass.exists()
    }
}
