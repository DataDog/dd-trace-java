import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class CompletableFuturePromiseForkJoinPoolTest extends CompletableFuturePromiseTest {
  @Override
  Executor executor() {
    return Executors.newWorkStealingPool(3) // Three is the magic number
  }

  @Override
  def setup() {
    CheckpointValidator.DONOTUSE_excludeValidations_DONOTUSE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.SEQUENCE)
  }
}
