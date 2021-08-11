import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import scala.concurrent.ExecutionContext$

import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool

class Scala210PromiseCompletionPriorityForkJoinPoolForkedTest extends ScalaPromiseCompletionPriorityTestBase {
  @Override
  protected scala.concurrent.ExecutionContext getExecutionContext() {
    return ExecutionContext$.MODULE$.fromExecutor(ForkJoinPool.commonPool())
  }

  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)
  }
}

class Scala210PromiseCompletionPriorityGlobalForkedTest extends ScalaPromiseCompletionPriorityTestBase {
  @Override
  protected scala.concurrent.ExecutionContext getExecutionContext() {
    return ExecutionContext$.MODULE$.global()
  }

  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)
  }
}

class Scala210PromiseCompletionPriorityScheduledThreadPoolForkedTest extends ScalaPromiseCompletionPriorityTestBase {
  @Override
  protected scala.concurrent.ExecutionContext getExecutionContext() {
    return ExecutionContext$.MODULE$.fromExecutor(Executors.newScheduledThreadPool(5))
  }

  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)
  }
}

class Scala210PromiseCompletionPriorityThreadPoolForkedTest extends ScalaPromiseCompletionPriorityTestBase {
  @Override
  protected scala.concurrent.ExecutionContext getExecutionContext() {
    return ExecutionContext$.MODULE$.fromExecutorService(Executors.newCachedThreadPool())
  }

  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)
  }
}
