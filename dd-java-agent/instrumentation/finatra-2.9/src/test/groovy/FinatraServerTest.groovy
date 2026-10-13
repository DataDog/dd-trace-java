import com.twitter.finatra.http.HttpServer
import com.twitter.util.Await
import com.twitter.util.Closable
import com.twitter.util.Duration
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.finatra.FinatraDecorator
import spock.lang.Shared

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

abstract class FinatraServerTest extends HttpServerTest<HttpServer> {
  private static final Duration TIMEOUT = Duration.fromSeconds(5)
  private static final long STARTUP_TIMEOUT = 20 // SECONDS

  @Shared
  private FutureTask<Void> serverMain

  static closeAndWait(Closable closable) {
    if (closable != null) {
      Await.ready(closable.close(), TIMEOUT)
    }
  }

  @Override
  HttpServer startServer(int port) {
    def testServer = new FinatraServer()

    // Starting the server is blocking so start it in a separate thread. nonExitingMain (unlike
    // main) reports lifecycle errors instead of calling System.exit, which would take down the
    // whole test worker.
    serverMain = new FutureTask<Void>({
      testServer.nonExitingMain("-admin.port=:0", "-http.port=:" + port)
    }, null)
    Thread serverThread = new Thread(serverMain, "finatra-server")
    serverThread.setDaemon(true)
    serverThread.start()

    boolean started = testServer.awaitStart(STARTUP_TIMEOUT, TimeUnit.SECONDS)
    if (serverMain.isDone()) {
      serverMain.get() // rethrows the startup failure, if there was one
      throw new IllegalStateException("Finatra server exited during startup")
    }
    if (!started) {
      // Close here: the server is never handed over, so stopServer cannot do it
      closeAndWait(testServer)
      throw new IllegalStateException("Finatra server did not start within ${STARTUP_TIMEOUT}s")
    }

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
      return // startServer failed before handing the server over, and closed it itself
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
    return operation()
  }

  @Override
  Serializable expectedServerSpanRoute(ServerEndpoint endpoint) {
    return String
  }

  @Override
  boolean hasDecodedResource() {
    return false
  }

  @Override
  String expectedIntegrationName() {
    "netty"
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

class FinatraServerV0ForkedTest extends FinatraServerTest {
  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return null
  }

  @Override
  String operation() {
    return "finatra.request"
  }
}

class FinatraServerV1ForkedTest extends FinatraServerTest implements TestingGenericHttpNamingConventions.ServerV1 {
}
