package datadog.gradle.plugin.testing

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/** Checks the live agent after the job's test invocation, including when all tests were cached. */
@DisableCachingByDefault(because = "Checks the current APM Test Agent state")
abstract class CheckTestAgentResults : DefaultTask() {
  @get:Input
  val agentUrl: Provider<String> = providers.testAgentUrl()

  @get:OutputDirectory
  abstract val reportDirectory: DirectoryProperty

  @get:Inject
  protected abstract val providers: ProviderFactory

  init {
    group = "verification"
    description = "Validates all trace check results from the running APM Test Agent."
    reportDirectory.convention(project.layout.buildDirectory.dir("reports/test-agent/$name"))
    val useTestAgent = providers.environmentVariable("CI_USE_TEST_AGENT").map { it == "true" }.orElse(false)
    onlyIf("APM Test Agent is enabled") { useTestAgent.get() }
    outputs.upToDateWhen { false }
  }

  @TaskAction
  fun checkResults() {
    TestAgentResultsChecker(agentUrl.get(), reportDirectory.get().asFile, logger::lifecycle).check()
  }
}
