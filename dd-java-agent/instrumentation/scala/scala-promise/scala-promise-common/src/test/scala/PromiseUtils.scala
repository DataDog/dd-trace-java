import PromiseUtils.Timeout
import groovy.lang.Closure

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

class PromiseUtils(implicit ec: ExecutionContext) {
  // This code is only here to ensure that we in the unit promise tests do the
  // first apply inside a trace during the initialization of the PromiseUtils,
  // so that we initialize the unit field if it exists with a context, and
  // the Noop Transformation if it exists with a context.
  Await.result(Future.apply("unused"), Timeout)

  def newPromise[T](): Promise[T] = Promise.apply()

  def map[T](promise: Promise[T], callable: Closure[T]): Future[T] = {
    promise.future.map(v => callable.call(v))
  }

  def onComplete[T](future: Future[T], callable: Closure[T]): Unit = {
    future.onComplete(v => callable.call(v.get))
  }

  def apply[T](callable: Closure[T]): Future[T] = {
    Future(callable.call())
  }

  def await[T](future: Future[T]): T = {
    Await.result(future, Timeout)
  }

  def completeWith[T](promise: Promise[T], future: Future[T]): Unit = {
    promise.completeWith(future)
  }
}

object PromiseUtils {
  val Timeout: Duration = Duration("5s")
}
