import java.util.concurrent.{Executors, ForkJoinPool}

import groovy.lang.Closure

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future, Promise}

object PromiseUtils {

  private val fjp = ExecutionContext.fromExecutorService(ForkJoinPool.commonPool())
  private val tpe = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  private val stpe = ExecutionContext.fromExecutorService(Executors.newScheduledThreadPool(5))

  def newPromise[T](): Promise[T] = Promise.apply()

  def map[T](promise: Promise[T], callable: Closure[T]): Future[T] = {
    promise.future.map(v => callable.call(v))
  }

  def mapInForkJoinPool[T](promise: Promise[T], callable: Closure[T]): Future[T] = {
    promise.future.map(v => callable.call(v))(fjp)
  }

  def mapInThreadPool[T](promise: Promise[T], callable: Closure[T]): Future[T] = {
    promise.future.map(v => callable.call(v))(tpe)
  }

  def mapInScheduledThreadPool[T](promise: Promise[T], callable: Closure[T]): Future[T] = {
    promise.future.map(v => callable.call(v))(stpe)
  }

  def onComplete[T](future: Future[T], callable: Closure[T]): Unit = {
    future.onComplete(v => callable.call(v.get))
  }
}
