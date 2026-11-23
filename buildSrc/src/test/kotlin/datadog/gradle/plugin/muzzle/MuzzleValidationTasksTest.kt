package datadog.gradle.plugin.muzzle

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

class MuzzleValidationTasksTest {
  @Test
  fun `registration is deferred and repeated provider queries reuse the tasks`() {
    val project = ProjectBuilder.builder().build()
    var attempts = 0
    val validationTasks = MuzzleValidationTasks(project.providers) {
      attempts++
      setOf(project.tasks.register("validation").get())
    }

    assertThat(attempts).isZero()
    assertThat(project.tasks.names).doesNotContain("validation")

    val first = validationTasks.tasks.get()
    val second = validationTasks.tasks.get()

    assertThat(attempts).isEqualTo(1)
    assertThat(first).containsExactly(project.tasks.named("validation").get())
    assertThat(second).isSameAs(first)
  }

  @Test
  fun `failed registration is not retried after registering a task`() {
    val project = ProjectBuilder.builder().build()
    val failure = IllegalStateException("Registration failed")
    var attempts = 0
    val validationTasks = MuzzleValidationTasks(project.providers) {
      attempts++
      project.tasks.register("partiallyRegistered")
      throw failure
    }

    repeat(2) {
      assertThatThrownBy { validationTasks.tasks.get() }.isSameAs(failure)
    }

    assertThat(attempts).isEqualTo(1)
    assertThat(project.tasks.names).contains("partiallyRegistered")
  }
}
