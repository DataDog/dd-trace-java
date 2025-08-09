import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.rum.RumInjector
import datadog.trace.api.rum.RumTelemetryCollector
import datadog.trace.instrumentation.servlet5.RumHttpServletResponseWrapper
import spock.lang.Subject

import jakarta.servlet.http.HttpServletResponse

class RumHttpServletResponseWrapperTest extends AgentTestRunner {

  def mockResponse = Mock(HttpServletResponse)
  def mockTelemetryCollector = Mock(RumTelemetryCollector)

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
    1 * mockTelemetryCollector.onInjectionSucceed("5")
  }

  void 'getOutputStream with non-HTML content reports skipped'() {
    setup:
    wrapper.setContentType("text/plain")

    when:
    wrapper.getOutputStream()

    then:
    1 * mockTelemetryCollector.onInjectionSkipped("5")
    1 * mockResponse.getOutputStream()
  }

  void 'getWriter with non-HTML content reports skipped'() {
    setup:
    wrapper.setContentType("text/plain")

    when:
    wrapper.getWriter()

    then:
    1 * mockTelemetryCollector.onInjectionSkipped("5")
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
    1 * mockTelemetryCollector.onInjectionFailed("5", "none")
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
    1 * mockTelemetryCollector.onInjectionFailed("5", "none")
  }

  void 'setHeader with Content-Security-Policy reports CSP detected'() {
    when:
    wrapper.setHeader("Content-Security-Policy", "test")

    then:
    1 * mockTelemetryCollector.onContentSecurityPolicyDetected("5")
    1 * mockResponse.setHeader("Content-Security-Policy", "test")
  }

  void 'addHeader with Content-Security-Policy reports CSP detected'() {
    when:
    wrapper.addHeader("Content-Security-Policy", "test")

    then:
    1 * mockTelemetryCollector.onContentSecurityPolicyDetected("5")
    1 * mockResponse.addHeader("Content-Security-Policy", "test")
  }

  void 'setHeader with non-CSP header does not report CSP detected'() {
    when:
    wrapper.setHeader("X-Content-Security-Policy", "test")

    then:
    0 * mockTelemetryCollector.onContentSecurityPolicyDetected("5")
    1 * mockResponse.setHeader("X-Content-Security-Policy", "test")
  }

  void 'addHeader with non-CSP header does not report CSP detected'() {
    when:
    wrapper.addHeader("X-Content-Security-Policy", "test")

    then:
    0 * mockTelemetryCollector.onContentSecurityPolicyDetected("5")
    1 * mockResponse.addHeader("X-Content-Security-Policy", "test")
  }

  // Callback is created in the RumHttpServletResponseWrapper and passed to InjectingPipeOutputStream via WrappedServletOutputStream.
  // When the stream is closed, the callback is called with the total number of bytes written to the stream.
  void 'response sizes are reported to the telemetry collector via the WrappedServletOutputStream callback'() {
    setup:
    def downstream = Mock(jakarta.servlet.ServletOutputStream)
    def marker = "</head>".getBytes("UTF-8")
    def contentToInject = "<script></script>".getBytes("UTF-8")
    def onBytesWritten = { bytes ->
      mockTelemetryCollector.onInjectionResponseSize("5", bytes)
    }
    def wrappedStream = new datadog.trace.instrumentation.servlet5.WrappedServletOutputStream(
      downstream, marker, contentToInject, null, onBytesWritten)

    when:
    wrappedStream.write("test".getBytes("UTF-8"))
    wrappedStream.write("content".getBytes("UTF-8"))
    wrappedStream.close()

    then:
    1 * mockTelemetryCollector.onInjectionResponseSize("5", 11)
  }

  void 'response sizes are reported by the InjectingPipeOutputStream callback'() {
    setup:
    def downstream = Mock(java.io.OutputStream)
    def marker = "</head>".getBytes("UTF-8")
    def contentToInject = "<script></script>".getBytes("UTF-8")
    def onBytesWritten = Mock(java.util.function.LongConsumer)
    def stream = new datadog.trace.bootstrap.instrumentation.buffer.InjectingPipeOutputStream(
      downstream, marker, contentToInject, null, onBytesWritten)

    when:
    stream.write("test".getBytes("UTF-8"))
    stream.write("content".getBytes("UTF-8"))
    stream.close()

    then:
    1 * onBytesWritten.accept(11)
  }

  void 'injection timing is reported when injection is successful'() {
    setup:
    // set the injection start time to simulate timing
    wrapper.@injectionStartTime = System.nanoTime() - 2_000_000L

    when:
    wrapper.onInjected() // report timing when injection is successful

    then:
    1 * mockTelemetryCollector.onInjectionSucceed("5")
    1 * mockTelemetryCollector.onInjectionTime("5", { it > 0 })
  }
}
