package datadog.trace.instrumentation.kotlin.coroutines

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import datadog.trace.api.InstrumenterConfig
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.core.DDSpan
import datadog.trace.test.util.Flaky
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadPoolDispatcherKt
import spock.lang.Shared

abstract class AbstractKotlinCoroutineInstrumentationTest<T extends CoreKotlinCoroutineTests> extends InstrumentationSpecification {

  protected abstract T getCoreKotlinCoroutineTestsInstance(CoroutineDispatcher dispatcher)

  def "already-cancelled parent does not leave a trace continuation open"() {
    when:
    runUnderTrace("parent") {
      getCoreKotlinCoroutineTestsInstance(Dispatchers.Default).launchWithCancelledParent()
    }

    then:
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    TEST_WRITER.get(0).size() == 1
    TEST_WRITER.get(0).get(0).operationName.toString() == "parent"
  }

  def "empty withContext preserves thread context callbacks"() {
    when:
    getCoreKotlinCoroutineTestsInstance(Dispatchers.Default).emptyWithContextPreservesFastPath()

    then:
    noExceptionThrown()
  }

  def "withContext with a replaced Job keeps an early-finished root open nonCancellable=#nonCancellable #dispatcherName"() {
    when:
    getCoreKotlinCoroutineTestsInstance(dispatcher).withContextWithReplacedJobKeepsEarlyFinishedRootOpen(nonCancellable)

    then:
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    def trace = TEST_WRITER.get(0)
    trace.size() == 3
    findSpan(trace, "late-child").parentId == findSpan(trace, "early-root").spanId
    findSpan(trace, "after-context").parentId == findSpan(trace, "early-root").spanId

    where:
    [dispatcherName, dispatcher, nonCancellable] << dispatchersToTest.collectMany { [it + false, it + true] }
  }

  def "child launched inside withContext inherits an early-finished root #dispatcherName"() {
    when:
    getCoreKotlinCoroutineTestsInstance(dispatcher).withContextChildInheritsEarlyFinishedRoot()

    then:
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    def trace = TEST_WRITER.get(0)
    trace.size() == 3
    findSpan(trace, "late-child").parentId == findSpan(trace, "early-root").spanId
    findSpan(trace, "after-context").parentId == findSpan(trace, "early-root").spanId

    where:
    [dispatcherName, dispatcher] << dispatchersToTest
  }

  def "flowOn preserves a locally activated root unchangedContext=#unchangedContext suspendFlow=#suspendFlow"() {
    when:
    getCoreKotlinCoroutineTestsInstance(Dispatchers.Default).flowWithLocallyActivatedRoot(unchangedContext, suspendFlow)

    then:
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    def trace = TEST_WRITER.get(0)
    trace.size() == 4
    ["flow-child", "collect-child", "after-flow"].each {
      assert findSpan(trace, it).parentId == findSpan(trace, "flow-root").spanId
    }

    where:
    unchangedContext | suspendFlow
    false            | false
    false            | true
    true             | false
    true             | true
  }

  def "flow still rejects an invalid emission context kind=#kind"() {
    when:
    getCoreKotlinCoroutineTestsInstance(Dispatchers.Default).flowEmissionInContext(kind, false)

    then:
    def error = thrown(IllegalStateException)
    error.message.startsWith("Flow invariant is violated")

    where:
    kind << ["equal-name", "changed-name", "changed-dispatcher", "mdc", "launch"]
  }

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
    findSpan(trace, "preLaunch").spanContext().getParentId() == trace[0].spanContext().getSpanId()
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
    findSpan(trace, "nested").spanContext().getParentId() == trace[0].spanContext().getSpanId()

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
    findSpan(trace, "keptPromise").spanContext().getParentId() == trace[0].spanContext().getSpanId()
    findSpan(trace, "keptPromise2").spanContext().getParentId() == trace[0].spanContext().getSpanId()
    findSpan(trace, "brokenPromise").spanContext().getParentId() == trace[0].spanContext().getSpanId()

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
    findSpan(trace, "timeout1").spanContext().getParentId() == trace[0].spanContext().getSpanId()
    findSpan(trace, "timeout2").spanContext().getParentId() == trace[0].spanContext().getSpanId()
    findSpan(trace, "timeout3").spanContext().getParentId() == trace[0].spanContext().getSpanId()

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

  @Flaky(
  value = "Legacy scope restore/resume race",
  condition = {
    InstrumenterConfig.get().isLegacyContextManagerEnabled()
  }
  )
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

