import java.util.concurrent.ConcurrentLinkedQueue

import scala.concurrent.{ExecutionContext, Future, Promise}

final class QueuingExecutionContext extends ExecutionContext {
  private val tasks = new ConcurrentLinkedQueue[Runnable]()

  override def execute(task: Runnable): Unit = tasks.add(task)

  override def reportFailure(cause: Throwable): Unit = throw cause

  def queuedTaskCount: Int = tasks.size()

  def runNext(): Boolean = {
    val task = tasks.poll()
    if (task eq null) {
      false
    } else {
      task.run()
      true
    }
  }
}

final class FirstCompletedOfUtils(executionContext: ExecutionContext) {
  private implicit val ec: ExecutionContext = executionContext

  def newPromise[T](): Promise[T] = Promise[T]()

  def firstCompleted[T](first: Promise[T], second: Promise[T]): Future[T] =
    Future.firstCompletedOf(List(first.future, second.future))

  def onComplete[T](promise: Promise[T], callback: Runnable): Unit =
    promise.future.onComplete(_ => callback.run())
}
