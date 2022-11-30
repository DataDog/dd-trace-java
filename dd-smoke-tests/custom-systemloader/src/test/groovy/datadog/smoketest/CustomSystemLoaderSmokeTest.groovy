package datadog.smoketest

import spock.lang.Timeout

import java.util.concurrent.TimeUnit

class CustomSystemLoaderSmokeTest extends AbstractSmokeTest {
  private static final int TIMEOUT_SECS = 30

  @Override
  def logLevel() {
    "debug"
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    String appJar = System.getProperty("datadog.smoketest.systemloader.shadowJar.path")
    assert new File(appJar).isFile()

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.add("-Djava.system.class.loader=datadog.smoketest.systemloader.TestLoader")
    command.addAll((String[]) ["-jar", appJar])

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))

    return processBuilder
  }

  @Timeout(value = TIMEOUT_SECS, unit = TimeUnit.SECONDS)
  def "resource types loaded by custom system class-loader are transformed"() {
    when:
    testedProcess.waitFor()
    int loadedResources = 0
    int transformedResources = 0
    checkLog {
      if (it =~ /Loading sample.app.Resource[$]Test[1-3] from TestLoader/) {
        loadedResources++
      }
      if (it =~ /Transformed.*class=sample.app.Resource[$]Test[1-3].*classloader=datadog.smoketest.systemloader.TestLoader/) {
        transformedResources++
      }
    }
    then:
    testedProcess.exitValue() == 0
    loadedResources == 3
    transformedResources == 3
    !logHasErrors
  }
}
