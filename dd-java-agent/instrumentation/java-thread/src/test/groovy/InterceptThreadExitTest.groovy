import datadog.trace.agent.test.AgentTestRunner

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class InterceptThreadExitTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.profiling.enabled", "true")
  }

  def "test notification of thread exit"() {
    setup:
    def executor = Executors.newSingleThreadExecutor()

    when:
    runUnderTrace("parent") {
      executor.execute({})
    }
    executor.shutdown()
    executor.awaitTermination(10, TimeUnit.SECONDS)
    // wait a second to make sure the update happens on some JDKs
    if (TEST_CONTEXT_THREAD_LISTENER.attachments.get() > 0 && TEST_CONTEXT_THREAD_LISTENER.detachments.get() == 0) {
      Thread.sleep(1000)
    }

    then:
    TEST_CONTEXT_THREAD_LISTENER.attachments.get() >= 1
    TEST_CONTEXT_THREAD_LISTENER.detachments.get() == 1
    TEST_CONTEXT_THREAD_LISTENER.attachments.get() >= TEST_CONTEXT_THREAD_LISTENER.detachments.get()
  }
}
