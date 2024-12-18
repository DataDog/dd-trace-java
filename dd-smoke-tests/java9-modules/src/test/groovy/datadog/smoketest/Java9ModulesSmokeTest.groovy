package datadog.smoketest

import static java.util.concurrent.TimeUnit.SECONDS

class Java9ModulesSmokeTest extends AbstractSmokeTest {
  // Estimate for the amount of time instrumentation plus some extra
  private static final int TIMEOUT_SECS = 30

  @Override
  ProcessBuilder createProcessBuilder() {
    String imageDir = System.getProperty("datadog.smoketest.module.image")

    assert imageDir != null

    List<String> command = new ArrayList<>()
    command.add(imageDir + "/bin/java")
    command.addAll(defaultJavaProperties)
    // temporarily turn off DDProf due to PROF-11066
    command.removeAll { it.startsWith("-Ddd.profiling.ddprof.enabled") }
    command.add("-Ddd.profiling.ddprof.enabled=false")
    command.addAll((String[]) ["-m", "datadog.smoketest.moduleapp/datadog.smoketest.moduleapp.ModuleApplication"])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  @Override
  boolean isErrorLog(String log) {
    // XXX: This test will make the tracer to fail at bootstrap:
    //      Caused by: java.lang.NoClassDefFoundError: java/lang/management/ManagementFactory
    //        at datadog.trace.api.Platform$GC.current(Platform.java:32)
    if (log.contains('ERROR datadog.trace.bootstrap.AgentBootstrap')) {
      return false
    }
    return super.isErrorLog(log)
  }

  def "Module application runs correctly"() {
    expect:
    assert testedProcess.waitFor(TIMEOUT_SECS, SECONDS)
    assert testedProcess.exitValue() == 0
  }
}
