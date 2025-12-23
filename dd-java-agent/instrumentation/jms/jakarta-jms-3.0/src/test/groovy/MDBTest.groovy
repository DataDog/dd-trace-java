import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.asserts.ListWriterAssert
import spock.lang.Shared

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class MDBTest extends InstrumentationSpecification {

  @Shared
  def msg = new MDBJmsMsg()

  def "Test an incomplete MDB that should not get traced here"() {
    setup:
    def beanBad = new MDBBad()

    when:
    runUnderTrace("parent") {
      beanBad.onMessage(msg)
    }

    then:
    assertTraces(1) {
      workerTrace(it)
    }
  }

  def "Test MDB1"() {
    setup:
    def bean1 = new MDB1()

    when:
    runUnderTrace("parent") {
      bean1.onMessage(msg)
    }

    then:
    assertTraces(2, SORT_TRACES_BY_START) {
      workerTrace(it)
      jmsTrace(it)
    }
  }

  def "Test MDB2"() {
    setup:
    def bean2 = new MDB2()

    when:
    runUnderTrace("parent") {
      bean2.onMessage(msg)
    }

    then:
    assertTraces(2, SORT_TRACES_BY_START) {
      workerTrace(it)
      jmsTrace(it)
    }
  }

  def workerTrace(ListWriterAssert writer) {
    writer.trace(1) {
      span(0) {
        serviceName "worker.org.gradle.process.internal.worker.GradleWorkerMain"
      }
    }
  }

  def jmsTrace(ListWriterAssert writer) {
    writer.trace(1) {
      span(0) {
        spanType "queue"
        serviceName "jms"
        operationName "jms.consume"
        resourceName "Consumed from Temporary Queue"
        measured true
      }
    }
  }
}
