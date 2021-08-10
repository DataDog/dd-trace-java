import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import datadog.trace.core.DDSpan
import scala.concurrent.forkjoin.ForkJoinPool

class ForkJoinPoolPropagationTest extends AgentTestRunner {
  def "test imbalanced recursive task propagation #parallelism FJP threads" () {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS)

    when:
    ForkJoinPool fjp = new ForkJoinPool(parallelism)

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
    parallelism | depth
    1           |    10
    1           |    20
    1           |    30
    1           |    40
    1           |    50
    2           |    10
    2           |    20
    2           |    30
    2           |    40
    2           |    50
    3           |    10
    3           |    20
    3           |    30
    3           |    40
    3           |    50
    4           |    10
    4           |    20
    4           |    30
    4           |    40
    4           |    50
  }
}
