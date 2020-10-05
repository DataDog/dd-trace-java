import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.core.DDSpan

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RecursiveThreadPoolPropagationTest extends AgentTestRunner {

  def "propagate context with recursive submission to #parallelism-thread pool #depth times" () {
    when:
    ExecutorService executorService = Executors.newFixedThreadPool(parallelism)
    executorService.submit(new RecursiveThreadPoolSubmission(executorService, depth, 0))
    then:
    assertTrace(depth)

    cleanup:
    executorService.shutdownNow()

    where:
    depth | parallelism
    1     |     2
    1     |     3
    1     |     4
    2     |     1
    2     |     2
    2     |     3
    2     |     4
    3     |     1
    3     |     2
    3     |     3
    3     |     4
    4     |     1
    4     |     2
    4     |     3
    4     |     4
    5     |     1
    5     |     2
    5     |     3
    5     |     4
  }

  def "propagate context with recursive execution to #parallelism-thread pool #depth times" () {
    when:
    ExecutorService executorService = Executors.newFixedThreadPool(parallelism)
    executorService.submit(new RecursiveThreadPoolExecution(executorService, depth, 0))
    then:
    assertTrace(depth)

    cleanup:
    executorService.shutdownNow()

    where:
    depth | parallelism
    1     |     1
    1     |     2
    1     |     3
    1     |     4
    2     |     1
    2     |     2
    2     |     3
    2     |     4
    3     |     1
    3     |     2
    3     |     3
    3     |     4
    4     |     1
    4     |     2
    4     |     3
    4     |     4
    5     |     1
    5     |     2
    5     |     3
    5     |     4
  }

  def "propagate context with recursive execution and submission to #parallelism-thread pool #depth times" () {
    when:
    ExecutorService executorService = Executors.newFixedThreadPool(parallelism)
    executorService.submit(new RecursiveThreadPoolMixedSubmissionAndExecution(executorService, depth, 0))
    then:
    assertTrace(depth)

    cleanup:
    executorService.shutdownNow()

    where:
    depth | parallelism
    1     |     1
    1     |     2
    1     |     3
    1     |     4
    2     |     1
    2     |     2
    2     |     3
    2     |     4
    3     |     1
    3     |     2
    3     |     3
    3     |     4
    4     |     1
    4     |     2
    4     |     3
    4     |     4
    5     |     1
    5     |     2
    5     |     3
    5     |     4
  }

  private static void assertTrace(int depth) {
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    int i = 0
    int orphanCount = 0
    List<DDSpan> trace = TEST_WRITER.get(0)
    assert trace.size() == depth
    sortByDepth(trace)
    for (DDSpan span : trace) {
      orphanCount += span.isRootSpan() ? 1 : 0
      assert String.valueOf(i++) == span.getOperationName()
    }
    assert orphanCount == 1
  }

  private static void sortByDepth(List<DDSpan> trace) {
    Collections.sort(trace, new Comparator<DDSpan>() {
      @Override
      int compare(DDSpan l, DDSpan r) {
        return Integer.parseInt(l.getOperationName().toString()) - Integer.parseInt(r.getOperationName().toString())
      }
    })
  }
}
