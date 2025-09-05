package datadog.trace.instrumentation.kotlin.coroutines

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.core.DDSpan
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadPoolDispatcherKt
import spock.lang.Shared

abstract class AbstractKotlinCoroutineInstrumentationTest<T extends CoreKotlinCoroutineTests> extends InstrumentationSpecification {

  protected abstract T getCoreKotlinCoroutineTestsInstance(CoroutineDispatcher dispatcher)

  @Shared
  static dispatchersToTest = [
    ['default', Dispatchers.Default],
    ['io', Dispatchers.IO],
    ['unconfined', Dispatchers.Unconfined],
    ['fixed thread pool', ThreadPoolDispatcherKt.newFixedThreadPoolContext(2, "Fixed-Thread-Pool")],
    ['single thread pool', ThreadPoolDispatcherKt.newSingleThreadContext("Single-Thread")],
  ]

  def "kotlin cancellation prevents trace #dispatcherName"() {
    setup:
    CoreKotlinCoroutineTests kotlinTest = getCoreKotlinCoroutineTestsInstance(dispatcher)
    int expectedNumberOfSpans = kotlinTest.tracePreventedByCancellation()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    trace.size() == expectedNumberOfSpans
    trace[0].resourceName.toString() == "KotlinCoroutineTests.tracePreventedByCancellation"
    findSpan(trace, "preLaunch").context().getParentId() == trace[0].context().getSpanId()
    findSpan(trace, "postLaunch") == null

    where:
    [dispatcherName, dispatcher] << dispatchersToTest
  }

  def "kotlin propagates across nested jobs #dispatcherName"() {
    setup:
    CoreKotlinCoroutineTests kotlinTest = getCoreKotlinCoroutineTestsInstance(dispatcher)
    int expectedNumberOfSpans = kotlinTest.tracedAcrossThreadsWithNested()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    trace.size() == expectedNumberOfSpans
    trace[0].resourceName.toString() == "KotlinCoroutineTests.tracedAcrossThreadsWithNested"
    findSpan(trace, "nested").context().getParentId() == trace[0].context().getSpanId()

    where:
    [dispatcherName, dispatcher] << dispatchersToTest
  }

  def "kotlin either deferred completion #dispatcherName"() {
    setup:
    CoreKotlinCoroutineTests kotlinTest = getCoreKotlinCoroutineTestsInstance(dispatcher)
    int expectedNumberOfSpans = kotlinTest.traceWithDeferred()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == expectedNumberOfSpans
    trace[0].resourceName.toString() == "KotlinCoroutineTests.traceWithDeferred"
    findSpan(trace, "keptPromise").context().getParentId() == trace[0].context().getSpanId()
    findSpan(trace, "keptPromise2").context().getParentId() == trace[0].context().getSpanId()
    findSpan(trace, "brokenPromise").context().getParentId() == trace[0].context().getSpanId()

    where:
    [dispatcherName, dispatcher] << dispatchersToTest
  }

  def "kotlin first completed deferred #dispatcherName"() {
    setup:
    CoreKotlinCoroutineTests kotlinTest = getCoreKotlinCoroutineTestsInstance(dispatcher)
    int expectedNumberOfSpans = kotlinTest.tracedWithDeferredFirstCompletions()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == expectedNumberOfSpans
    findSpan(trace, "timeout1").context().getParentId() == trace[0].context().getSpanId()
    findSpan(trace, "timeout2").context().getParentId() == trace[0].context().getSpanId()
    findSpan(trace, "timeout3").context().getParentId() == trace[0].context().getSpanId()

    where:
    [dispatcherName, dispatcher] << dispatchersToTest
  }

  def "coroutine suspension should not mess up traces"() {
    setup:
    CoreKotlinCoroutineTests kotlinTest = getCoreKotlinCoroutineTestsInstance(
      ThreadPoolDispatcherKt.newSingleThreadContext("Single-Thread")
      )
    int expectedNumberOfSpans = kotlinTest.tracedWithSuspendingCoroutines()

    expect:
    assertTraces(1) {
      trace(expectedNumberOfSpans, true) {
        span(4) {
          operationName "trace.annotation"
          parent()
        }
        def topLevelSpan = span(3)
        span(3) {
          operationName "top-level"
          childOf span(4)
        }
        span(2) {
          operationName "synchronous-child"
          childOf topLevelSpan
        }
        span(1) {
          operationName "second-span"
          childOf topLevelSpan
        }
        span(0) {
          operationName "first-span"
          childOf topLevelSpan
        }
      }
    }
  }

