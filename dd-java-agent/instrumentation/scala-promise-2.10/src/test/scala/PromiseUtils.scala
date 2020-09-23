import groovy.lang.Closure

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}

object PromiseUtils {

  def newPromise[T](): Promise[T] = Promise.apply()

  def map[T](promise: Promise[T], callable: Closure[T]): Future[T] = {
    promise.future.map(v => callable.call(v))
  }

  def onComplete[T](future: Future[T], callable: Closure[T]): Unit = {
    future.onComplete(v => callable.call(v.get))
  }
}
