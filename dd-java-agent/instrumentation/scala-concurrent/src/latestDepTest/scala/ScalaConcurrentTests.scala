import datadog.trace.agent.test.InstrumentationSpecification.blockUntilChildSpansFinished
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.{
  setAsyncPropagationEnabled,
  activeSpan
}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}

class ScalaConcurrentTests {

  /** @return Number of expected spans in the trace
    */
  @Trace
  def traceWithFutureAndCallbacks(): Integer = {
    setAsyncPropagationEnabled(true)
    val goodFuture: Future[Integer] = Future {
      tracedChild("goodFuture")
      1
    }
    goodFuture.onComplete(_ => tracedChild("good complete"))
    val badFuture: Future[Integer] = Future {
      tracedChild("badFuture")
      throw new RuntimeException("Uh-oh")
    }
    badFuture.onComplete(t => tracedChild("bad complete"))

    blockUntilChildSpansFinished(activeSpan(), 4)
    return 5
  }

  @Trace
  def tracedAcrossThreadsWithNoTrace(): Integer = {
    setAsyncPropagationEnabled(true)
    val goodFuture: Future[Integer] = Future {
      1
    }
    goodFuture.onComplete(_ =>
      Future {
        2
      }.onComplete(_ => tracedChild("callback"))
    )

    blockUntilChildSpansFinished(activeSpan(), 1)
    return 2
  }

  /** @return Number of expected spans in the trace
    */
  @Trace
  def traceWithPromises(): Integer = {
    setAsyncPropagationEnabled(true)
    val keptPromise   = Promise[Boolean]()
    val brokenPromise = Promise[Boolean]()
    val afterPromise  = keptPromise.future
    val afterPromise2 = keptPromise.future

    val failedAfterPromise = brokenPromise.future

    Future {
      tracedChild("future1")
      keptPromise success true
      brokenPromise failure new IllegalStateException()
    }

    afterPromise.onComplete(_ => tracedChild("keptPromise"))
    afterPromise2.onComplete(_ => tracedChild("keptPromise2"))
    failedAfterPromise.onComplete(_ => tracedChild("brokenPromise"))

    blockUntilChildSpansFinished(activeSpan(), 4)
    return 5
  }

  /** @return Number of expected spans in the trace
    */
  @Trace
  def tracedWithFutureFirstCompletions(): Integer = {
    setAsyncPropagationEnabled(true)
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
        }
      )
    )
    Await.result(completedVal, 30.seconds)

    blockUntilChildSpansFinished(activeSpan(), 3)
    return 4
  }

  /** @return Number of expected spans in the trace
    */
  @Trace
  def tracedTimeout(): Integer = {
    setAsyncPropagationEnabled(true)
    val f: Future[String] = Future {
      tracedChild("timeoutChild")
      while (true) {
        // never actually finish
      }
      "done"
    }

    try {
      Await.result(f, 1.milliseconds)
    } catch {
      case e: Exception => {}
    }

    blockUntilChildSpansFinished(activeSpan(), 1)
    return 2
  }

  @Trace
  def tracedChild(opName: String): Unit = {
    activeSpan().setSpanName(opName)
  }
}
