import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.rum.RumInjector
import datadog.trace.api.rum.RumTelemetryCollector
import datadog.trace.bootstrap.instrumentation.buffer.InjectingPipeOutputStream
import datadog.trace.bootstrap.instrumentation.buffer.InjectingPipeWriter
import datadog.trace.instrumentation.servlet3.RumHttpServletResponseWrapper
import datadog.trace.instrumentation.servlet3.WrappedServletOutputStream
import spock.lang.Subject

import java.util.function.LongConsumer
import javax.servlet.ServletContext
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class RumHttpServletResponseWrapperTest extends InstrumentationSpecification {
  private static final String SERVLET_VERSION = "3"

  def mockRequest = Mock(HttpServletRequest)
  def mockResponse = Mock(HttpServletResponse)
  def mockServletContext = Mock(ServletContext)
  def mockTelemetryCollector = Mock(RumTelemetryCollector)

  @Subject
  RumHttpServletResponseWrapper wrapper

  void setup() {
    mockRequest.getServletContext() >> mockServletContext
    mockServletContext.getEffectiveMajorVersion() >> Integer.parseInt(SERVLET_VERSION)
    wrapper = new RumHttpServletResponseWrapper(mockRequest, mockResponse)
    RumInjector.setTelemetryCollector(mockTelemetryCollector)
  }

  void cleanup() {
    RumInjector.setTelemetryCollector(RumTelemetryCollector.NO_OP)
  }

  void 'onInjected calls telemetry collector onInjectionSucceed'() {
    when:
    wrapper.onInjected()

    then:
    1 * mockTelemetryCollector.onInjectionSucceed(SERVLET_VERSION)
  }

  void 'getOutputStream with non-HTML content reports skipped'() {
    setup:
    wrapper.setContentType("text/plain")

    when:
    wrapper.getOutputStream()

    then:
    1 * mockTelemetryCollector.onInjectionSkipped(SERVLET_VERSION)
    1 * mockResponse.getOutputStream()
  }

  void 'getWriter with non-HTML content reports skipped (setContentType)'() {
    when:
    wrapper.setContentType("text/plain")
    wrapper.getWriter()

    then:
    1 * mockTelemetryCollector.onInjectionSkipped(SERVLET_VERSION)
    1 * mockResponse.setContentType("text/plain")
    1 * mockResponse.getWriter()
  }

  void 'getWriter with non-HTML content reports skipped (setHeader)'() {
    when:
    wrapper.setHeader("Content-Type", "text/plain")
    wrapper.getWriter()

    then:
    1 * mockTelemetryCollector.onInjectionSkipped(SERVLET_VERSION)
    1 * mockResponse.setHeader("Content-Type", "text/plain")
    1 * mockResponse.getWriter()
  }

  void 'getWriter with non-HTML content reports skipped (addHeader)'() {
    when:
    wrapper.addHeader("Content-Type", "text/plain")
    wrapper.getWriter()

    then:
    1 * mockTelemetryCollector.onInjectionSkipped(SERVLET_VERSION)
    1 * mockResponse.addHeader("Content-Type", "text/plain")
    1 * mockResponse.getWriter()
  }

  void 'getOutputStream exception reports failure'() {
    setup:
    wrapper.setContentType("text/html")
    mockResponse.getOutputStream() >> { throw new IOException("stream error") }

    when:
    try {
      wrapper.getOutputStream()
    } catch (IOException ignored) {}

    then:
    1 * mockTelemetryCollector.onInjectionFailed(SERVLET_VERSION, null)
  }

  void 'getWriter exception reports failure'() {
    setup:
    wrapper.setContentType("text/html")
    mockResponse.getWriter() >> { throw new IOException("writer error") }

    when:
    try {
      wrapper.getWriter()
    } catch (IOException ignored) {}

    then:
    1 * mockTelemetryCollector.onInjectionFailed(SERVLET_VERSION, null)
  }

  void 'setHeader with Content-Security-Policy reports CSP detected'() {
    when:
    wrapper.setHeader("Content-Security-Policy", "test")

    then:
    1 * mockTelemetryCollector.onContentSecurityPolicyDetected(SERVLET_VERSION)
    1 * mockResponse.setHeader("Content-Security-Policy", "test")
  }

  void 'addHeader with Content-Security-Policy reports CSP detected'() {
    when:
    wrapper.addHeader("Content-Security-Policy", "test")

    then:
    1 * mockTelemetryCollector.onContentSecurityPolicyDetected(SERVLET_VERSION)
    1 * mockResponse.addHeader("Content-Security-Policy", "test")
  }

  void 'setHeader with non-CSP header does not report CSP detected'() {
    when:
    wrapper.setHeader("X-Content-Security-Policy", "test")

    then:
    0 * mockTelemetryCollector.onContentSecurityPolicyDetected(SERVLET_VERSION)
    1 * mockResponse.setHeader("X-Content-Security-Policy", "test")
  }

  void 'addHeader with non-CSP header does not report CSP detected'() {
    when:
    wrapper.addHeader("X-Content-Security-Policy", "test")

    then:
    0 * mockTelemetryCollector.onContentSecurityPolicyDetected(SERVLET_VERSION)
    1 * mockResponse.addHeader("X-Content-Security-Policy", "test")
  }

  void 'setCharacterEncoding reports the content-encoding tag with value when injection fails'() {
    setup:
    wrapper.setContentType("text/html")
    wrapper.setCharacterEncoding("UTF-8")
    mockResponse.getOutputStream() >> { throw new IOException("stream error") }

    when:
    try {
      wrapper.getOutputStream()
    } catch (IOException ignored) {}

    then:
    1 * mockTelemetryCollector.onInjectionFailed(SERVLET_VERSION, "UTF-8")
  }

  void 'setCharacterEncoding reports the content-encoding tag with null when injection fails'() {
    setup:
    wrapper.setContentType("text/html")
    wrapper.setCharacterEncoding(null)
    mockResponse.getOutputStream() >> { throw new IOException("stream error") }

    when:
    try {
      wrapper.getOutputStream()
    } catch (IOException ignored) {}

    then:
    1 * mockTelemetryCollector.onInjectionFailed(SERVLET_VERSION, null)
  }

  void 'flushBuffer drains and resets a partial marker'() {
    setup:
    def downstream = new StringWriter()
    def writer = attachWriter(downstream)

    when:
    writer.write("</he")
    wrapper.flushBuffer()

    then:
    downstream.toString() == "</he"
    1 * mockResponse.flushBuffer()

    when:
    writer.write("ad>")
    wrapper.commit()

    then:
    downstream.toString() == "</head>"
    0 * mockTelemetryCollector.onInjectionSucceed(_)
  }

  void 'resetBuffer discards content and re-arms injection'() {
    setup:
    def downstream = new StringWriter()
    def writer = attachWriter(downstream)

    when:
    writer.write("</he")
    wrapper.resetBuffer()
    writer.write("</head>")
    wrapper.commit()

    then:
    !downstream.toString().startsWith("</he")
    downstream.toString() == "<script></script></head>"
    1 * mockResponse.resetBuffer()
    1 * mockTelemetryCollector.onInjectionSucceed(SERVLET_VERSION)
  }

  void 'reset retires the old writer and restores injection for a new writer'() {
    setup:
    def downstream = new StringWriter()
    def oldWriter = attachWriter(downstream)

    when:
    oldWriter.write("</he")
    wrapper.reset()
    attachWriter(downstream).write("</head>")
    wrapper.commit()

    then:
    !downstream.toString().startsWith("</he")
    downstream.toString() == "<script></script></head>"
    1 * mockResponse.reset()
    1 * mockTelemetryCollector.onInjectionSucceed(SERVLET_VERSION)
  }

  void 'reset does not reactivate a retired nested wrapper'() {
    setup:
    wrapper.stopFiltering()
    def outerWrapper = new RumHttpServletResponseWrapper(mockRequest, wrapper)

    when:
    outerWrapper.reset()

    then:
    !wrapper.@shouldInject
    outerWrapper.@shouldInject
    1 * mockResponse.reset()
  }

  void 'sendError discards buffered content and stops filtering'() {
    setup:
    def downstream = new StringWriter()
    def writer = attachWriter(downstream)

    when:
    writer.write("</he")
    wrapper.sendError(500)
    writer.write("ad>")
    wrapper.commit()

    then:
    downstream.toString() == "ad>"
    1 * mockResponse.sendError(500)
    0 * mockTelemetryCollector.onInjectionSucceed(_)
  }

  void 'sendError discards buffered content when the delegate throws'() {
    setup:
    def downstream = new StringWriter()
    attachWriter(downstream).write("</he")

    when:
    wrapper.sendError(500)

    then:
    thrown(IOException)
    1 * mockResponse.sendError(500) >> { throw new IOException("error response failed") }

    when:
    wrapper.commit()

    then:
    downstream.toString().isEmpty()
  }

  void 'sendError preserves buffered content when the delegate rejects the call'() {
    setup:
    def downstream = new StringWriter()
    attachWriter(downstream).write("</he")

    when:
    wrapper.sendError(500)

    then:
    thrown(IllegalStateException)
    1 * mockResponse.sendError(500) >> { throw new IllegalStateException("already committed") }

    when:
    wrapper.commit()

    then:
    downstream.toString() == "</he"
  }

  void 'sendRedirect discards buffered content and stops filtering'() {
    setup:
    def downstream = new StringWriter()
    def writer = attachWriter(downstream)

    when:
    writer.write("</he")
    wrapper.sendRedirect("/other")
    writer.write("ad>")
    wrapper.commit()

    then:
    downstream.toString() == "ad>"
    1 * mockResponse.sendRedirect("/other")
    0 * mockTelemetryCollector.onInjectionSucceed(_)
  }

  void 'sendRedirect discards buffered content when the delegate throws'() {
    setup:
    def downstream = new StringWriter()
    attachWriter(downstream).write("</he")

    when:
    wrapper.sendRedirect("/other")

    then:
    thrown(IOException)
    1 * mockResponse.sendRedirect("/other") >> { throw new IOException("redirect failed") }

    when:
    wrapper.commit()

    then:
    downstream.toString().isEmpty()
  }

  private PrintWriter attachWriter(StringWriter downstream) {
    def pipe = new InjectingPipeWriter(
      downstream,
      "</head>".toCharArray(),
      "<script></script>".toCharArray(),
      wrapper.&onInjected,
      null,
      null)
    def writer = new PrintWriter(pipe)
    wrapper.@wrappedPipeWriter = pipe
    wrapper.@printWriter = writer
    return writer
  }

  // Callback is created in the RumHttpServletResponseWrapper and passed to InjectingPipeOutputStream via WrappedServletOutputStream.
  // When the stream is closed, the callback is called with the number of bytes written to the stream and the time taken to write the injection content.
  void 'response sizes are reported to the telemetry collector via the WrappedServletOutputStream callback'() {
    setup:
    def downstream = Mock(javax.servlet.ServletOutputStream)
    def marker = "</head>".getBytes("UTF-8")
    def contentToInject = "<script></script>".getBytes("UTF-8")
    def onBytesWritten = { bytes ->
      mockTelemetryCollector.onInjectionResponseSize(SERVLET_VERSION, bytes)
    }
    def wrappedStream = new WrappedServletOutputStream(
      downstream, marker, contentToInject, null, onBytesWritten, null)
    def testBytes = "test content"

    when:
    wrappedStream.write(testBytes[0..5].getBytes("UTF-8"))
    wrappedStream.write(testBytes[6..-1].getBytes("UTF-8"))
    wrappedStream.close()

    then:
    1 * mockTelemetryCollector.onInjectionResponseSize(SERVLET_VERSION, testBytes.length())
  }

  void 'response sizes are reported by the InjectingPipeOutputStream callback'() {
    setup:
    def downstream = Mock(java.io.OutputStream)
    def marker = "</head>".getBytes("UTF-8")
    def contentToInject = "<script></script>".getBytes("UTF-8")
    def onBytesWritten = Mock(LongConsumer)
    def stream = new InjectingPipeOutputStream(
      downstream, marker, contentToInject, null, onBytesWritten, null)
    def testBytes = "test content"

    when:
    stream.write(testBytes[0..5].getBytes("UTF-8"))
    stream.write(testBytes[6..-1].getBytes("UTF-8"))
    stream.close()

    then:
    1 * onBytesWritten.accept(testBytes.length())
  }

  void 'response sizes are reported by the InjectingPipeWriter callback'() {
    setup:
    def downstream = Mock(java.io.Writer)
    def marker = "</head>".toCharArray()
    def contentToInject = "<script></script>".toCharArray()
    def onBytesWritten = Mock(LongConsumer)
    def writer = new InjectingPipeWriter(
      downstream, marker, contentToInject, null, onBytesWritten, null)
    def testBytes = "test content"

    when:
    writer.write(testBytes[0..5].toCharArray())
    writer.write(testBytes[6..-1].toCharArray())
    writer.close()

    then:
    1 * onBytesWritten.accept(testBytes.length())
  }

  void 'injection timing is reported by the InjectingPipeOutputStream callback'() {
    setup:
    def downstream = Mock(java.io.OutputStream) {
      write(_) >> { args ->
        Thread.sleep(1) // simulate slow write
      }
    }
    def marker = "</head>".getBytes("UTF-8")
    def contentToInject = "<script></script>".getBytes("UTF-8")
    def onInjectionTime = Mock(LongConsumer)
    def stream = new InjectingPipeOutputStream(
      downstream, marker, contentToInject, null, null, onInjectionTime)

    when:
    stream.write("<html><head></head><body>content</body></html>".getBytes("UTF-8"))
    stream.close()

    then:
    1 * onInjectionTime.accept({ it > 0 })
  }

  void 'injection timing is reported by the InjectingPipeWriter callback'() {
    setup:
    def downstream = Mock(java.io.Writer) {
      write(_) >> { args ->
        Thread.sleep(1) // simulate slow write
      }
    }
    def marker = "</head>".toCharArray()
    def contentToInject = "<script></script>".toCharArray()
    def onInjectionTime = Mock(LongConsumer)
    def writer = new InjectingPipeWriter(
      downstream, marker, contentToInject, null, null, onInjectionTime)

    when:
    writer.write("<html><head></head><body>content</body></html>".toCharArray())
    writer.close()

    then:
    1 * onInjectionTime.accept({ it > 0 })
  }
}
