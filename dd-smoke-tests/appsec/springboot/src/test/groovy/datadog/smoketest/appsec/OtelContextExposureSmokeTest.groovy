package datadog.smoketest.appsec

import spock.util.concurrent.PollingConditions

/**
 * Verifies that the OTel thread/process context integration ({@code
 * datadog.trace.bootstrap.Agent#createProfilingContextIntegration}) is driven purely by AppSec
 * activation, independently of profiling: {@code defaultAppSecProperties} always sets {@code
 * -Ddd.profiling.enabled=false}, and this module runs twice in CI (the {@code test} and {@code
 * testRuntimeActivation} Gradle tasks), once with AppSec fully enabled and once with it inactive.
 */
class OtelContextExposureSmokeTest extends AbstractAppSecServerSmokeTest {

  private static final String PROCESS_CONTEXT_LOG_LINE = 'Registering process context for OTel profiler'

  @Override
  def logLevel() {
    'DEBUG'
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.appsec.springboot.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll(defaultAppSecProperties)
    command.addAll((String[]) ["-jar", springBootShadowJar, "--server.port=${httpPort}"])

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  void 'OTel process context registration follows AppSec activation, not profiling'() {
    given:
    boolean appSecFullyEnabled = System.getProperty('smoke_test.appsec.enabled') != 'inactive'
    PollingConditions conditions = new PollingConditions(timeout: 30, initialDelay: 1, factor: 1.25)

    expect:
    if (appSecFullyEnabled) {
      conditions.eventually {
        assert new File(logFilePath).text.contains(PROCESS_CONTEXT_LOG_LINE)
      }
    } else {
      // Give the agent the same startup time as the positive case before asserting absence,
      // so a slow-starting agent can't produce a false negative.
      conditions.eventually {
        assert new File(logFilePath).length() > 0
      }
      sleep(5_000)
      assert !new File(logFilePath).text.contains(PROCESS_CONTEXT_LOG_LINE)
    }
  }
}
