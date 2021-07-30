import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode

import java.util.concurrent.Executor
import java.util.concurrent.Executors

class CompletableFuturePromiseThreadPoolTest extends CompletableFuturePromiseTest {
  @Override
  Executor executor() {
    return Executors.newFixedThreadPool(3) // Three is the magic number
  }

  @Override
  def setup() {
    CheckpointValidator.DONOTUSE_excludeValidations_DONOTUSE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.SEQUENCE)
  }
}
