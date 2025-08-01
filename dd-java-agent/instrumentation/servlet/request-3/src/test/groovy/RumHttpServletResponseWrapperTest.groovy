import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.rum.RumInjector
import datadog.trace.api.rum.RumTelemetryCollector
import datadog.trace.instrumentation.servlet3.RumHttpServletResponseWrapper
import spock.lang.Subject

import javax.servlet.http.HttpServletResponse

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
    1 * mockTelemetryCollector.onInjectionSucceed()
  }

  void 'getOutputStream with non-HTML content reports skipped'() {
    setup:
    wrapper.setContentType("text/plain")

    when:
    wrapper.getOutputStream()

    then:
    1 * mockTelemetryCollector.onInjectionSkipped()
    1 * mockResponse.getOutputStream()
  }

  void 'getWriter with non-HTML content reports skipped'() {
    setup:
    wrapper.setContentType("text/plain")

    when:
    wrapper.getWriter()

    then:
    1 * mockTelemetryCollector.onInjectionSkipped()
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
    1 * mockTelemetryCollector.onInjectionFailed()
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
    1 * mockTelemetryCollector.onInjectionFailed()
  }

  void 'setHeader with Content-Security-Policy reports CSP detected'() {
    when:
    wrapper.setHeader("Content-Security-Policy", "test")

    then:
    1 * mockTelemetryCollector.onContentSecurityPolicyDetected()
    1 * mockResponse.setHeader("Content-Security-Policy", "test")
  }

  void 'addHeader with Content-Security-Policy-Report-Only reports CSP detected'() {
    when:
    wrapper.addHeader("Content-Security-Policy-Report-Only", "test")

    then:
    1 * mockTelemetryCollector.onContentSecurityPolicyDetected()
    1 * mockResponse.addHeader("Content-Security-Policy-Report-Only", "test")
  }

  void 'setHeader with non-CSP header does not report CSP detected'() {
    when:
    wrapper.setHeader("X-Content-Security-Policy", "test")

    then:
    0 * mockTelemetryCollector.onContentSecurityPolicyDetected()
    1 * mockResponse.setHeader("X-Content-Security-Policy", "test")
  }

  void 'addHeader with non-CSP header does not report CSP detected'() {
    when:
    wrapper.addHeader("X-Content-Security-Policy", "test")

    then:
    0 * mockTelemetryCollector.onContentSecurityPolicyDetected()
    1 * mockResponse.addHeader("X-Content-Security-Policy", "test")
  }

  void 'setHeader with Content-Length reports response size'() {
    when:
    wrapper.setHeader("Content-Length", "1024")

    then:
    1 * mockTelemetryCollector.onInjectionResponseSize(1024)
    1 * mockResponse.setHeader("Content-Length", "1024")
  }

  void 'setContentLength method reports response size'() {
    when:
    wrapper.setContentLength(1024)

    then:
    1 * mockTelemetryCollector.onInjectionResponseSize(1024)
  }
}
