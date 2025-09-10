import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.Tags
import reactor.core.publisher.DirectProcessor
import reactor.core.publisher.EmitterProcessor
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxProcessor
import reactor.core.publisher.Mono
import reactor.core.publisher.TopicProcessor
import reactor.core.publisher.WorkQueueProcessor

import java.util.concurrent.CountDownLatch

class SubscriptionTest extends InstrumentationSpecification {

  def "subscription test with processor #processor.class and #consumers consumers"() {
    when:
    def connection = (FluxProcessor<Connection, Connection>) processor
    CountDownLatch published = new CountDownLatch(consumers)
    // need to wait for subscriber when using a non buffered processor
    CountDownLatch subscribed = new CountDownLatch(consumers)
    for (int i = 0; i < consumers; i++) {
      def t = new Thread({
        runnableUnderTrace("parent", {
          connection.subscribe {
            it.query()
            published.countDown()
          }
          subscribed.countDown()
          published.await()
        })
      })
      t.start()
    }
    subscribed.await()
    connection.sink().next(new Connection())
    published.await()
    then:
    assertTraces(consumers) {
      for (int i = 0; i < consumers; i++) {
        trace(2) {
          basicSpan(it, "parent")
          basicSpan(it, "Connection.query", span(0))
        }
      }
    }
    where:
    processor                   | consumers
    DirectProcessor.create()    | 1
    EmitterProcessor.create()   | 1
    TopicProcessor.create()     | 1
    WorkQueueProcessor.create() | 1
    DirectProcessor.create()    | 3
    EmitterProcessor.create()   | 3
    TopicProcessor.create()     | 3
  }

  def "test broadcasting flux"() {
    when:
    def connection = Flux.<Connection> just(new Connection()).publish()

    CountDownLatch published = new CountDownLatch(1)
    // need to wait for subscriber when using a non buffered processor
    CountDownLatch subscribed = new CountDownLatch(1)
    def t = new Thread({
      runnableUnderTrace("parent", {
        connection.subscribe {
          it.query()
          published.countDown()
        }
        subscribed.countDown()
        published.await()
      })
    })
    t.start()
    subscribed.await()
    connection.connect()
    published.await()
    then:
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        basicSpan(it, "Connection.query", span(0))
      }
    }
  }

  def 'Mono then()'() {
    when:
    runUnderTrace("parent", {
      Mono.create {
        def creator = it
        runUnderTrace("child1", {
          creator.success()
        })
      }
      .then(Mono.create {
        def creator = it
        runUnderTrace("child2", {
          creator.success()
        })
      })
      .block()
    })
    then:
    assertTraces(1, {
      trace(3) {
        sortSpansByStart()
        basicSpan(it, "parent")
        basicSpan(it, "child1", span(0))
        basicSpan(it, "child2", span(0))
      }
    })
  }

  def 'Mono with lifecycle spans and no parent'() {
    when:
    Mono.fromCallable {
      runUnderTrace("span", {
        Mono.just("Hello World")
      })
    }
    .doOnNext {
      runUnderTrace("onNext", {})
    }
    .doFinally { runUnderTrace("finally", {}) }
    .doAfterTerminate { runUnderTrace("after", {}) }
    .block()
    then:
    assertTraces(4, {
      trace(1) {
        basicSpan(it, "span")
      }
      trace(1) {
        basicSpan(it, "onNext")
      }
      trace(1) {
        basicSpan(it, "after")
      }
      trace(1) {
        basicSpan(it, "finally")
      }
    })
  }

  def 'Mono with lifecycle spans and parent'() {
    when:
    runUnderTrace("parent", {
      Mono.fromCallable {
        runUnderTrace("span", {
          Mono.just("Hello World")
        })
      }
      .doOnNext {
        runUnderTrace("onNext", {})
      }
      .doFinally { runUnderTrace("finally", {}) }
      .doAfterTerminate { runUnderTrace("after", {}) }
      .block()
    })
    then:
    assertTraces(1, {
      trace(5) {
        sortSpansByStart()
        basicSpan(it, "parent")
        basicSpan(it, "span", span(0))
        basicSpan(it, "onNext", span(0))
        basicSpan(it, "after", span(0))
        basicSpan(it, "finally", span(0))
      }
    })
  }

  def "Optimized mono should finish attached spans"() {
    when:
    runUnderTrace("parent", {
      optimizedTraced().block()
    })

    then:
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          resourceName "SubscriptionTest.optimizedTraced"
          operationName "trace.annotation"
          tags {
            defaultTags()
            "$Tags.COMPONENT" "trace"
          }
        }
      }
    }
  }

  @Trace
  static Mono<String> optimizedTraced() {
    // MonoSource is optimized by not subscribing recursively
    return Mono.from (Mono.just("test"))
  }

  static class Connection {
    static int query() {
      def span = AgentTracer.startSpan("Connection.query")
      span.finish()
      return new Random().nextInt()
    }
  }
}
