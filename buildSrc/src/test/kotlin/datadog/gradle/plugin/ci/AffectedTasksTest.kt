package datadog.gradle.plugin.ci

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class AffectedTasksTest {
  private val root = File("/repo")

  private fun dir(path: String) = File(root, path)

  private fun source(project: String, path: String, classesTaskName: String, isMain: Boolean = false) =
    SourceDirectory(project, dir(path), classesTaskName, isMain)

  private val instSources = listOf(
    source("inst", "inst/src/main/java", "classes", isMain = true),
    source("inst", "inst/src/main/java11", "main_java11Classes", isMain = true),
    source("inst", "inst/src/test/groovy", "testClasses"),
    source("inst", "inst/src/test/groovy", "latestDepTestClasses"),
    source("inst", "inst/src/testFixtures/java", "testFixturesClasses"),
    source("inst", "inst/src/jmh/java", "jmhClasses"),
  )

  @Test
  fun `test change affects every suite compiling the test sources`() {
    val affected = affectedTasks(mapOf("inst" to listOf(dir("inst/src/test/groovy/FooTest.groovy"))), instSources)

    assertThat(affected).isEqualTo(mapOf("inst" to setOf("testClasses", "latestDepTestClasses")))
  }

  @Test
  fun `main change affects every task of the project`() {
    val affected = affectedTasks(
      mapOf("inst" to listOf(dir("inst/src/main/java/Foo.java"), dir("inst/src/main/java11/Bar.java"))),
      instSources,
    )

    assertThat(affected).isEqualTo(mapOf("inst" to setOf(ALL_TASKS)))
  }

  @Test
  fun `test fixtures and benchmark changes affect their own classes task`() {
    val affected = affectedTasks(
      mapOf("inst" to listOf(dir("inst/src/testFixtures/java/Fixture.java"), dir("inst/src/jmh/java/Bench.java"))),
      instSources,
    )

    assertThat(affected).isEqualTo(mapOf("inst" to setOf("testFixturesClasses", "jmhClasses")))
  }

  @Test
  fun `change outside any source set affects every task of the project`() {
    val affected = affectedTasks(
      mapOf("inst" to listOf(dir("inst/build.gradle"), dir("inst/src/test/proto/foo.proto"))),
      instSources,
    )

    assertThat(affected).isEqualTo(mapOf("inst" to setOf(ALL_TASKS)))
  }

  @Test
  fun `change in sources compiled by another project affects that project too`() {
    val sources = listOf(source("user", "common/src/test/groovy", "testClasses"))

    val affected = affectedTasks(mapOf("common" to listOf(dir("common/src/test/groovy/SharedTest.groovy"))), sources)

    assertThat(affected).isEqualTo(mapOf("user" to setOf("testClasses"), "common" to setOf(ALL_TASKS)))
  }

  @Test
  fun `source directory prefix of another directory name does not match`() {
    val affected = affectedTasks(mapOf("inst" to listOf(dir("inst/src/test/groovy-extra/Foo.groovy"))), instSources)

    assertThat(affected).isEqualTo(mapOf("inst" to setOf(ALL_TASKS)))
  }
}
