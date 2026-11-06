package datadog.gradle.plugin.testing

import okhttp3.HttpUrl
import org.gradle.api.Action
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.newInstance
import javax.inject.Inject

fun Test.configureTestAgentPostChecks() {
  // Bail out if APM TestAgent is not used
  if (project.providers.environmentVariable("CI_USE_TEST_AGENT").orNull != "true") {
    return
  }

  val reportDirectory = project.layout.buildDirectory.dir("reports/test-agent/$name")

  val testEnvironment = testAgentEnvironment()
  val configuration = testAgentConfiguration(this.project.providers, testEnvironment)
  val agentUrl = project.providers.testAgentUrl()

  // Register additional inputs related to APM TestAgent
  inputs.property("ciTestAgentConfiguration", configuration)
  inputs.property("ciTestAgentUrl", agentUrl)

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
      reportDirectory,
      agentUrl
    )
  )
}

/** Resolves the same job endpoint for per-Test checks and the final live check. */
internal fun ProviderFactory.testAgentUrl(): Provider<String> =
  environmentVariable("CI_AGENT_HOST").zip(environmentVariable("CI_TEST_AGENT_PORT").orElse("8126")) { host, value ->
    val port = value.toIntOrNull()?.takeIf { it in 1..65535 }
      ?: throw InvalidUserDataException("CI_TEST_AGENT_PORT must be an integer between 1 and 65535")
    HttpUrl.Builder().scheme("http").host(host).port(port).build().toString()
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
      it == "CI_USE_TEST_AGENT" || it == "CI_AGENT_HOST" || it == "CI_TEST_AGENT_PORT"
    }.mapValues { it.value.toString() }
  }
}

private fun testAgentConfiguration(
  factory: ProviderFactory,
  testEnvironment: Provider<Map<String, String>>
): Provider<Map<String, String>> {
  // Capture the endpoint now so a change refreshes Test.environment in the configuration cache.
  val sidecarHost = factory.environmentVariable("CI_AGENT_HOST").getOrElse("")
  val sidecarPort = factory.environmentVariable("CI_TEST_AGENT_PORT").getOrElse("8126")

  // These values configure the already running sidecar; Test.environment cannot override them.
  val sidecarEnvironment = factory.environmentVariablesPrefixedBy("CI_TEST_AGENT_")
    .map { settings ->
      settings.filterKeys { it in testAgentVariables } + mapOf(
        "CI_AGENT_HOST" to sidecarHost,
        "CI_TEST_AGENT_PORT" to sidecarPort,
      )
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
    if ((test["CI_TEST_AGENT_PORT"] ?: "8126") != sidecar["CI_TEST_AGENT_PORT"]) {
      throw InvalidUserDataException("CI_TEST_AGENT_PORT must match the job's fingerprinted APM Test Agent sidecar")
    }

    test + sidecar
  }
}

abstract class CheckTestAgentResultsAction @Inject constructor(
  private val reportDirectory: Provider<Directory>,
  private val agentUrl: Provider<String>,
) : Action<Task> {
  override fun execute(task: Task) {
    TestAgentResultsChecker(
      agentUrl.get(),
      reportDirectory.get().asFile,
      task.logger::lifecycle,
    ).check()
  }
}
