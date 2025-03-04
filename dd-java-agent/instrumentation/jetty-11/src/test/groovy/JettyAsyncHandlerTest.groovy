import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import jakarta.servlet.AsyncContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class JettyAsyncHandlerTest extends Jetty11Test implements TestingGenericHttpNamingConventions.ServerV0 {

  @Override
  protected Handler handler() {
    new ContinuationTestHandler(super.handler())
  }

  @Override
  boolean testSessionId() {
    false // continuation test handler not working with sessions
  }

  @Override
  boolean testWebsockets() {
    false
  }

  static class ContinuationTestHandler implements Handler {
    @Delegate
    private final Handler delegate

    ContinuationTestHandler(Handler delegate) {
      this.delegate = delegate
    }

    final ExecutorService executorService = Executors.newSingleThreadExecutor()

    @Override
    void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) {
      if (!request.getAttribute('datadog.suspended')) {
        AsyncContext async = request.startAsync()
        request.setAttribute('datadog.suspended', Boolean.TRUE)
        executorService.execute {
          async.dispatch() // no-args dispatch doesn't generate a new span
        }
        baseRequest.handled = true
      } else {
        this.delegate.handle(target, baseRequest, request, response)
      }
    }
  }
}

