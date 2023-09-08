package datadog.trace.bootstrap

import spock.lang.Specification

class AgentBootstrapTest extends Specification {
  def 'parse java.version strings'() {
    when:
    def major = AgentBootstrap.parseJavaMajorVersion(version)

    then:
    major == expected

    where:
    version      | expected
    null         | 0
    ''           | 0
    'a.0.0'      | 0
    '0.a.0'      | 0
    '0.0.a'      | 0
    '1.a.0_0'    | 1
    '1.8.a_0'    | 8
    '1.8.0_a'    | 8
    '1.7'        | 7
    '1.7.0'      | 7
    '1.7.0_221'  | 7
    '1.8'        | 8
    '1.8.0'      | 8
    '1.8.0_212'  | 8
    '1.8.0_292'  | 8
    '9-ea'       | 9
    '9.0.4'      | 9
    '9.1.2'      | 9
    '10.0.2'     | 10
    '11'         | 11
    '11a'        | 11
    '11.0.6'     | 11
    '11.0.11'    | 11
    '12.0.2'     | 12
    '13.0.2'     | 13
    '14'         | 14
    '14.0.2'     | 14
    '15'         | 15
    '15.0.2'     | 15
    '16.0.1'     | 16
    '11.0.9.1+1' | 11
    '11.0.6+10'  | 11
  }

  def 'log warning message when java version is less than 8'() {
    setup:
    def baos = new ByteArrayOutputStream()
    def logStream = new PrintStream(baos)

    when:
    def isLowerThan8 = AgentBootstrap.lessThanJava8(version, logStream)
    logStream.flush()
    def logLines = Arrays.asList(baos.toString().split('\n'))
    // If the list only contains a single String and that is the empty String, then the set is empty
    if (logLines.size() == 1 && logLines.contains('')) {
      logLines = []
    }

    then:
    isLowerThan8 == expectedLower
    if (!expectedLower) {
      assert logLines.isEmpty()
    } else {
      assert logLines.size() == 2
      assert logLines == [
        "Warning: Version ${AgentJar.getAgentVersion()} of dd-java-agent is not compatible with Java ${version} and will not be installed.",
        'Please upgrade your Java version to 8+ or use the 0.x version of dd-java-agent in your build tool or download it from https://dtdg.co/java-tracer-v0'
      ]
    }

    where:
    version      | expectedLower
    null         | true
    ''           | true
    'a.0.0'      | true
    '0.a.0'      | true
    '0.0.a'      | true
    '1.a.0_0'    | true
    '1.8.a_0'    | false
    '1.8.0_a'    | false
    '1.7'        | true
    '1.7.0'      | true
    '1.7.0_221'  | true
    '1.8'        | false
    '9.0.4'      | false
    '10.0.2'     | false
    '11a'        | false
    '15'         | false
    '11.0.9.1+1' | false

  }

  def 'return true when first exception in the cause chain is the specified exception'() {
    setup:
    def ex = new IOException()

    when:
    def causeChainContainsException = AgentBootstrap.exceptionCauseChainContains(ex, "java.io.IOException")

    then:
    causeChainContainsException
  }

  def 'return false when exception cause chain does not contain specified exception'() {
    setup:
    def ex = new NullPointerException()

    when:
    def causeChainContainsException = AgentBootstrap.exceptionCauseChainContains(ex, "java.io.IOException")

    then:
    !causeChainContainsException
  }

  def 'return true when exception cause chain contains specified exception as a cause'() {
    setup:
    def ex = new Exception(new IOException())

    when:
    def causeChainContainsException = AgentBootstrap.exceptionCauseChainContains(ex, "java.io.IOException")

    then:
    causeChainContainsException
  }

  def 'return false when exception cause chain has a cycle'() {
    setup:
    def ex = Mock(Exception)
    ex.getCause() >> ex

    when:
    def causeChainContainsException = AgentBootstrap.exceptionCauseChainContains(ex, "java.io.IOException")

    then:
    !causeChainContainsException
  }
}
