package datadog.smoketest

class OpenTelemetrySmokeTest extends AbstractSmokeTest {
  @Override
  ProcessBuilder createProcessBuilder() {
    def jarPath = System.getProperty("datadog.smoketest.shadowJar.path")
    def command = new ArrayList<String>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.add("-Ddd.integration.opentelemetry.experimental.enabled=true")
    command.addAll(["-jar", jarPath])

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  def 'receive trace'() {
    expect:
    waitForTraceCount(1)
  }
}
