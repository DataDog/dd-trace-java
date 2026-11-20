package datadog.gradle.plugin.naming

import datadog.gradle.plugin.GradleFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.FAILED
import org.junit.jupiter.api.Test
import java.io.File

class InstrumentationNamingPluginTest : GradleFixture() {
  @Test
  fun `exempts only test modules nested in an instrumentation module`() {
    val instrumentation = "dd-java-agent:instrumentation"
    addSubproject(instrumentation, """plugins { id("dd-trace-java.instrumentation-naming") }""")
    addSubproject("$instrumentation:lettuce:lettuce-5.0", "plugins { java }")
    addSubproject("$instrumentation:lettuce:lettuce-5.0:lettuce-6.0-test", "plugins { java }")
    // Its parent directory is a grouping directory, not an instrumentation module
    addSubproject("$instrumentation:other:lettuce-6.0-test", "plugins { java }")

    val task = ":$instrumentation:checkInstrumentationNaming"
    val build = run(task, expectFailure = true)
    assertThat(build.task(task)?.outcome).isEqualTo(FAILED)
    assertThat(build.output)
      .contains(File("other", "lettuce-6.0-test").path)
      .doesNotContain(File("lettuce/lettuce-5.0", "lettuce-6.0-test").path)
  }
}
