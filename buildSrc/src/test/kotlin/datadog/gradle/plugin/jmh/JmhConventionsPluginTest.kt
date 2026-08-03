package datadog.gradle.plugin.jmh

import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension.Companion.TEST_JVM_CONSTRAINTS
import datadog.gradle.plugin.testJvmConstraints.TestJvmSpec
import me.champeau.jmh.JmhParameters
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.JavaVersion
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

class JmhConventionsPluginTest {
  @Test
  fun `plugin applies jmh and test-jvm-constraints`() {
    val project = ProjectBuilder.builder().build()

    project.pluginManager.apply("dd-trace-java.jmh-conventions")

    assertThat(project.plugins.hasPlugin("me.champeau.jmh")).isTrue()
    assertThat(project.extensions.findByName(TEST_JVM_CONSTRAINTS)).isNotNull()
  }

  @Test
  fun `plugin provides the test jvm as an overridable default`() {
    val propertyName = "org.gradle.project.${TestJvmSpec.TEST_JVM}"
    val previousValue = System.setProperty(propertyName, JavaVersion.current().majorVersion)

    try {
      val project = ProjectBuilder.builder().build()

      project.pluginManager.apply("dd-trace-java.jmh-conventions")

      val jmh = project.extensions.getByType(JmhParameters::class.java)
      val expectedExecutable = TestJvmSpec(project).javaTestLauncher.get().executablePath.asFile.absolutePath
      assertThat(jmh.jvm.get()).isEqualTo(expectedExecutable)

      jmh.jvm.set("module-jvm")
      assertThat(jmh.jvm.get()).isEqualTo("module-jvm")
    } finally {
      if (previousValue == null) {
        System.clearProperty(propertyName)
      } else {
        System.setProperty(propertyName, previousValue)
      }
    }
  }

  @Test
  fun `plugin provides jmh project properties as defaults`() {
    withGradleProperties(
      "jmh.includes" to "FooBenchmark, BarBenchmark",
      "jmh.profilers" to "stack, gc",
      "jmh.forks" to "1",
      "jmh.threads" to "1",
    ) {
      val project = ProjectBuilder.builder().build()

      project.pluginManager.apply("dd-trace-java.jmh-conventions")

      val jmh = project.extensions.getByType(JmhParameters::class.java)
      assertThat(jmh.includes.get()).containsExactly("FooBenchmark|BarBenchmark")
      assertThat(jmh.profilers.get()).containsExactly("stack", "gc")
      assertThat(jmh.fork.get()).isEqualTo(1)
      assertThat(jmh.threads.get()).isEqualTo(1)
    }
  }

  @Test
  fun `jmh properties are absent when the project properties are not set`() {
    val project = ProjectBuilder.builder().build()

    project.pluginManager.apply("dd-trace-java.jmh-conventions")

    val jmh = project.extensions.getByType(JmhParameters::class.java)
    assertThat(jmh.includes.get()).isEmpty()
    assertThat(jmh.profilers.get()).isEmpty()
    assertThat(jmh.fork.isPresent).isFalse()
    assertThat(jmh.threads.isPresent).isFalse()
  }

  @Test
  fun `module jmh settings override project property defaults`() {
    withGradleProperties(
      "jmh.profilers" to "async",
      "jmh.forks" to "1",
    ) {
      val project = ProjectBuilder.builder().build()

      project.pluginManager.apply("dd-trace-java.jmh-conventions")

      val jmh = project.extensions.getByType(JmhParameters::class.java)
      jmh.profilers.set(listOf("gc"))
      jmh.fork.set(4)

      assertThat(jmh.profilers.get()).containsExactly("gc")
      assertThat(jmh.fork.get()).isEqualTo(4)
    }
  }

  @Test
  fun `applying test-jvm-constraints before jmh-conventions is idempotent`() {
    val project = ProjectBuilder.builder().build()

    project.pluginManager.apply("dd-trace-java.test-jvm-constraints")
    project.pluginManager.apply("dd-trace-java.jmh-conventions")

    assertThat(project.extensions.findByName(TEST_JVM_CONSTRAINTS)).isNotNull()
  }

  private fun withGradleProperties(vararg properties: Pair<String, String>, assertions: () -> Unit) {
    val systemProperties = properties.associate { (name, value) -> "org.gradle.project.$name" to value }
    val previousValues = systemProperties.keys.associateWith(System::getProperty)

    try {
      systemProperties.forEach(System::setProperty)
      assertions()
    } finally {
      previousValues.forEach { (name, value) ->
        if (value == null) {
          System.clearProperty(name)
        } else {
          System.setProperty(name, value)
        }
      }
    }
  }
}
