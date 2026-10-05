import com.twitter.app.lifecycle.Event
import com.twitter.app.lifecycle.Observer
import com.twitter.finatra.http.HttpServer
import com.twitter.util.Await
import com.twitter.util.Closable
import com.twitter.util.Duration
import com.twitter.util.Promise
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.finatra.FinatraDecorator
import spock.lang.Shared

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class FinatraServer270Test extends HttpServerTest<HttpServer> {
  private static final Duration TIMEOUT = Duration.fromSeconds(5)
  private static final Duration STARTUP_TIMEOUT = Duration.fromSeconds(20)

  @Shared
  private FutureTask<Void> serverMain

  static closeAndWait(Closable closable) {
    if (closable != null) {
      Await.ready(closable.close(), TIMEOUT)
    }
  }

  @Override
  String expectedIntegrationName() {
    "netty"
  }

  @Override
  HttpServer startServer(int port) {
    HttpServer testServer = new FinatraServer()

    Promise<Boolean> startupPromise = new Promise<>()

    testServer.withObserver(new Observer() {
        @Override
        void onSuccess(Event event) {
          if (event == testServer.startupCompletionEvent()) {
            startupPromise.setValue(true)
          }
        }

        void onEntry(Event event) {
        }

        @Override
        void onFailure(Event stage, Throwable throwable) {
          if (stage != Event.Close$.MODULE$) {
            startupPromise.setException(throwable)
          }
        }
      })

    // Starting the server is blocking so start it in a separate thread. nonExitingMain (unlike
    // main) reports lifecycle errors instead of calling System.exit, which would take down the
    // whole test worker. The observer above is registered first so no startup event is missed.
    serverMain = new FutureTask<Void>({
      testServer.nonExitingMain("-admin.port=:0", "-http.port=:" + port)
    }, null)
    Thread serverThread = new Thread(serverMain, "finatra-server")
    serverThread.setDaemon(true)
    serverThread.start()

    Await.result(startupPromise, STARTUP_TIMEOUT)

    return testServer
  }

  @Override
  boolean hasHandlerSpan() {
    return true
  }

  @Override
  boolean testNotFound() {
    // Resource name is set to "GET /notFound"
    false
  }

  @Override
  void stopServer(HttpServer httpServer) {
    if (httpServer == null) {
      return // startServer failed before handing the server over
    }
    Await.ready(httpServer.close(), TIMEOUT)
    // Rethrows a shutdown failure reported by the server thread, or times out if it did not stop
    serverMain.get(TIMEOUT.inMilliseconds(), TimeUnit.MILLISECONDS)
  }

  @Override
  String component() {
    return FinatraDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return "finatra.request"
  }

  @Override
  Serializable expectedServerSpanRoute(ServerEndpoint endpoint) {
    return String
  }

  @Override
  boolean hasDecodedResource() {
    return false
  }

  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    def errorEndpoint = endpoint == EXCEPTION || endpoint == ERROR
    trace.span {
      serviceName expectedServiceName()
      operationName "finatra.controller"
      resourceName "FinatraController"
      spanType DDSpanTypes.HTTP_SERVER
      errored errorEndpoint
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" FinatraDecorator.DECORATE.component()
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER

        // Finatra doesn't propagate the stack trace or exception to the instrumentation
        // so the normal errorTags() method can't be used
        defaultTags()
      }
    }
  }
}
