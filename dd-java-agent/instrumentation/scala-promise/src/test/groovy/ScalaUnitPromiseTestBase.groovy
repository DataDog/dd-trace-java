import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import scala.concurrent.ExecutionContext

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static org.junit.Assume.assumeTrue

abstract class ScalaUnitPromiseTestBase extends AgentTestRunner {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.trace.integration.scala_promise_completion_priority.enabled", "true")
  }

  private volatile PromiseUtils pu = null
  private volatile AgentSpan is = null

  // This lazy initialization is needed so that we install the instrumentations
  // before we actually use the scala Future object in the PromiseUtils constructor
  private void checkAndInit() {
    if (pu == null) {
      synchronized (this) {
        if (pu == null) {
          (pu, is) = runUnderTrace("initialization") {
            [new PromiseUtils(getExecutionContext()), activeSpan()]
          }
          assertTraces(1) {
            trace(1) {
              basicSpan(it, "initialization")
            }
          }
          // Clear out the test writer
          TEST_WRITER.start()
        }
      }
    }
  }

  protected PromiseUtils getPromiseUtils() {
    checkAndInit()
    return pu
  }

  protected AgentSpan getInitSpan() {
    checkAndInit()
    return is
  }

  abstract protected ExecutionContext getExecutionContext()

  protected boolean hasUnitPromise() {
    return true
  }
}

abstract class ScalaUnitPromiseTestNoPropagation extends ScalaUnitPromiseTestBase {
  def "make sure that we don't propagate unit context"() {
    setup:
    assumeTrue(hasUnitPromise())

    when:
    def f1 = promiseUtils.apply({
      runUnderTrace("f1") {
        return "f1"
      }
    })
    def f2 = promiseUtils.apply({
      runUnderTrace("f2") {
        return "f2"
      }
    })

    then:
    promiseUtils.await(f1) == "f1"
    promiseUtils.await(f2) == "f2"
    assertTraces(2) {
      trace(1) {
        basicSpan(it, "f1")
      }
      trace(1) {
        basicSpan(it, "f2")
      }
    }
  }
}

abstract class ScalaUnitPromiseTestPropagation extends ScalaUnitPromiseTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.trace.integration.scala_future_object.enabled", "false")
  }

  def "make sure that that the unit context instrumentation works"() {
    setup:
    assumeTrue(hasUnitPromise())

    when:
    def f1 = promiseUtils.apply({
      runUnderTrace("f1") {
        return "f1"
      }
    })
    def f2 = promiseUtils.apply({
      runUnderTrace("f2") {
        return "f2"
      }
    })

    then:
    promiseUtils.await(f1) == "f1"
    promiseUtils.await(f2) == "f2"
    assertTraces(1) {
      trace(2, true) {
        basicSpan(it, "f1", initSpan)
        basicSpan(it, "f2", initSpan)
      }
    }
  }
}
