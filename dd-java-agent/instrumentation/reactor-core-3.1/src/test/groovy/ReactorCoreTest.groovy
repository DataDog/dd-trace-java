import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.Tags
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import spock.lang.Shared

import java.time.Duration

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan

class ReactorCoreTest extends AgentTestRunner {

  public static final String EXCEPTION_MESSAGE = "test exception"

  @Shared
  def addOne = { i ->
    addOneFunc(i)
  }

  @Shared
  def throwException = {
    throw new RuntimeException(EXCEPTION_MESSAGE)
  }

  def "Publisher '#name' test"() {
    when:
    def result = runUnderTrace(publisherSupplier)

    then:
    result == expected
    and:
    sortAndAssertTraces(1) {
      trace(0, workSpans + 2) {
        span(0) {
          resourceName "trace-parent"
          operationName "trace-parent"
          parent()
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }

        basicSpan(it, 1, "publisher-parent", "publisher-parent", span(0))

        for (int i = 0; i < workSpans; i++) {
          span(i + 2) {
            resourceName "addOne"
            operationName "addOne"
            childOf(span(1))
            tags {
              "$Tags.COMPONENT" "trace"
              defaultTags()
            }
          }
        }
      }
    }

    where:
    name                  | expected | workSpans | publisherSupplier
    "basic mono"          | 2        | 1         | { -> Mono.just(1).map(addOne) }
    "two operations mono" | 4        | 2         | { -> Mono.just(2).map(addOne).map(addOne) }
    "delayed mono"        | 4        | 1         | { -> Mono.just(3).delayElement(Duration.ofMillis(100)).map(addOne) }
    "delayed twice mono"  | 6        | 2         | { ->
      Mono.just(4).delayElement(Duration.ofMillis(100)).map(addOne).delayElement(Duration.ofMillis(100)).map(addOne)
    }
    "basic flux"          | [6, 7]   | 2         | { -> Flux.fromIterable([5, 6]).map(addOne) }
    "two operations flux" | [8, 9]   | 4         | { -> Flux.fromIterable([6, 7]).map(addOne).map(addOne) }
    "delayed flux"        | [8, 9]   | 2         | { ->
      Flux.fromIterable([7, 8]).delayElements(Duration.ofMillis(100)).map(addOne)
    }
    "delayed twice flux"  | [10, 11] | 4         | { ->
      Flux.fromIterable([8, 9]).delayElements(Duration.ofMillis(100)).map(addOne).delayElements(Duration.ofMillis(100)).map(addOne)
    }

    "mono from callable"  | 12       | 2         | { -> Mono.fromCallable({ addOneFunc(10) }).map(addOne) }
  }

  def "Publisher error '#name' test"() {
    when:
    runUnderTrace(publisherSupplier)

    then:
    def exception = thrown RuntimeException
    exception.message == EXCEPTION_MESSAGE
    and:
    sortAndAssertTraces(1) {
      trace(0, 2) {
        span(0) {
          resourceName "trace-parent"
          operationName "trace-parent"
          parent()
          errored true
          tags {
            "$Tags.COMPONENT" "trace"
            errorTags(RuntimeException, EXCEPTION_MESSAGE)
            defaultTags()
          }
        }

        // It's important that we don't attach errors at the Reactor level so that we don't
        // impact the spans on reactor integrations such as netty and lettuce, as reactor is
        // more of a context propagation mechanism than something we would be tracking for
        // errors this is ok.
        basicSpan(it, 1, "publisher-parent", "publisher-parent", span(0))
      }
    }

    where:
    name   | publisherSupplier
    "mono" | { -> Mono.error(new RuntimeException(EXCEPTION_MESSAGE)) }
    "flux" | { -> Flux.error(new RuntimeException(EXCEPTION_MESSAGE)) }
  }

