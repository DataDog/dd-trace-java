import datadog.trace.agent.test.AgentTestRunner
import spock.lang.Shared
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class MDBTest extends AgentTestRunner {

  @Shared
  def msg = new JmsMsg()

  def "Test MDB1"() {
    setup:
    def bean1 = new MDB1()

    when:
    runUnderTrace("parent") {
      bean1.onMessage(msg)
    }
    TEST_WRITER.waitForTraces(2) // I get 2 here, good

    then:
    // When I expect 0 I get 2.
    // when I expect 1 I get 2.
    // When I expect 2 I get 0.
    // When I expect 3, I get 2.
    assertTraces(2) {

    }
  }

  def "Test MDB2"() {
    setup:
    def bean2 = new MDB2()

    when:
    runUnderTrace("parent") {
      bean2.onMessage(msg)
    }
    TEST_WRITER.waitForTraces(2) // I get 2 here, good

    then:
    // same problem here as above.  I get 2 except when I assertTraces(2)
    assertTraces(2) {

    }
  }
}

