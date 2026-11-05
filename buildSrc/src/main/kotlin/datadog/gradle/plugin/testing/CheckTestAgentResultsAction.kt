package datadog.gradle.plugin.testing

import org.gradle.api.Action
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.newInstance
import org.gradle.process.ExecOperations
import javax.inject.Inject

fun Test.configureTestAgentPostChecks() {
  // Bail out if APM TestAgent is not used
  if (project.providers.environmentVariable("CI_USE_TEST_AGENT").orNull != "true") {
    return
  }

  val checkScript = project.rootProject.layout.projectDirectory.file("tooling/check_test_agent_results.sh")
  val reportDirectory = project.layout.buildDirectory.dir("reports/test-agent/$name")

  val testEnvironment = testAgentEnvironment()
  val configuration = testAgentConfiguration(this.project.providers, testEnvironment)

  // Register additional inputs related to APM TestAgent
  inputs.property("ciTestAgentConfiguration", configuration)
  inputs.file(checkScript)
    .withPropertyName("ciTestAgentCheckScript")
    .withPathSensitivity(PathSensitivity.NONE)

  outputs.files(reportDirectory.map { directory ->
    listOf(
      directory.file("summary_response.txt"),
      directory.file("response.txt")
    )
  }).withPropertyName("ciTestAgentPostCheckReports")

  // A failing trace check must fail the Test task before its results enter the cache.
  doLast(
    "checkTestAgent",
    project.objects.newInstance<CheckTestAgentResultsAction>(
      checkScript,
      reportDirectory
    )
  )
}

private val testAgentBooleanVariables = listOf(
  "CI_TEST_AGENT_SUPPRESS_TRACE_PARSE_ERRORS",
  "CI_TEST_AGENT_POOL_TRACE_CHECK_FAILURES",
  "CI_TEST_AGENT_DISABLE_ERROR_RESPONSES",
)
private val testAgentVariables = listOf(
  "CI_TEST_AGENT_IMAGE",
  "CI_TEST_AGENT_ENABLED_CHECKS"
) + testAgentBooleanVariables

private fun Test.testAgentEnvironment(): Provider<Map<String, String>> {
  return project.providers.provider {
    environment.filterKeys {
      it == "CI_USE_TEST_AGENT" || it == "CI_AGENT_HOST"
    }.mapValues { it.value.toString() }
  }
}

private fun testAgentConfiguration(
  factory: ProviderFactory,
  testEnvironment: Provider<Map<String, String>>
): Provider<Map<String, String>> {
  // Capture the host now so a change refreshes Test.environment in the configuration cache.
  val sidecarHost = factory.environmentVariable("CI_AGENT_HOST").getOrElse("")

  // These values configure the already running sidecar; Test.environment cannot override them.
  val sidecarEnvironment = factory.environmentVariablesPrefixedBy("CI_TEST_AGENT_")
    .map { settings ->
      settings.filterKeys { it in testAgentVariables } + ("CI_AGENT_HOST" to sidecarHost)
    }

  return testEnvironment.zip(sidecarEnvironment) { test, sidecar ->
    // Validate configuration
    val missing = testAgentVariables.filterNot(sidecar::containsKey)
    if (missing.isNotEmpty()) {
      throw InvalidUserDataException("Missing APM Test Agent configuration: ${missing.joinToString()}")
    }
    if (!sidecar.getValue("CI_TEST_AGENT_IMAGE").matches(Regex(".+@sha256:[0-9a-f]{64}"))) {
      throw InvalidUserDataException("CI_TEST_AGENT_IMAGE must pin the running sidecar with @sha256:<digest>")
    }
    testAgentBooleanVariables.forEach { name ->
      // Test Agent v1.64.1 treats every non-empty string, including "false", as true.
      if (sidecar.getValue(name) !in listOf("", "true")) {
        throw InvalidUserDataException("$name must be 'true' or empty for Test Agent v1.64.1")
      }
    }
    if (sidecar.getValue("CI_AGENT_HOST").isEmpty() || test["CI_AGENT_HOST"] != sidecar["CI_AGENT_HOST"]) {
      throw InvalidUserDataException("CI_AGENT_HOST must match the job's fingerprinted APM Test Agent sidecar")
    }

    test + sidecar
  }
}

abstract class CheckTestAgentResultsAction @Inject constructor(
  private val checkScript: RegularFile,
  private val reportDirectory: Provider<Directory>,
) : Action<Task> {
  @get:Inject
  abstract val execOperations: ExecOperations

  override fun execute(task: Task) {
    val test = task as Test
    val directory = reportDirectory.get().asFile
    // Replace reports from previous executions or cache restores before checking the live agent.
    check(directory.deleteRecursively() && directory.mkdirs()) { "Cannot prepare Test Agent report directory: $directory" }
    execOperations.exec {
      commandLine("bash", checkScript.asFile.absolutePath)
      workingDir(directory)
      // Registration already established that the invocation enables these checks.
      environment = test.environment + ("CI_USE_TEST_AGENT" to "true")
    }
  }
}