  def "Publisher step '#name' test"() {
    when:
    runUnderTrace(publisherSupplier)

    then:
    def exception = thrown RuntimeException
    exception.message == EXCEPTION_MESSAGE
    and:
    sortAndAssertTraces(1) {
      trace(0, workSpans + 2) {
        span(0) {
          resourceName "trace-parent"
          operationName "trace-parent"
          parent()
          errored true
          tags {
            "$Tags.COMPONENT" "trace"
            errorTags(RuntimeException, EXCEPTION_MESSAGE)
            defaultTags()
          }
        }

        // It's important that we don't attach errors at the Reactor level so that we don't
        // impact the spans on reactor integrations such as netty and lettuce, as reactor is
        // more of a context propagation mechanism than something we would be tracking for
        // errors this is ok.
        basicSpan(it, 1, "publisher-parent", "publisher-parent", span(0))

        for (int i = 0; i < workSpans; i++) {
          span(i + 2) {
            resourceName "addOne"
            operationName "addOne"
            childOf(span(1))
            tags {
              "$Tags.COMPONENT" "trace"
              defaultTags()
            }
          }
        }
      }
    }

    where:
    name                 | workSpans | publisherSupplier
    "basic mono failure" | 1         | { -> Mono.just(1).map(addOne).map({ throwException() }) }
    "basic flux failure" | 1         | { -> Flux.fromIterable([5, 6]).map(addOne).map({ throwException() }) }
  }

  def "Publisher '#name' cancel"() {
    when:
    cancelUnderTrace(publisherSupplier)

    then:
    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          resourceName "trace-parent"
          operationName "trace-parent"
          parent()
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }

