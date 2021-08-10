import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import scala.concurrent.ExecutionContext

import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool

class Scala213PromiseCompletionPriorityForkJoinPoolForkedTest extends ScalaPromiseCompletionPriorityTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.fromExecutor(ForkJoinPool.commonPool())
  }

  @Override
  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.SUSPEND_RESUME,
      CheckpointValidationMode.THREAD_SEQUENCE)
  }
}

class Scala213PromiseCompletionPriorityGlobalForkedTest extends ScalaPromiseCompletionPriorityTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.global()
  }

  @Override
  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.SUSPEND_RESUME,
      CheckpointValidationMode.THREAD_SEQUENCE)
  }
}

class Scala213PromiseCompletionPriorityScheduledThreadPoolForkedTest extends ScalaPromiseCompletionPriorityTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.fromExecutor(Executors.newScheduledThreadPool(5))
  }

  @Override
  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.SUSPEND_RESUME,
      CheckpointValidationMode.THREAD_SEQUENCE)
  }
}

class Scala213PromiseCompletionPriorityThreadPoolForkedTest extends ScalaPromiseCompletionPriorityTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  }
}