  def "lazily started coroutines should inherit the span active at start time #dispatcherName"() {
    setup:
    CoreKotlinCoroutineTests kotlinTest = getCoreKotlinCoroutineTestsInstance(dispatcher)
    int expectedNumberOfSpans = kotlinTest.tracedWithLazyStarting()

    expect:
    assertTraces(1) {
      trace(expectedNumberOfSpans, true) {
        span(4) {
          operationName "trace.annotation"
          parent()
        }
        span(3) {
          operationName "top-level"
          childOf span(4)
        }
        span(1) {
          operationName "lazy-start"
          childOf span(4)
        }
        span(2) {
          operationName "second-span"
          childOf span(1)
        }
        span(0) {
          operationName "first-span"
          childOf span(4)
        }
      }
    }

    where:
    [dispatcherName, dispatcher] << dispatchersToTest
  }

  def "coroutine instrumentation should work without an enclosing trace span #dispatcherName"() {
    setup:
    CoreKotlinCoroutineTests kotlinTest = getCoreKotlinCoroutineTestsInstance(dispatcher)
    int expectedNumberOfSpans = kotlinTest.withNoTraceParentSpan(false, false)

    expect:
    assertTopLevelSpanWithTwoSubSpans(expectedNumberOfSpans)

    where:
    [dispatcherName, dispatcher] << dispatchersToTest
  }

  def "coroutine instrumentation should work when started lazily without an enclosing trace span #dispatcherName"() {
    setup:
    CoreKotlinCoroutineTests kotlinTest = getCoreKotlinCoroutineTestsInstance(dispatcher)
    kotlinTest.withNoTraceParentSpan(true, false)

    expect:
    assertTraces(3, SORT_TRACES_BY_NAMES) {
      trace(1, true) {
        span(0) {
          operationName "first-span"
          parent()
        }
      }
      trace(1, true) {
        span(0) {
          operationName "second-span"
          parent()
        }
      }
      trace(1, true) {
        span(0) {
          operationName "top-level"
          parent()
        }
      }
    }

    where:
    [dispatcherName, dispatcher] << dispatchersToTest
  }

  def "coroutine instrumentation should work without an enclosing trace span and throwing exceptions #dispatcherName"() {
    setup:
    CoreKotlinCoroutineTests kotlinTest = getCoreKotlinCoroutineTestsInstance(dispatcher)
    int expectedNumberOfSpans = kotlinTest.withNoTraceParentSpan(false, true)

    expect:
    assertTopLevelSpanWithTwoSubSpans(expectedNumberOfSpans, true)

    where:
    [dispatcherName, dispatcher] << dispatchersToTest
  }

  def "coroutine instrumentation should work when started lazily without an enclosing trace span and throwing exceptions #dispatcherName"() {
    setup:
    CoreKotlinCoroutineTests kotlinTest = getCoreKotlinCoroutineTestsInstance(dispatcher)
    int expectedTraces = kotlinTest.withNoTraceParentSpan(true, true)

    expect:
    assertTraces(expectedTraces, SORT_TRACES_BY_NAMES) {
      if (expectedTraces == 3) {
        trace(1, true) {
          span(0) {
            operationName "first-span"
            parent()
          }
        }
        trace(1, true) {
          span(0) {
            operationName "second-span"
            parent()
          }
        }
      } else {
        trace(1, true) {
          span(0) {
            span(0) {
              parent()
              // either of the jobs could have been started first, thrown, and canceled the other job
              operationName { name -> (name == 'first-span' || name == 'second-span') }
            }
          }
        }
      }
      trace(1, true) {
        span(0) {
          operationName "top-level"
          parent()
        }
      }
    }

    where:
    [dispatcherName, dispatcher] << dispatchersToTest
  }

  protected void assertTopLevelSpanWithTwoSubSpans(int expectedNumberOfSpans, boolean threw = false) {
    def topLevel = expectedNumberOfSpans - 1
    assertTraces(1) {
      trace(expectedNumberOfSpans, true) {
        def topLevelSpan = span(topLevel)
        span(topLevel) {
          operationName "top-level"
          parent()
        }
        if (expectedNumberOfSpans == 3) {
          span(1) {
            operationName "second-span"
            childOf topLevelSpan
          }
          span(0) {
            operationName "first-span"
            childOf topLevelSpan
          }
        } else if (expectedNumberOfSpans == 2) {
          assert threw
          span(0) {
            childOf topLevelSpan
            // either of the jobs could have been started first, thrown, and canceled the other job
            operationName { name -> (name == 'first-span' || name == 'second-span') }
          }
        }
      }
    }
  }


