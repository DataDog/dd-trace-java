
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

  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)
  }
}

class Scala213PromiseGlobalTest extends ScalaPromiseTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.global()
  }

  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)
  }
}

class Scala213PromiseScheduledThreadPoolTest extends ScalaPromiseTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.fromExecutor(Executors.newScheduledThreadPool(5))
  }

  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)
  }
}

class Scala213PromiseThreadPoolTest extends ScalaPromiseTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  }

  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)
  }
}
