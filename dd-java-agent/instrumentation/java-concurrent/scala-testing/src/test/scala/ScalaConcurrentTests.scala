import datadog.trace.agent.test.AgentTestRunner.blockUntilChildSpansFinished
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.{activeScope, activeSpan}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}

class ScalaConcurrentTests {

  /**
   * @return Number of expected spans in the trace
   */
  @Trace
  def traceWithFutureAndCallbacks(): Integer = {
    activeScope().setAsyncPropagation(true)
    val goodFuture: Future[Integer] = Future {
      tracedChild("goodFuture")
      1
    }
    goodFuture onSuccess {
      case _ => tracedChild("successCallback")
    }
    val badFuture: Future[Integer] = Future {
      tracedChild("badFuture")
      throw new RuntimeException("Uh-oh")
    }
    badFuture onFailure {
      case t: Throwable => tracedChild("failureCallback")
    }

    blockUntilChildSpansFinished(4)
    return 5
  }

  @Trace
  def tracedAcrossThreadsWithNoTrace(): Integer = {
    activeScope().setAsyncPropagation(true)
    val goodFuture: Future[Integer] = Future {
      1
    }
    goodFuture onSuccess {
      case _ => Future {
        2
      } onSuccess {
        case _ => tracedChild("callback")
      }
    }

    blockUntilChildSpansFinished(1)
    return 2
  }

  /**
   * @return Number of expected spans in the trace
   */
  @Trace
  def traceWithPromises(): Integer = {
    activeScope().setAsyncPropagation(true)
    val keptPromise = Promise[Boolean]()
    val brokenPromise = Promise[Boolean]()
    val afterPromise = keptPromise.future
    val afterPromise2 = keptPromise.future

    val failedAfterPromise = brokenPromise.future

    Future {
      tracedChild("future1")
      keptPromise success true
      brokenPromise failure new IllegalStateException()
    }

    afterPromise onSuccess {
      case b => tracedChild("keptPromise")
    }
    afterPromise2 onSuccess {
      case b => tracedChild("keptPromise2")
    }

    failedAfterPromise onFailure {
      case t => tracedChild("brokenPromise")
    }

    blockUntilChildSpansFinished(4)
    return 5
  }

  /**
   * @return Number of expected spans in the trace
   */
  @Trace
  def tracedWithFutureFirstCompletions(): Integer = {
    activeScope().setAsyncPropagation(true)
    val completedVal = Future.firstCompletedOf(
      List(
        Future {
          tracedChild("timeout1")
          false
        },
        Future {
          tracedChild("timeout2")
          false
        },
        Future {
          tracedChild("timeout3")
          true
        }))
    Await.result(completedVal, 30 seconds)

    blockUntilChildSpansFinished(3)
    return 4
  }

  /**
   * @return Number of expected spans in the trace
   */
  @Trace
  def tracedTimeout(): Integer = {
    activeScope().setAsyncPropagation(true)
    val f: Future[String] = Future {
      tracedChild("timeoutChild")
      while (true) {
        // never actually finish
      }
      "done"
    }

    try {
      Await.result(f, 1 milliseconds)
    } catch {
      case e: Exception => {}
    }

    blockUntilChildSpansFinished(1)
    return 2
  }

  @Trace
  def tracedChild(opName: String): Unit = {
    activeSpan().setSpanName(opName)
  }
}
