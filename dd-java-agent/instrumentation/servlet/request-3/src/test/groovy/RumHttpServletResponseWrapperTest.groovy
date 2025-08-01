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
}
