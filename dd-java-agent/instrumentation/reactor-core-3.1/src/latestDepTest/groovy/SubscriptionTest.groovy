import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks

import java.util.concurrent.CountDownLatch

class SubscriptionTest extends InstrumentationSpecification {

  def "subscription test with Sinks.Many sink #sink.class and #consumers consumers"() {
    when:
    def connection = ((Sinks.Many<Connection>) sink)
    CountDownLatch published = new CountDownLatch(consumers)
    // need to wait for subscriber when using a non buffered processor
    CountDownLatch subscribed = new CountDownLatch(consumers)
    for (int i = 0; i < consumers; i++) {
      def t = new Thread({
        runnableUnderTrace("parent", {
          connection.asFlux().subscribe {
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
    connection.tryEmitNext(new Connection()).orThrow()
    published.await()
    then:
    assertTraces(consumers) {
      for (int i = 0; i< consumers; i++) {
        trace(2) {
          basicSpan(it, "parent")
          basicSpan(it, "Connection.query", span(0))
        }
      }
    }
    where:
    sink                                            | consumers
    Sinks.many().unicast().onBackpressureBuffer()   | 1
    Sinks.many().multicast().onBackpressureBuffer() | 1
    Sinks.many().multicast().directAllOrNothing()   | 1
    Sinks.many().multicast().directBestEffort()     | 1
    Sinks.many().replay().all()                     | 1
    Sinks.many().replay().latest()                  | 1
    Sinks.many().multicast().onBackpressureBuffer() | 3
    Sinks.many().multicast().directAllOrNothing()   | 3
    Sinks.many().multicast().directBestEffort()     | 3
    Sinks.many().replay().all()                     | 3
    Sinks.many().replay().latest()                  | 3
  }

  def "subscription test with Sinks.One sink"() {
    when:
    def connection = Sinks.<Connection> one()
    CountDownLatch published = new CountDownLatch(1)
    // need to wait for subscriber when using a non buffered processor
    CountDownLatch subscribed = new CountDownLatch(1)
    def t = new Thread({
      runnableUnderTrace("parent", {
        connection.asMono().subscribe {
          it.query()
          published.countDown()
        }
        subscribed.countDown()
        published.await()
      })
    })
    t.start()
    subscribed.await()
    connection.tryEmitValue(new Connection()).orThrow()
    published.await()
    t.join()
    then:
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        basicSpan(it, "Connection.query", span(0))
      }
    }
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

  static class Connection {
    static int query() {
      def span = AgentTracer.startSpan("Connection.query")
      span.finish()
      return new Random().nextInt()
    }
  }
}
