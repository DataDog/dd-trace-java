package datadog.trace.instrumentation.jetty9

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.servlet3.AsyncDispatcherDecorator
import org.eclipse.jetty.continuation.Continuation
import org.eclipse.jetty.continuation.ContinuationSupport
import org.eclipse.jetty.server.AsyncContextEvent
import org.eclipse.jetty.server.AsyncContextState
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.server.session.SessionHandler

import javax.servlet.MultipartConfigElement
import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

import static test.TestHandler.handleRequest
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR
import static datadog.trace.instrumentation.servlet3.TestServlet3.SERVLET_TIMEOUT

abstract class JettyContinuationHandlerTest extends Jetty9Test {

  private static final long CONTINUATION_TIMEOUT = TimeUnit.SECONDS.toMillis(30)
  private static final long TIMEOUT_TASK_WAIT = TimeUnit.SECONDS.toNanos(10)

  @Override
  AbstractHandler handler() {
    def ret = new SessionHandler()
    ret.handler = ContinuationTestHandler.INSTANCE
    ret
  }

  static class ContinuationTestHandler extends AbstractHandler {
    private static final Field TIMEOUT_TASK = AsyncContextEvent.getDeclaredField('_timeoutTask')
    private static final MultipartConfigElement MULTIPART_CONFIG_ELEMENT = new MultipartConfigElement(System.getProperty('java.io.tmpdir'))
    static final ContinuationTestHandler INSTANCE = new ContinuationTestHandler()
    final ExecutorService executorService = Executors.newSingleThreadExecutor()

    static {
      TIMEOUT_TASK.accessible = true
      if (!Modifier.isVolatile(TIMEOUT_TASK.modifiers)) {
        throw new IllegalStateException('Jetty timeout task must be volatile')
      }
    }

    @Override
    void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
      request.setAttribute('org.eclipse.jetty.multipartConfig', MULTIPART_CONFIG_ELEMENT)
      request.setAttribute('org.eclipse.multipartConfig', MULTIPART_CONFIG_ELEMENT)
      final Continuation continuation = ContinuationSupport.getContinuation(request)
      // some versions of jetty (like 9.4.15.v20190215) get into a loop:
      // after an exception from handleRequest, the error will be handled here again;
      // calling handleRequest will cause a new exception, and the process will repeat.
      // this happens in the /exception endpoint
      if (!request.getAttribute('javax.servlet.error.status_code')) {
        if (continuation.initial) {
          continuation.setTimeout(CONTINUATION_TIMEOUT)
          continuation.suspend()
          executorService.execute {
            // Jetty schedules the timeout after this handler returns. Its volatile task field is
            // the publication signal that makes it safe for resume to cancel the scheduled task.
            awaitTimeoutTask(request)
            continuation.resume()
          }
        } else {
          handleRequest(baseRequest, response)
        }
      }
      baseRequest.handled = true
    }

    private static void awaitTimeoutTask(HttpServletRequest request) {
      AsyncContextState asyncContext = request.asyncContext as AsyncContextState
      AsyncContextEvent event = asyncContext.httpChannelState.asyncContextEvent
      long deadline = System.nanoTime() + TIMEOUT_TASK_WAIT
      while (TIMEOUT_TASK.get(event) == null) {
        if (System.nanoTime() >= deadline) {
          throw new IllegalStateException('Jetty did not schedule the continuation timeout')
        }
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
      }
    }
  }

  @Override
  boolean hasHandlerSpan() {
    true
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    trace.span {
      serviceName expectedServiceName()
      operationName 'servlet.dispatch'
      resourceName 'servlet.dispatch' // should this not be a path?
      childOfPrevious()
      errored(endpoint.throwsException || endpoint == TIMEOUT_ERROR)
      tags {
        "$Tags.COMPONENT" AsyncDispatcherDecorator.DECORATE.component()
        if (endpoint == TIMEOUT || endpoint == TIMEOUT_ERROR) {
          "timeout" SERVLET_TIMEOUT
        }
        if (endpoint.throwsException) {
          "error.message" endpoint.body
          "error.type" { it == Exception.name || it == InputMismatchException.name }
          "error.stack" String
        }
        defaultTags()
      }
    }
  }
}

class JettyContinuationHandlerV0ForkedTest extends JettyContinuationHandlerTest implements TestingGenericHttpNamingConventions.ServerV0 {
}

class JettyContinuationHandlerV1ForkedTest extends JettyContinuationHandlerTest implements TestingGenericHttpNamingConventions.ServerV1 {
}
