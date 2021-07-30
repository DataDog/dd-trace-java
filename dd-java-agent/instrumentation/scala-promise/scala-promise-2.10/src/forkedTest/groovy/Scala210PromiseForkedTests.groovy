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

  @Override
  def setup() {
    CheckpointValidator.DONOTUSE_excludeValidations_DONOTUSE(
      CheckpointValidationMode.SEQUENCE)
  }
}

class Scala210PromiseCompletionPriorityGlobalForkedTest extends ScalaPromiseCompletionPriorityTestBase {
  @Override
  protected scala.concurrent.ExecutionContext getExecutionContext() {
    return ExecutionContext$.MODULE$.global()
  }

  @Override
  def setup() {
    CheckpointValidator.DONOTUSE_excludeValidations_DONOTUSE(
      CheckpointValidationMode.SEQUENCE)
  }
}

class Scala210PromiseCompletionPriorityScheduledThreadPoolForkedTest extends ScalaPromiseCompletionPriorityTestBase {
  @Override
  protected scala.concurrent.ExecutionContext getExecutionContext() {
    return ExecutionContext$.MODULE$.fromExecutor(Executors.newScheduledThreadPool(5))
  }

  @Override
  def setup() {
    CheckpointValidator.DONOTUSE_excludeValidations_DONOTUSE(
      CheckpointValidationMode.SEQUENCE)
  }
}

class Scala210PromiseCompletionPriorityThreadPoolForkedTest extends ScalaPromiseCompletionPriorityTestBase {
  @Override
  protected scala.concurrent.ExecutionContext getExecutionContext() {
    return ExecutionContext$.MODULE$.fromExecutorService(Executors.newCachedThreadPool())
  }

  @Override
  def setup() {
    CheckpointValidator.DONOTUSE_excludeValidations_DONOTUSE(
      CheckpointValidationMode.SEQUENCE)
  }
}
