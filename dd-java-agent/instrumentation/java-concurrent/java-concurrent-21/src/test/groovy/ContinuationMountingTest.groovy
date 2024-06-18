import datadog.trace.agent.test.AgentTestRunner

import java.util.concurrent.atomic.AtomicLong

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class ContinuationMountingTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.profiling.enabled", "true")
  }

  def "test continuation mounting intercepted"() {
    setup:
    def spanName = UUID.randomUUID().toString()
    def duration = new AtomicLong()

    when:
    runUnderTrace(spanName, {
      Thread.ofVirtual()
        .name("test thread")
        .start({
          long start = System.nanoTime()
          for (int i = 0; i < 3; i++) {
            Thread.sleep(20)
            long now = System.nanoTime()
            duration.addAndGet(now - start)
            start = now
          }
        }).join(5000)
    })

    then:
    TEST_PROFILING_CONTEXT_INTEGRATION.getActivationBalance(spanName) == 0
  }
}
