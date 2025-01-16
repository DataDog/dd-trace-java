package datadog.smoketest

import spock.lang.Shared

abstract class AbstractModulesSmokeTest extends AbstractSmokeTest {
  static final String CONFIG_PREFIX = 'datadog.smoketest.jbossmodules.'

  @Shared
  String repoPath = System.getProperty(CONFIG_PREFIX + 'repoPath')

  @Override
  ProcessBuilder createProcessBuilder() {
    def frameworkJar = System.getProperty(CONFIG_PREFIX + getClass().simpleName - 'SmokeTest')

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.add('-Ddd.profiling.enabled=false')
    command.add('-jar')
    command.add(frameworkJar)
    command.add('-mp')
    command.add(repoPath)
    command.add('app')

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))

    return processBuilder
  }

  @Override
  boolean isErrorLog(String log) {
    super.isErrorLog(log) || log.contains("Cannot resolve type description") || log.contains("Instrumentation muzzled")
  }

  def "example application runs without errors"() {
    when:
    testedProcess.waitFor()

    then: 'MessageClient is transformed'
    testedProcess.exitValue() == 0
    processTestLogLines {
      it.contains("Transformed - instrumentation.target.class=datadog.smoketest.jbossmodules.client.MessageClient")
    }
  }

  @Override
  def logLevel() {
    return "debug"
  }
}