  def "kotlin trace consistent with timeout #dispatcherName"() {
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

  def "kotlin trace consistent after delay #dispatcherName"() {
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

  def "structured children keep the trace open after the seed span finishes #dispatcherName"() {
    setup:
    CoreKotlinCoroutineTests kotlinTest = getCoreKotlinCoroutineTestsInstance(dispatcher)
    int expectedNumberOfSpans = kotlinTest.structuredChildrenOutliveSeedSpan()
    kotlinTest.finishDetached()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == expectedNumberOfSpans
    findSpan(trace, "child").spanContext().getParentId() == findSpan(trace, "seed").spanContext().getSpanId()
    findSpan(trace, "grandchild").spanContext().getParentId() == findSpan(trace, "child").spanContext().getSpanId()

    where:
    [dispatcherName, dispatcher] << dispatchersToTest
  }

  def "child launched after a scoped coroutine keeps the trace open scoped=#scoped #dispatcherName"() {
    setup:
    CoreKotlinCoroutineTests kotlinTest = getCoreKotlinCoroutineTestsInstance(dispatcher)
    int expectedNumberOfSpans = kotlinTest.structuredChildAfterScopedCoroutine(scoped)
    kotlinTest.finishDetached()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == expectedNumberOfSpans
    findSpan(trace, "child").spanContext().getParentId() == findSpan(trace, "seed").spanContext().getSpanId()
    findSpan(trace, "seed").spanContext().getParentId() == findSpan(trace, "root").spanContext().getSpanId()

    where:
    [dispatcherName, dispatcher, scoped] << dispatchersToTest.collectMany { dispatcher ->
      ["none", "withContext", "coroutineScope", "withTimeout"].collect { dispatcher + it }
    }
  }

  def "children of scoped coroutines keep the trace open #dispatcherName (suspendScope=#suspendScope)"() {
    setup:
    CoreKotlinCoroutineTests kotlinTest = getCoreKotlinCoroutineTestsInstance(dispatcher)
    int expectedNumberOfSpans = kotlinTest.structuredChildrenOfScopedCoroutines(suspendScope)
    kotlinTest.finishDetached()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == expectedNumberOfSpans
    findSpan(trace, "scope-child").spanContext().getParentId() == findSpan(trace, "seed").spanContext().getSpanId()
    findSpan(trace, "context-child").spanContext().getParentId() == findSpan(trace, "seed").spanContext().getSpanId()

    where:
    [dispatcherName, dispatcher, suspendScope] << dispatchersToTest.collectMany { dispatcher ->
      [false, true].collect { dispatcher + it }
    }
  }

  def "structured child keeps the trace open when its parent started without one #dispatcherName"() {
    setup:
    CoreKotlinCoroutineTests kotlinTest = getCoreKotlinCoroutineTestsInstance(dispatcher)
    int expectedNumberOfSpans = kotlinTest.structuredChildOfUntracedCoroutine()
    kotlinTest.finishDetached()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == expectedNumberOfSpans
    findSpan(trace, "child").spanContext().getParentId() == findSpan(trace, "root").spanContext().getSpanId()
    where:
    [dispatcherName, dispatcher] << dispatchersToTest
  }

  def "child with a replaced job keeps the trace open supervisor=#supervisor #dispatcherName"() {
    setup:
    CoreKotlinCoroutineTests kotlinTest = getCoreKotlinCoroutineTestsInstance(dispatcher)
    int expectedNumberOfSpans = kotlinTest.childWithReplacedJob(supervisor)
    kotlinTest.finishDetached()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == expectedNumberOfSpans
    findSpan(trace, "child").spanContext().getParentId() == findSpan(trace, "trace.annotation").spanContext().getSpanId()

    where:
    [dispatcherName, dispatcher, supervisor] << dispatchersToTest.collectMany { [it + false, it + true] }
  }

  def "cancelled coroutine keeps the trace open for its structured child #dispatcherName"() {
    setup:
    CoreKotlinCoroutineTests kotlinTest = getCoreKotlinCoroutineTestsInstance(dispatcher)
    int expectedNumberOfSpans = kotlinTest.structuredChildOfCancelledCoroutine()
    kotlinTest.cancelDetached()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == expectedNumberOfSpans
    findSpan(trace, "cleanup").spanContext().getParentId() == findSpan(trace, "trace.annotation").spanContext().getSpanId()

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
