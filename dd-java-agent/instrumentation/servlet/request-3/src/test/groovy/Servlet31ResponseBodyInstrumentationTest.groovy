
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.http.StoredBodySupplier
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.instrumentation.servlet.BufferedWriterWrapper
import datadog.trace.instrumentation.servlet3.Servlet31OutputStreamWrapper
import groovy.servlet.AbstractHttpServlet
import spock.lang.Shared

import javax.servlet.ServletOutputStream
import javax.servlet.annotation.WebServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.util.function.BiFunction

class Servlet31ResponseBodyInstrumentationTest extends TomcatServlet3Test {

  @Shared
  CallbackProvider ig

  def setupSpec() {
    // Set up AppSec callbacks to enable response body instrumentation
    ig = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC)
    Events<Object> events = Events.get()

    // Register minimal callbacks needed for response body instrumentation
    ig.registerCallback(events.responseBodyStart(), { RequestContext ctx, StoredBodySupplier supplier ->
      // Simple callback that just acknowledges the response body start
      return null
    } as BiFunction<RequestContext, StoredBodySupplier, Void>)

    ig.registerCallback(events.responseBodyDone(), { RequestContext ctx, StoredBodySupplier supplier ->
      // Simple callback that just acknowledges the response body end
      return datadog.trace.api.gateway.Flow.ResultFlow.empty()
    } as BiFunction<RequestContext, StoredBodySupplier, datadog.trace.api.gateway.Flow<Void>>)
  }

  def cleanupSpec() {
    if (ig != null) {
      ig.reset()
    }
  }

  @Override
  Class servlet() {
    ResponseBodyTestServlet
  }

  @Override
  String getContext() {
    return "response-body-test"
  }

  def "test getOutputStream is wrapped with Servlet31OutputStreamWrapper"() {
    setup:
    def request = request(HttpServerTest.ServerEndpoint.SUCCESS, "GET", null).build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == "output-stream-response"

    and:
    // Verify that the instrumentation was applied by checking for the wrapper header
    response.header("X-Stream-Wrapped") == "true"
  }

  def "test getWriter is wrapped with BufferedWriterWrapper"() {
    setup:
    def request = request(HttpServerTest.ServerEndpoint.FORWARDED, "GET", null).build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == "writer-response"

    and:
    // Verify that the instrumentation was applied by checking for the wrapper header
    response.header("X-Writer-Wrapped") == "true"
  }

  def "test both getOutputStream and getWriter handling"() {
    setup:
    def request = request(HttpServerTest.ServerEndpoint.QUERY_PARAM, "GET", null).build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == "both-response"

    and:
    // Only one should be wrapped since they're mutually exclusive in servlet spec
    (response.header("X-Stream-Wrapped") == "true") || (response.header("X-Writer-Wrapped") == "true")
  }

  def "test response body instrumentation with content-length header"() {
    setup:
    def request = request(HttpServerTest.ServerEndpoint.CREATED, "GET", null).build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 201
    response.body().string() == "content-length-response"
    response.header("Content-Length") == "23"
    response.header("X-Stream-Wrapped") == "true"
  }

  def "test response body instrumentation with character encoding"() {
    setup:
    def request = request(HttpServerTest.ServerEndpoint.REDIRECT, "GET", null).build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 302
    response.header("X-Writer-Wrapped") == "true"
    response.header("Content-Type").contains("UTF-8")
  }

  def "test response body instrumentation does not wrap when callbacks are not available"() {
    setup:
    // Temporarily reset callbacks to test the negative case
    ig.reset()
    def request = request(HttpServerTest.ServerEndpoint.SUCCESS, "GET", null).build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == "output-stream-response"

    and:
    // Verify that the instrumentation was NOT applied
    response.header("X-Stream-Wrapped") == null

    cleanup:
    // Restore callbacks for other tests
    setupAppSecCallbacks()
  }

  private void setupAppSecCallbacks() {
    Events<Object> events = Events.get()
    ig.registerCallback(events.responseBodyStart(), { RequestContext ctx, StoredBodySupplier supplier ->
      return null
    } as BiFunction<RequestContext, StoredBodySupplier, Void>)

    ig.registerCallback(events.responseBodyDone(), { RequestContext ctx, StoredBodySupplier supplier ->
      return datadog.trace.api.gateway.Flow.ResultFlow.empty()
    } as BiFunction<RequestContext, StoredBodySupplier, datadog.trace.api.gateway.Flow<Void>>)
  }

  @WebServlet
  static class ResponseBodyTestServlet extends AbstractHttpServlet {
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) {
      String path = req.getPathInfo() ?: req.getServletPath()

      resp.addHeader(HttpServerTest.IG_RESPONSE_HEADER, HttpServerTest.IG_RESPONSE_HEADER_VALUE)

      switch (path) {
        case "/success":
          testOutputStream(resp, "output-stream-response")
          break
        case "/forwarded":
          testWriter(resp, "writer-response")
          break
        case "/query":
          testBothOutputStreamAndWriter(resp, "both-response")
          break
        case "/created":
          testOutputStreamWithContentLength(resp, "content-length-response")
          break
        case "/redirect":
          testWriterWithEncoding(resp)
          break
        default:
          resp.setStatus(404)
          resp.getWriter().print("Not Found")
      }
    }

    private void testOutputStream(HttpServletResponse resp, String content) {
      resp.setStatus(200)
      resp.setContentType("text/plain")

      ServletOutputStream os = resp.getOutputStream()

      // Check if the output stream was wrapped
      if (os instanceof Servlet31OutputStreamWrapper) {
        resp.setHeader("X-Stream-Wrapped", "true")
      }

      os.write(content.getBytes())
    }

    private void testWriter(HttpServletResponse resp, String content) {
      resp.setStatus(200)
      resp.setContentType("text/plain")

      PrintWriter writer = resp.getWriter()

      // Check if the writer was wrapped
      if (writer instanceof BufferedWriterWrapper) {
        resp.setHeader("X-Writer-Wrapped", "true")
      }

      writer.print(content)
    }

    private void testBothOutputStreamAndWriter(HttpServletResponse resp, String content) {
      resp.setStatus(200)
      resp.setContentType("text/plain")

      try {
        // Try to get output stream first
        ServletOutputStream os = resp.getOutputStream()
        if (os instanceof Servlet31OutputStreamWrapper) {
          resp.setHeader("X-Stream-Wrapped", "true")
        }
        os.write(content.getBytes())
      } catch (IllegalStateException e) {
        // If output stream fails, try writer
        PrintWriter writer = resp.getWriter()
        if (writer instanceof BufferedWriterWrapper) {
          resp.setHeader("X-Writer-Wrapped", "true")
        }
        writer.print(content)
      }
    }

    private void testOutputStreamWithContentLength(HttpServletResponse resp, String content) {
      resp.setStatus(201)
      resp.setContentType("text/plain")
      resp.setContentLength(content.length())

      ServletOutputStream os = resp.getOutputStream()

      if (os instanceof Servlet31OutputStreamWrapper) {
        resp.setHeader("X-Stream-Wrapped", "true")
      }

      os.write(content.getBytes())
    }

    private void testWriterWithEncoding(HttpServletResponse resp) {
      resp.setStatus(302)
      resp.setContentType("text/plain; charset=UTF-8")
      resp.setCharacterEncoding("UTF-8")
      resp.setHeader("Location", "/redirected")

      PrintWriter writer = resp.getWriter()

      if (writer instanceof BufferedWriterWrapper) {
        resp.setHeader("X-Writer-Wrapped", "true")
      }

      writer.print("Redirecting...")
    }
  }
}
