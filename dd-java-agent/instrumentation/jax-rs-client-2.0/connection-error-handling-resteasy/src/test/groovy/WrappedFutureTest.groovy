import static datadog.trace.api.config.TraceInstrumentationConfig.JAX_RS_EXCEPTION_AS_ERROR_ENABLED

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.instrumentation.connection_error.resteasy.WrappedFuture
import datadog.trace.instrumentation.jaxrs.ClientTracingFilter
import java.util.concurrent.CompletableFuture
import org.jboss.resteasy.client.jaxrs.internal.ClientConfiguration
import org.jboss.resteasy.spi.ResteasyProviderFactory

class WrappedFutureTest extends InstrumentationSpecification {
  def "wrappedFuture does not generate traces for non-exceptions"() {
    setup:
    if (jaxRsExceptionAsErrorEnabled != null) {
      injectSysConfig(JAX_RS_EXCEPTION_AS_ERROR_ENABLED, "$jaxRsExceptionAsErrorEnabled")
    }
    def testSpan = TEST_TRACER.buildSpan("testInstrumentation", "testSpan").start()
    def props = [(ClientTracingFilter.SPAN_PROPERTY_NAME): testSpan]

    def completedFuture = CompletableFuture.completedFuture("passed")

    def clientConfig = new ClientConfiguration(new ResteasyProviderFactory())
    clientConfig.setProperties(props)
    def wrappedFuture = new WrappedFuture(completedFuture, clientConfig)

    when:
    try {
      wrappedFuture.get()
    } catch (ignored) {
    }

    then:
    assertTraces(0) {}

    where:
    jaxRsExceptionAsErrorEnabled | isErrored
    true                         | true
    false                        | false
    null                         | true
  }

  def "wrappedFuture handleProcessingException properly utilizes the config"() {
    setup:
    if (jaxRsExceptionAsErrorEnabled != null) {
      injectSysConfig(JAX_RS_EXCEPTION_AS_ERROR_ENABLED, "$jaxRsExceptionAsErrorEnabled")
    }
    def testSpan = TEST_TRACER.buildSpan("testInstrumentation", "testSpan").start()
    def props = [(ClientTracingFilter.SPAN_PROPERTY_NAME): testSpan]

    def future = new CompletableFuture()
    future.completeExceptionally(new RuntimeException("failed"))

    def clientConfig = new ClientConfiguration(new ResteasyProviderFactory())
    clientConfig.setProperties(props)
    def wrappedFuture = new WrappedFuture(future, clientConfig)

    when:
    try {
      wrappedFuture.get()
    } catch (ignored) {
    }

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