        basicSpan(it, 1, "publisher-parent", "publisher-parent", span(0))
      }
    }

    where:
    name         | publisherSupplier
    "basic mono" | { -> Mono.just(1) }
    "basic flux" | { -> Flux.fromIterable([5, 6]) }
  }

  def "Publisher chain spans have the correct parent for '#name'"() {
    when:
    runUnderTrace(publisherSupplier)

    then:
    assertTraces(1) {
      trace(0, workSpans + 2) {
        span(0) {
          resourceName "trace-parent"
          operationName "trace-parent"
          parent()
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }

        basicSpan(it, 1, "publisher-parent", "publisher-parent", span(0))

        for (int i = 0; i < workSpans; i++) {
          span(i + 2) {
            resourceName "addOne"
            operationName "addOne"
            childOf(span(1))
            tags {
              "$Tags.COMPONENT" "trace"
              defaultTags()
            }
          }
        }
      }
    }

    where:
    name         | workSpans | publisherSupplier
    "basic mono" | 3         | { -> Mono.just(1).map(addOne).map(addOne).then(Mono.just(1).map(addOne)) }
    "basic flux" | 5         | { -> Flux.fromIterable([5, 6]).map(addOne).map(addOne).then(Mono.just(1).map(addOne)) }
  }

  def "Publisher chain spans have the correct parents from assembly time '#name'"() {
    when:
    runUnderTrace {
      // The operations in the publisher created here all end up children of the publisher-parent
      Publisher<Integer> publisher = publisherSupplier()

      AgentSpan intermediate = startSpan("intermediate")
      // After this activation, all additions to the assembly are children of this span
      AgentScope scope = activateSpan(intermediate, true)
      try {
        if (publisher instanceof Mono) {
          return ((Mono) publisher).map(addOne)
        } else if (publisher instanceof Flux) {
          return ((Flux) publisher).map(addOne)
        }
        throw new IllegalStateException("Unknown publisher type")
      } finally {
        scope.close()
      }
    }

    then:
    sortAndAssertTraces(1) {
      trace(0, (workItems * 2) + 3) {
        span(0) {
          resourceName "trace-parent"
          operationName "trace-parent"
          parent()
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }

        basicSpan(it, 1, "publisher-parent", "publisher-parent", span(0))
        basicSpan(it, 2, "intermediate", "intermediate", span(1))

        for (int i = 0; i < workItems * 2; i++) {
          span(i + 3) {
            resourceName "addOne"
            operationName "addOne"
            childOf(span(i % 2 == 0 ? 1 : 2))
            tags {
              "$Tags.COMPONENT" "trace"
              defaultTags()
            }
          }
        }
      }
    }

    where:
    name         | workItems | publisherSupplier
    "basic mono" | 1         | { -> Mono.just(1).map(addOne) }
    "basic flux" | 2         | { -> Flux.fromIterable([1, 2]).map(addOne) }
  }

  def "Publisher chain spans can have the parent removed at assembly time '#name'"() {
    when:
    runUnderTrace {
      // The operations in the publisher created here all end up children of the publisher-parent
      Publisher<Integer> publisher = publisherSupplier()

      // After this activation, all additions to the assembly will create new traces
      AgentScope scope = activateSpan(AgentTracer.noopSpan(), true)
      try {
        if (publisher instanceof Mono) {
          return ((Mono) publisher).map(addOne)
        } else if (publisher instanceof Flux) {
          return ((Flux) publisher).map(addOne)
        }
        throw new IllegalStateException("Unknown publisher type")
      } finally {
        scope.close()
      }
    }

    then:
    sortAndAssertTraces(1 + workItems) {
      trace(0, 2 + workItems) {
        span(0) {
          resourceName "trace-parent"
          operationName "trace-parent"
          parent()
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }

        basicSpan(it, 1, "publisher-parent", "publisher-parent", span(0))

        for (int i = 0; i < workItems; i++) {
          span(2 + i) {
            resourceName "addOne"
            operationName "addOne"
            childOf(span(1))
            tags {
              "$Tags.COMPONENT" "trace"
              defaultTags()
            }
          }
        }
      }
      for (int i = 0; i < workItems; i++) {
        trace(i + 1, 1) {
          span(0) {
            resourceName "addOne"
            operationName "addOne"
            parent()
            tags {
              "$Tags.COMPONENT" "trace"
              defaultTags()
            }
          }
        }
      }
    }

    where:
    name         | workItems | publisherSupplier
    "basic mono" | 1         | { -> Mono.just(1).map(addOne) }
    "basic flux" | 2         | { -> Flux.fromIterable([1, 2]).map(addOne) }
  }

  @Trace(operationName = "trace-parent", resourceName = "trace-parent")
  def runUnderTrace(def publisherSupplier) {
    final AgentSpan span = startSpan("publisher-parent")

    AgentScope scope = activateSpan(span, true)
    try {
      scope.setAsyncPropagation(true)

      def publisher = publisherSupplier()
      // Read all data from publisher
      if (publisher instanceof Mono) {
        return publisher.block()
      } else if (publisher instanceof Flux) {
        return publisher.toStream().toArray({ size -> new Integer[size] })
      }

      throw new RuntimeException("Unknown publisher: " + publisher)
    } finally {
      scope.close()
    }
  }

  @Trace(operationName = "trace-parent", resourceName = "trace-parent")
  def cancelUnderTrace(def publisherSupplier) {
    final AgentSpan span = startSpan("publisher-parent")
    AgentScope scope = activateSpan(span, true)
    scope.setAsyncPropagation(true)

    def publisher = publisherSupplier()
    publisher.subscribe(new Subscriber<Integer>() {
      void onSubscribe(Subscription subscription) {
        subscription.cancel()
      }

      void onNext(Integer t) {
      }

      void onError(Throwable error) {
      }

      void onComplete() {
      }
    })

    scope.close()
  }

  @Trace(operationName = "addOne", resourceName = "addOne")
  def static addOneFunc(int i) {
    return i + 1
  }

  void sortAndAssertTraces(
    final int size,
    @ClosureParams(value = SimpleType, options = "datadog.trace.agent.test.asserts.ListWriterAssert")
    @DelegatesTo(value = ListWriterAssert, strategy = Closure.DELEGATE_FIRST)
    final Closure spec) {

    TEST_WRITER.waitForTraces(size)

    TEST_WRITER.each {
      it.sort({ a, b ->
        return a.startTimeNano <=> b.startTimeNano
      })
    }

    TEST_WRITER.sort({ a, b ->
      return a[0].startTimeNano <=> b[0].startTimeNano
    })

    assertTraces(size, spec)
  }
}
