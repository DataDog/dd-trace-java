package datadog.smoketest

import static java.util.concurrent.TimeUnit.SECONDS

class MultiAgentWithNoProtectionDomainSystemLoaderSmokeTest extends AbstractSmokeTest {
  private static final int TIMEOUT_SECS = 30

  @Override
  def logLevel() {
    "debug"
  }

  @Override
  String javaHome() {
    return System.getProperty("datadog.smoketest.java.home")
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    String appJar = System.getProperty("datadog.smoketest.jar.path")
    assert new File(appJar).isFile()
    String anotherAgentJar = System.getProperty("datadog.smoketest.another.agent.path")
    assert new File(anotherAgentJar).isFile()

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.add("-Djava.system.class.loader=datadog.smoketest.classloader.NoProtectionDomainClassLoader")
    command.add("-javaagent:" + anotherAgentJar)
    command.addAll((String[]) ["-jar", appJar])

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))

    return processBuilder
  }

  def "no failure on bootstrap when multiple agent and no ProtectionDomain system class-loader"() {
    when:
    testedProcess.waitFor(TIMEOUT_SECS, SECONDS)
    boolean codeSourceNotAvailable = false
    boolean multipleJavaagent = false
    boolean useClassLoaderResource = false
    checkLogPostExit {
      if (it =~ /Could not get bootstrap jar from code source, using -javaagent arg/) {
        codeSourceNotAvailable = true
      }
      if (it =~ /Could not get bootstrap jar from -javaagent arg: multiple javaagents specified/) {
        multipleJavaagent = true
      }
      if (it =~ /Could not get agent jar from -javaagent arg, using ClassLoader#getResource/) {
        useClassLoaderResource = true
      }
    }

    then:
    testedProcess.exitValue() == 0
    codeSourceNotAvailable
    multipleJavaagent
    useClassLoaderResource
    !logHasErrors
  }
}
