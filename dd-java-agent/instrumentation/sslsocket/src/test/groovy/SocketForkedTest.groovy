import datadog.trace.agent.test.InstrumentationSpecification

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class SocketForkedTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.profiling.enabled", "true")
  }

  def "wallclock sampling disabled during Socket.connect"() {
    when:
    runUnderTrace("parent") {
      try {
        // calls connect internally, and will fail
        new Socket("localhost", 0)
      } catch(Throwable expected) {
      }
    }
    then:
    // expect one pair of attach and detach for the span, another for Socket.connect
    TEST_PROFILING_CONTEXT_INTEGRATION.detachments.get() == 2
    TEST_PROFILING_CONTEXT_INTEGRATION.attachments.get() == 2
  }
}
