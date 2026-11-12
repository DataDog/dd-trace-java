package datadog.trace.instrumentation.jetty9

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.servlet3.AsyncDispatcherDecorator
import okhttp3.MediaType
import okhttp3.RequestBody
import org.eclipse.jetty.continuation.Continuation
import org.eclipse.jetty.continuation.ContinuationSupport
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.server.session.SessionHandler

import javax.servlet.MultipartConfigElement
import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import static test.TestHandler.handleRequest
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR
import static datadog.trace.instrumentation.servlet3.TestServlet3.SERVLET_TIMEOUT

abstract class JettyContinuationHandlerTest extends Jetty9Test {

  def 'form requests survive continuation redispatch without HTTP retries'() {
    setup:
    def noRetryClient = client.newBuilder().retryOnConnectionFailure(false).build()
    int attempts = 100

    when:
    attempts.times {
      def formRequest = request(BODY_URLENCODED, 'POST',
      RequestBody.create(MediaType.get('application/x-www-form-urlencoded'), 'a=x')).build()
      try (def response = noRetryClient.newCall(formRequest).execute()) {
        assert response.code() == 200
        assert response.body().string() == '[a:[x]]'
      }
    }
    TEST_WRITER.waitForTraces(attempts)

    then:
    TEST_WRITER.size() == attempts
    TEST_WRITER.every {
      trace ->
      def root = trace.find {
        it.parentId == 0
      }
      trace.size() == 3 &&
      root.getTag('http.status_code') == 200 &&
      root.getTag('request.body.converted') == '[a:[x]]' &&
      trace.any {
        it.operationName == 'servlet.dispatch'
      }
    }
  }

  @Override
  AbstractHandler handler() {
    def ret = new SessionHandler()
    ret.handler = ContinuationTestHandler.INSTANCE
    ret
  }

  static class ContinuationTestHandler extends AbstractHandler {
    private static final MultipartConfigElement MULTIPART_CONFIG_ELEMENT = new MultipartConfigElement(System.getProperty('java.io.tmpdir'))
    static final ContinuationTestHandler INSTANCE = new ContinuationTestHandler()
    final ExecutorService executorService = Executors.newSingleThreadExecutor()

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
          if (request.contentType?.startsWith('application/x-www-form-urlencoded')) {
            // Parse the form before suspending the request. When the client waits for `HTTP 100 Continue`
            // before sending the body, parsing after resume can overlap with Jetty 9.0.0's pending connection read
            // and cause ReadPendingException.
            request.parameterMap
            if (response.committed) {
              baseRequest.handled = true
              return
            }
          }
          continuation.suspend()
          executorService.execute {
            continuation.resume()
          }
        } else {
          handleRequest(baseRequest, response)
        }
      }
      baseRequest.handled = true
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
