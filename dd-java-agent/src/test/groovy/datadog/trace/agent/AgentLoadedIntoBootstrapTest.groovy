package datadog.trace.agent

import datadog.trace.agent.test.IntegrationTestUtils
import datadog.trace.bootstrap.AgentBootstrap
import jvmbootstraptest.AgentLoadedChecker
import jvmbootstraptest.MyClassLoaderIsNotBootstrap
import spock.lang.Specification
import spock.lang.Timeout

@Timeout(30)
class AgentLoadedIntoBootstrapTest extends Specification {

  def "Agent loads in when separate jvm is launched"() {
    expect:
    IntegrationTestUtils.runOnSeparateJvm(AgentLoadedChecker.getName()
      , []
      , []
      , [:]
      , true) == 0
  }

  def "AgentBootstrap is loaded not from dd-java-agent.jar"() {
    setup:
    def mainClassName = MyClassLoaderIsNotBootstrap.getName()
    def pathToJar = IntegrationTestUtils.createJarWithClasses(mainClassName,
      MyClassLoaderIsNotBootstrap,
      AgentBootstrap).getPath()

    expect:
    IntegrationTestUtils.runOnSeparateJvm(mainClassName
      , []
      , []
      , [:]
      , pathToJar as String
      , true) == 0

    cleanup:
    new File(pathToJar).delete()
  }
}
