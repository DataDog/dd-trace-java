import static datadog.trace.api.config.TraceInstrumentationConfig.JAX_RS_EXCEPTION_AS_ERROR_ENABLED

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.instrumentation.connection_error.resteasy.ResteasyClientConnectionErrorInstrumentation
import datadog.trace.instrumentation.jaxrs.ClientTracingFilter
import org.jboss.resteasy.client.jaxrs.internal.ClientConfiguration
import org.jboss.resteasy.spi.ResteasyProviderFactory

class ResteasyClientConnectionErrorsInstrumentationTest extends InstrumentationSpecification {
  def "handleError does not generate traces for non-exceptions"() {
    setup:
    if (jaxRsExceptionAsErrorEnabled != null) {
      injectSysConfig(JAX_RS_EXCEPTION_AS_ERROR_ENABLED, "$jaxRsExceptionAsErrorEnabled")
    }
    def testSpan = TEST_TRACER.buildSpan("testInstrumentation", "testSpan").start()
    def props = [(ClientTracingFilter.SPAN_PROPERTY_NAME): testSpan]

    def clientConfig = new ClientConfiguration(new ResteasyProviderFactory())
    clientConfig.setProperties(props)

    when:
    ResteasyClientConnectionErrorInstrumentation.InvokeAdvice.handleError(clientConfig, null)

    then:
    assertTraces(0) {}

    where:
    jaxRsExceptionAsErrorEnabled | isErrored
    true                         | true
    false                        | false
    null                         | true
  }

  def "handleError properly utilizes the config"() {
    setup:
    if (jaxRsExceptionAsErrorEnabled != null) {
      injectSysConfig(JAX_RS_EXCEPTION_AS_ERROR_ENABLED, "$jaxRsExceptionAsErrorEnabled")
    }
    def testSpan = TEST_TRACER.buildSpan("testInstrumentation", "testSpan").start()
    def props = [(ClientTracingFilter.SPAN_PROPERTY_NAME): testSpan]

    def clientConfig = new ClientConfiguration(new ResteasyProviderFactory())
    clientConfig.setProperties(props)

    when:
    ResteasyClientConnectionErrorInstrumentation.InvokeAdvice.handleError(clientConfig, new RuntimeException("failed"))

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
