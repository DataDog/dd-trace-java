package forkjoin

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.core.DDSpan

import java.util.concurrent.ForkJoinPool

class ForkJoinPoolPropagationTest extends InstrumentationSpecification {
  def "test imbalanced recursive task propagation #parallelism FJP threads (async #async)" () {
    when:
    ForkJoinPool fjp = new ForkJoinPool(parallelism,
      ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, async)

    Integer result = fjp.invoke(new LinearTask(depth))

    then:
    result == depth

    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    List<DDSpan> trace = TEST_WRITER.get(0)
    int i = 0
    for (DDSpan span : trace) {
      assert Integer.toString(++i) == span.getOperationName()
    }
    assert i == depth

    cleanup:
    fjp.shutdownNow()

    where:
    parallelism | depth    | async
    1           |    10    | true
    1           |    20    | true
    1           |    30    | true
    1           |    40    | true
    1           |    50    | true
    2           |    10    | true
    2           |    20    | true
    2           |    30    | true
    2           |    40    | true
    2           |    50    | true
    3           |    10    | true
    3           |    20    | true
    3           |    30    | true
    3           |    40    | true
    3           |    50    | true
    4           |    10    | true
    4           |    20    | true
    4           |    30    | true
    4           |    40    | true
    4           |    50    | true
    1           |    10    | false
    1           |    20    | false
    1           |    30    | false
    1           |    40    | false
    1           |    50    | false
    2           |    10    | false
    2           |    20    | false
    2           |    30    | false
    2           |    40    | false
    2           |    50    | false
    3           |    10    | false
    3           |    20    | false
    3           |    30    | false
    3           |    40    | false
    3           |    50    | false
    4           |    10    | false
    4           |    20    | false
    4           |    30    | false
    4           |    40    | false
    4           |    50    | false
  }
}
