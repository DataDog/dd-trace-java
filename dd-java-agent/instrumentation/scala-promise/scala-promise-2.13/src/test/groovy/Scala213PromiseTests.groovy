
import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

import java.util.concurrent.ForkJoinPool

class Scala213PromiseForkJoinPoolTest extends ScalaPromiseTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.fromExecutor(ForkJoinPool.commonPool())
  }

  @Override
  def setup() {
    CheckpointValidator.DONOTUSE_excludeValidations_DONOTUSE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.SEQUENCE)
  }
}

class Scala213PromiseGlobalTest extends ScalaPromiseTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.global()
  }

  @Override
  def setup() {
    CheckpointValidator.DONOTUSE_excludeValidations_DONOTUSE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.SEQUENCE)
  }
}

class Scala213PromiseScheduledThreadPoolTest extends ScalaPromiseTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.fromExecutor(Executors.newScheduledThreadPool(5))
  }

  @Override
  def setup() {
    CheckpointValidator.DONOTUSE_excludeValidations_DONOTUSE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.SEQUENCE)
  }
}

class Scala213PromiseThreadPoolTest extends ScalaPromiseTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  }

  @Override
  def setup() {
    CheckpointValidator.DONOTUSE_excludeValidations_DONOTUSE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.SEQUENCE)
  }
}
