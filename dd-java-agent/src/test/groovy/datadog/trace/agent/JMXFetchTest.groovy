package datadog.trace.agent

import datadog.trace.agent.test.IntegrationTestUtils
import jvmbootstraptest.AgentLoadedChecker
import jvmbootstraptest.JmxStartedChecker
import spock.lang.Specification
import spock.lang.Timeout

@Timeout(30)
class JMXFetchTest extends Specification {
  DatagramSocket jmxStatsSocket

  def setup() {
    jmxStatsSocket = new DatagramSocket(0)
    jmxStatsSocket.setSoTimeout(30 * 1000)
  }

  def cleanup() {
    jmxStatsSocket.close()
  }

  def "test jmxfetch"() {
    when:
    // verify that JMX starts and reports metrics through the given socket.
    def returnCode = IntegrationTestUtils.runOnSeparateJvm(JmxStartedChecker.getName()
      , [
        "-Ddd.jmxfetch.enabled=true",
        "-Ddd.jmxfetch.start-delay=0",
        "-Ddd.jmxfetch.statsd.port=${jmxStatsSocket.localPort}",
        "-Ddd.writer.type=DDAgentWriter"] as String[]
      , "" as String[]
      , [:]
      , true)

    then:
    returnCode == 0

    when:
    byte[] buf = new byte[1500]
    DatagramPacket packet = new DatagramPacket(buf, buf.length)
    jmxStatsSocket.receive(packet)
    String received = new String(packet.getData(), 0, packet.getLength())
    def tags = (received =~ /\|#(.*)/)[0][1].tokenize(',')

    then:
    tags.contains("service:${JmxStartedChecker.getName()}" as String)
  }

  def "Agent loads when JmxFetch is misconfigured"() {
    when:
    // verify the agent starts up correctly with a bogus address.
    def returnCode = IntegrationTestUtils.runOnSeparateJvm(AgentLoadedChecker.getName()
      , [
        "-Ddd.jmxfetch.enabled=true",
        "-Ddd.jmxfetch.start-delay=0",
        "-Ddd.jmxfetch.statsd.host=example.local",
        "-Ddd.writer.type=DDAgentWriter"] as String[]
      , "" as String[]
      , [:]
      , true)

    then:
    returnCode == 0
  }

  def "test jmxfetch config"() {
    setup:
    def configSettings = names.collect {
      "-Ddd.jmxfetch.${it}.enabled=${enable}"
    }
    def testOutput = new ByteArrayOutputStream()

    when:
    def returnCode = IntegrationTestUtils.runOnSeparateJvm(JmxStartedChecker.getName()
      , [
        "-Ddd.jmxfetch.enabled=true",
        "-Ddd.jmxfetch.start-delay=0",
        "-Ddd.jmxfetch.statsd.port=${jmxStatsSocket.localPort}",
        "-Ddd.trace.debug=true",
        "-Ddd.writer.type=DDAgentWriter"]
      + configSettings as String[]
      , "" as String[]
      , [:]
      , new PrintStream(testOutput))

    def actualConfig = []
    new ByteArrayInputStream((testOutput.toByteArray())).eachLine {
      System.out.println(it)
      def match = (it =~ 'Reading metric config resource (.*)')
      if (match) {
        actualConfig += match[0][1]
      }
    }

    then:
    returnCode == 0
    actualConfig as Set == expectedConfig as Set

    where:
    // spotless:off
    names               | enable | expectedConfig
    []                  | true   | []
    ["tomcat"]          | false  | []
    ["tomcat"]          | true   | ["datadog/trace/agent/jmxfetch/metricconfigs/tomcat.yaml"]
    ["kafka"]           | true   | ["datadog/trace/agent/jmxfetch/metricconfigs/kafka.yaml"]
    ["tomcat", "kafka"] | true   | ["datadog/trace/agent/jmxfetch/metricconfigs/tomcat.yaml", "datadog/trace/agent/jmxfetch/metricconfigs/kafka.yaml"]
    ["tomcat", "kafka"] | false  | []
    ["invalid"]         | true   | []
    // spotless:on
  }
}
