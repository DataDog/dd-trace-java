import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import reactor.core.publisher.Mono

import java.util.concurrent.CountDownLatch

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class SubscriptionTest extends AgentTestRunner {

  def "subscription test"() {
    when:
    CountDownLatch latch = new CountDownLatch(1)
    runUnderTrace("parent") {
      Mono<Connection> connection = Mono.create {
        it.success(new Connection())
      }
      connection.subscribe {
        it.query()
        latch.countDown()
      }
    }
    latch.await()

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