  def "coroutine instrumentation should work without any parent span #dispatcherName"() {
    setup:
    CoreKotlinCoroutineTests kotlinTest = getCoreKotlinCoroutineTestsInstance(dispatcher)
    int expectedNumberOfTraces = kotlinTest.withNoParentSpan(false)

    expect:
    assertTraces(expectedNumberOfTraces, SORT_TRACES_BY_NAMES) {
      trace(1) {
        span(0) {
          operationName "first-span"
          parent()
        }
      }
      trace(1) {
        span(0) {
          operationName "second-span"
          parent()
        }
      }
    }

    where:
    [dispatcherName, dispatcher] << dispatchersToTest
  }

  def "coroutine instrumentation should work when started lazily without any parent span #dispatcherName"() {
    setup:
    CoreKotlinCoroutineTests kotlinTest = getCoreKotlinCoroutineTestsInstance(dispatcher)
    int expectedNumberOfTraces = kotlinTest.withNoParentSpan(true)

    expect:
    assertTraces(expectedNumberOfTraces, SORT_TRACES_BY_NAMES) {
      trace(1) {
        span(0) {
          operationName "first-span"
          parent()
        }
      }
      trace(1) {
        span(0) {
          operationName "second-span"
          parent()
        }
      }
    }

    where:
    [dispatcherName, dispatcher] << dispatchersToTest
  }

  def "coroutine instrumentation should work when started lazily and canceled #dispatcherName"() {
    setup:
    CoreKotlinCoroutineTests kotlinTest = getCoreKotlinCoroutineTestsInstance(dispatcher)
    int expectedNumberOfSpans = kotlinTest.withParentSpanAndOnlyCanceled()

    expect:
    assertTraces(1) {
      trace(expectedNumberOfSpans, true) {
        span(0) {
          operationName "top-level"
          parent()
        }
      }
    }

    where:
    [dispatcherName, dispatcher] << dispatchersToTest
  }

  def "kotlin trace consistent with timeout"() {
    setup:
    CoreKotlinCoroutineTests kotlinTest = getCoreKotlinCoroutineTestsInstance(dispatcher)
    int expectedNumberOfSpans = kotlinTest.traceAfterTimeout()

    expect:
    assertTraces(1) {
      trace(expectedNumberOfSpans, true) {
        span(5) {
          operationName "trace.annotation"
          parent()
        }
        span(0) {
          operationName "1-before-timeout"
          childOf span(5)
        }
        span(1) {
          operationName "2-inside-timeout"
          childOf span(5)
        }
        span(2) {
          operationName "3-after-timeout"
          childOf span(5)
        }
        span(3) {
          operationName "4-after-timeout-2"
          childOf span(5)
        }
        span(4) {
          operationName "5-after-timeout-3"
          childOf span(5)
        }
      }
    }

    where:
    [dispatcherName, dispatcher] << dispatchersToTest
  }

  def "kotlin trace consistent after delay"() {
    setup:
    CoreKotlinCoroutineTests kotlinTest = getCoreKotlinCoroutineTestsInstance(dispatcher)
    int expectedNumberOfSpans = kotlinTest.traceAfterDelay()

    expect:
    assertTraces(1) {
      trace(expectedNumberOfSpans, true) {
        span(5) {
          operationName "trace.annotation"
          parent()
        }
        span(1) {
          operationName "before-process"
          childOf span(5)
        }
        span(2) {
          operationName "process-url-a"
          childOf span(5)
        }
        span(3) {
          operationName "process-url-b"
          childOf span(5)
        }
        span(4) {
          operationName "process-url-c"
          childOf span(5)
        }
        span(0) {
          operationName "after-process"
          childOf span(5)
        }
      }
    }

    where:
    [dispatcherName, dispatcher] << dispatchersToTest
  }

  protected static DDSpan findSpan(List<DDSpan> trace, String opName) {
    for (DDSpan span : trace) {
      if (span.getOperationName() == opName) {
        return span
      }
    }
    return null
  }
}
