import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.rum.RumInjector
import datadog.trace.api.rum.RumTelemetryCollector
import datadog.trace.bootstrap.instrumentation.buffer.InjectingPipeOutputStream
import datadog.trace.bootstrap.instrumentation.buffer.InjectingPipeWriter
import datadog.trace.instrumentation.servlet5.RumHttpServletResponseWrapper
import datadog.trace.instrumentation.servlet5.WrappedServletOutputStream
import spock.lang.Subject

import java.util.function.LongConsumer
import jakarta.servlet.http.HttpServletResponse

class RumHttpServletResponseWrapperTest extends AgentTestRunner {
  private static final String SERVLET_VERSION = "5"

  def mockResponse = Mock(HttpServletResponse)
  def mockTelemetryCollector = Mock(RumTelemetryCollector)

  // injector needs to be enabled in order to check headers
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("rum.enabled", "true")
    injectSysConfig("rum.application.id", "test")
    injectSysConfig("rum.client.token", "secret")
    injectSysConfig("rum.remote.configuration.id", "12345")
  }

  @Subject
  RumHttpServletResponseWrapper wrapper

  void setup() {
    wrapper = new RumHttpServletResponseWrapper(mockResponse)
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

  void 'getWriter with non-HTML content reports skipped'() {
    setup:
    wrapper.setContentType("text/plain")

    when:
    wrapper.getWriter()

    then:
    1 * mockTelemetryCollector.onInjectionSkipped(SERVLET_VERSION)
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

  // Callback is created in the RumHttpServletResponseWrapper and passed to InjectingPipeOutputStream via WrappedServletOutputStream.
  // When the stream is closed, the callback is called with the total number of bytes written to the stream.
  void 'response sizes are reported to the telemetry collector via the WrappedServletOutputStream callback'() {
    setup:
    def downstream = Mock(jakarta.servlet.ServletOutputStream)
    def marker = "</head>".getBytes("UTF-8")
    def contentToInject = "<script></script>".getBytes("UTF-8")
    def onBytesWritten = { bytes ->
      mockTelemetryCollector.onInjectionResponseSize(SERVLET_VERSION, bytes)
    }
    def wrappedStream = new WrappedServletOutputStream(
      downstream, marker, contentToInject, null, onBytesWritten)

    when:
    wrappedStream.write("test".getBytes("UTF-8"))
    wrappedStream.write("content".getBytes("UTF-8"))
    wrappedStream.close()

    then:
    1 * mockTelemetryCollector.onInjectionResponseSize(SERVLET_VERSION, 11)
  }

  void 'response sizes are reported by the InjectingPipeOutputStream callback'() {
    setup:
    def downstream = Mock(java.io.OutputStream)
    def marker = "</head>".getBytes("UTF-8")
    def contentToInject = "<script></script>".getBytes("UTF-8")
    def onBytesWritten = Mock(LongConsumer)
    def stream = new InjectingPipeOutputStream(
      downstream, marker, contentToInject, null, onBytesWritten)

    when:
    stream.write("test".getBytes("UTF-8"))
    stream.write("content".getBytes("UTF-8"))
    stream.close()

    then:
    1 * onBytesWritten.accept(11)
  }

  void 'response sizes are reported by the InjectingPipeWriter callback'() {
    setup:
    def downstream = Mock(java.io.Writer)
    def marker = "</head>".toCharArray()
    def contentToInject = "<script></script>".toCharArray()
    def onBytesWritten = Mock(LongConsumer)
    def writer = new InjectingPipeWriter(
      downstream, marker, contentToInject, null, onBytesWritten)

    when:
    writer.write("test".toCharArray())
    writer.write("content".toCharArray())
    writer.close()

    then:
    1 * onBytesWritten.accept(11)
  }

  void 'injection timing is reported when injection is successful'() {
    setup:
    wrapper.setContentType("text/html")
    def mockWriter = Mock(java.io.PrintWriter)
    mockResponse.getWriter() >> mockWriter

    when:
    wrapper.getWriter()
    Thread.sleep(1) // ensure measurable time passes
    wrapper.onInjected()

    then:
    1 * mockTelemetryCollector.onInjectionSucceed(SERVLET_VERSION)
    1 * mockTelemetryCollector.onInjectionTime(SERVLET_VERSION, { it > 0 })
  }
}
