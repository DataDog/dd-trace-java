import static datadog.trace.api.config.TraceInstrumentationConfig.JAX_RS_EXCEPTION_AS_ERROR_ENABLED

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.instrumentation.jaxrs.ClientTracingFilter
import javax.ws.rs.ProcessingException
import org.glassfish.jersey.client.ClientConfig
import org.glassfish.jersey.client.ClientRequest
import org.glassfish.jersey.client.JerseyClient
import org.glassfish.jersey.client.WrappingResponseCallback
import org.glassfish.jersey.internal.MapPropertiesDelegate

class WrappingResponseCallbackTest extends InstrumentationSpecification {
  def "handleProcessingException properly utilizes the config"() {
    setup:
    if (jaxRsExceptionAsErrorEnabled != null) {
      injectSysConfig(JAX_RS_EXCEPTION_AS_ERROR_ENABLED, "$jaxRsExceptionAsErrorEnabled")
    }
    def testSpan = TEST_TRACER.buildSpan("testInstrumentation", "testSpan").start()
    def props = [(ClientTracingFilter.SPAN_PROPERTY_NAME): testSpan]
    def propertiesDelegate = new MapPropertiesDelegate(props)
    def clientConfig = new ClientConfig(new JerseyClient())

    def clientRequest = new ClientRequest(new URI("https://www.google.com/"), clientConfig, propertiesDelegate)
    def processingException = new ProcessingException("test")

    when:
    WrappingResponseCallback.handleProcessingException(clientRequest, processingException)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "testSpan"
          resourceName "testSpan"
          errored isErrored
        }
      }
    }

    where:
    jaxRsExceptionAsErrorEnabled | isErrored
    true                         | true
    false                        | false
    null                         | true
  }
}
