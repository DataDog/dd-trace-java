import datadog.trace.agent.test.InstrumentationSpecification

import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class TestFanout extends InstrumentationSpecification {

  def "test propagate with fanout"() {
    when:
    runUnderTrace("parent") {
      new Fanout(executor, 3, true).execute()
    }
    then:
    assertTraces(1) {
      trace(4) {
        span(0) {
          resourceName "parent"
        }
        span(1) {
          resourceName "Fanout.tracedWork"
          childOf span(0)
        }
        span(2) {
          resourceName "Fanout.tracedWork"
          childOf span(0)
        }
        span(3) {
          resourceName "Fanout.tracedWork"
          childOf span(0)
        }
      }
    }

    cleanup:
    executor.shutdownNow()

    where:
    executor << [
      Executors.newSingleThreadExecutor(),
      Executors.newFixedThreadPool(3),
      ForkJoinPool.commonPool()
    ]
  }
}
