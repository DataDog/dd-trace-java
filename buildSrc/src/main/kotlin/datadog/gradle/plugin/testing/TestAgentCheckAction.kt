package datadog.gradle.plugin.testing

import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

fun Test.configureTestAgentChecks(checkScript: File) {
  // Track inherited values for the configuration cache; fingerprint task overrides below.
  project.providers.environmentVariable("CI_USE_TEST_AGENT").orNull
  project.providers.environmentVariable("CI_AGENT_HOST").orNull
  val configuration = project.providers.provider {
    environment.filterKeys {
      it == "CI_USE_TEST_AGENT" || it == "CI_AGENT_HOST"
    }.mapValues { it.value.toString() }
  }
  inputs.property("ciTestAgentConfiguration", configuration)
  inputs.files(configuration.map {
    if (it["CI_USE_TEST_AGENT"] == "true") listOf(checkScript) else emptyList()
  }).withPropertyName("ciTestAgentCheckScript").withPathSensitivity(PathSensitivity.NONE)

  // A failing trace check must fail the Test task before its results enter the cache.
  doLast("checkTestAgent", project.objects.newInstance(TestAgentCheckAction::class.java, checkScript))
}

abstract class TestAgentCheckAction @Inject constructor(
  private val checkScript: File
) : Action<Task> {
  @get:Inject
  abstract val execOperations: ExecOperations

  override fun execute(task: Task) {
    val test = task as Test
    if (test.environment["CI_USE_TEST_AGENT"]?.toString() != "true") {
      return
    }
    execOperations.exec {
      commandLine("bash", checkScript.absolutePath)
      // The script writes response files; parallel test tasks need separate directories.
      workingDir(task.temporaryDir)
      setEnvironment(test.environment)
    }
  }
}
