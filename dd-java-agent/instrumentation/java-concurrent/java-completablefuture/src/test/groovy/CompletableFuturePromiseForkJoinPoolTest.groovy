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
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.SEQUENCE)
  }
}
