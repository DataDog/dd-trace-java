package datadog.trace.core.datastreams

import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.IgnoreIf

import static datadog.trace.api.config.GeneralConfig.DATA_STREAMS_ENABLED

@IgnoreIf({
  jvm.isJava8Compatible()
})
class Java7DataStreamsTest extends DDCoreSpecification {
  def "tracer builds with data streams disabled"() {
    setup:
    injectSysConfig(DATA_STREAMS_ENABLED, "false")

    when:
    def tracer = tracerBuilder().build()

    then:
    noExceptionThrown()

    cleanup:
    tracer.close()
  }

  def "no PathwayContext with data streams enabled"() {
    setup:
    injectSysConfig(DATA_STREAMS_ENABLED, "true")

    when:
    def tracer = tracerBuilder().build()
    def span = tracer.buildSpan("operation").start()

    then:
    span.context().pathwayContext instanceof AgentTracer.NoopPathwayContext

    cleanup:
    tracer.close()
  }
}
