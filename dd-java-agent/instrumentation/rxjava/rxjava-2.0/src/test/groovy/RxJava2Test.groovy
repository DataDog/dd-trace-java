import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.schedulers.Schedulers
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import spock.lang.Shared

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan
import static java.util.concurrent.TimeUnit.MILLISECONDS

class RxJava2Test extends InstrumentationSpecification {

  public static final String EXCEPTION_MESSAGE = "test exception"

  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  @Shared
  def addOne = { i ->
    addOneFunc(i)
  }

  @Shared
  def addTwo = { i ->
    addTwoFunc(i)
  }

  @Shared
  def throwException = {
    throw new RuntimeException(EXCEPTION_MESSAGE)
  }

  def "Publisher '#name' test"() {
    when:
    def result = assemblePublisherUnderTrace(publisherSupplier)

    then:
    result == expected
    and:
    assertTraces(1) {
      sortSpansByStart()
      trace(workSpans + 2) {
        span {
          resourceName "trace-parent"
          operationName "trace-parent"
          parent()
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }

        basicSpan(it, "publisher-parent", "publisher-parent", span(0))

        for (int i = 0; i < workSpans; i++) {
          span {
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
    // spotless:off
    name                      | expected | workSpans | publisherSupplier
    "basic maybe"             | 2        | 1         | { -> Maybe.just(1).map(addOne) }
    "two operations maybe"    | 4        | 2         | { -> Maybe.just(2).map(addOne).map(addOne) }
    "delayed maybe"           | 4        | 1         | { -> Maybe.just(3).delay(100, MILLISECONDS).map(addOne) }
    "delayed twice maybe"     | 6        | 2         | { ->
      Maybe.just(4).delay(100, MILLISECONDS).map(addOne).delay(100, MILLISECONDS).map(addOne)
    }
    "basic flowable"          | [6, 7]   | 2         | { -> Flowable.fromIterable([5, 6]).map(addOne) }
    "two operations flowable" | [8, 9]   | 4         | { -> Flowable.fromIterable([6, 7]).map(addOne).map(addOne) }
    "delayed flowable"        | [8, 9]   | 2         | { ->
      Flowable.fromIterable([7, 8]).delay(100, MILLISECONDS).map(addOne)
    }
    "delayed twice flowable"  | [10, 11] | 4         | { ->
      Flowable.fromIterable([8, 9]).delay(100, MILLISECONDS).map(addOne).delay(100, MILLISECONDS).map(addOne)
    }
    "maybe from callable"     | 12       | 2         | { -> Maybe.fromCallable({ addOneFunc(10) }).map(addOne) }
    // spotless:on
  }

  def "Publisher error '#name' test"() {
    when:
    assemblePublisherUnderTrace(publisherSupplier)

    then:
    def exception = thrown RuntimeException
    exception.message == EXCEPTION_MESSAGE
    and:
    assertTraces(1) {
      sortSpansByStart()
      trace(2) {
        span {
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
        basicSpan(it, "publisher-parent", "publisher-parent", span(0))
      }
    }

    where:
    name       | publisherSupplier
    "maybe"    | { -> Maybe.error(new RuntimeException(EXCEPTION_MESSAGE)) }
    "flowable" | { -> Flowable.error(new RuntimeException(EXCEPTION_MESSAGE)) }
  }

  def "Publisher step '#name' test"() {
    when:
    assemblePublisherUnderTrace(publisherSupplier)

    then:
    def exception = thrown RuntimeException
    exception.message == EXCEPTION_MESSAGE
    and:
    assertTraces(1) {
      sortSpansByStart()
      trace(workSpans + 2) {
        span {
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
        basicSpan(it, "publisher-parent", "publisher-parent", span(0))

        for (int i = 0; i < workSpans; i++) {
          span {
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
    name                     | workSpans | publisherSupplier
    "basic maybe failure"    | 1         | { -> Maybe.just(1).map(addOne).map({ throwException() }) }
    "basic flowable failure" | 1         | { -> Flowable.fromIterable([5, 6]).map(addOne).map({ throwException() }) }
  }

  def "Publisher '#name' cancel"() {
    when:
    cancelUnderTrace(publisherSupplier)

    then:
    assertTraces(1) {
      trace(2) {
        span {
          resourceName "trace-parent"
          operationName "trace-parent"
          parent()
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }

        basicSpan(it, "publisher-parent", "publisher-parent", span(0))
      }
    }

    where:
    name             | publisherSupplier
    "basic maybe"    | { -> Maybe.just(1) }
    "basic flowable" | { -> Flowable.fromIterable([5, 6]) }
  }

  def "Publisher chain spans have the correct parent for '#name'"() {
    when:
    assemblePublisherUnderTrace(publisherSupplier)

    then:
    assertTraces(1) {
      trace(workSpans + 2) {
        span {
          resourceName "trace-parent"
          operationName "trace-parent"
          parent()
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }

        basicSpan(it, "publisher-parent", "publisher-parent", span(0))

        for (int i = 0; i < workSpans; i++) {
          span {
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
    // spotless:off
    name             | workSpans | publisherSupplier
    "basic maybe"    | 3         | { -> Maybe.just(1).map(addOne).map(addOne).concatWith(Maybe.just(1).map(addOne)) }
    "basic flowable" | 5         | { ->
      Flowable.fromIterable([5, 6]).map(addOne).map(addOne).concatWith(Maybe.just(1).map(addOne).toFlowable())
    }
    // spotless:on
  }

  def "Publisher chain spans have the correct parents from subscription time"() {
    when:
    def maybe = Maybe.just(42)
      .map(addOne)
      .map(addTwo)

    runUnderTrace("trace-parent") {
      maybe.blockingGet()
    }

    then:
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        span {
          resourceName "trace-parent"
          operationName "trace-parent"
          parent()
        }
        span {
          operationName "addOne"
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }

        span {
          operationName "addTwo"
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }
  }

  def "Publisher chain spans have the correct parents from subscription time '#name'"() {
    when:
    assemblePublisherUnderTrace {
      // The "add one" operations in the publisher created here should be children of the publisher-parent
      def publisher = publisherSupplier()

      AgentSpan intermediate = startSpan("intermediate")
      AgentScope scope = activateSpan(intermediate)
      try {
        if (publisher instanceof Maybe) {
          return ((Maybe) publisher).map(addTwo)
        } else if (publisher instanceof Flowable) {
          return ((Flowable) publisher).map(addTwo)
        }
        throw new IllegalStateException("Unknown publisher type")
      } finally {
        intermediate.finish()
        scope.close()
      }
    }

    then:
    assertTraces(1) {
      trace(3 + 2 * workItems) {
        sortSpansByStart()
        span {
          resourceName "trace-parent"
          operationName "trace-parent"
          parent()
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }

        basicSpan(it, "publisher-parent", "publisher-parent", span(0))
        basicSpan(it, "intermediate", span(1))

        for (int i = 0; i < 2 * workItems; i = i + 2) {
          span {
            operationName "addOne"
            childOf span(1)
            tags {
              "$Tags.COMPONENT" "trace"
              defaultTags()
            }
          }
          span {
            operationName "addTwo"
            childOf span(1)
            tags {
              "$Tags.COMPONENT" "trace"
              defaultTags()
            }
          }
        }
      }
    }

    where:
    name             | workItems | publisherSupplier
    "basic maybe"    | 1         | { -> Maybe.just(1).map(addOne) }
    "basic flowable" | 2         | { -> Flowable.fromIterable([1, 2]).map(addOne) }
  }

  def "Flowables produce the right number of results on '#schedulerName' scheduler"() {
    when:
    List<String> values = Flowable.fromIterable(Arrays.asList(1, 2, 3, 4))
      .parallel()
      .runOn(scheduler)
      .flatMap({ num -> Maybe.just(num.toString() + " on " + Thread.currentThread().getName()).toFlowable() })
      .sequential()
      .toList()
      .blockingGet()

    then:
    values.size() == 4

    where:
    schedulerName | scheduler
    "new-thread"   | Schedulers.newThread()
    "computation" | Schedulers.computation()
    "single"      | Schedulers.single()
    "trampoline"  | Schedulers.trampoline()
  }

  @Trace(operationName = "trace-parent", resourceName = "trace-parent")
  def assemblePublisherUnderTrace(def publisherSupplier) {
    def span = startSpan("publisher-parent")
    // After this activation, the "add two" operations below should be children of this span
    def scope = activateSpan(span)

    def publisher = publisherSupplier()
    try {
      // Read all data from publisher
      if (publisher instanceof Maybe) {
        return ((Maybe) publisher).blockingGet()
      } else if (publisher instanceof Flowable) {
        return ((Flowable) publisher).toList().blockingGet().toArray({ size -> new Integer[size] })
      }

      throw new RuntimeException("Unknown publisher: " + publisher)
    } finally {
      span.finish()
      scope.close()
    }
  }

  @Trace(operationName = "trace-parent", resourceName = "trace-parent")
  def cancelUnderTrace(def publisherSupplier) {
    final AgentSpan span = startSpan("publisher-parent")
    AgentScope scope = activateSpan(span)

    def publisher = publisherSupplier()
    if (publisher instanceof Maybe) {
      publisher = publisher.toFlowable()
    }

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
    span.finish()
  }

  @Trace(operationName = "addOne", resourceName = "addOne")
  def static addOneFunc(int i) {
    return i + 1
  }

  @Trace(operationName = "addTwo", resourceName = "addTwo")
  def static addTwoFunc(int i) {
    return i + 2
  }
}
