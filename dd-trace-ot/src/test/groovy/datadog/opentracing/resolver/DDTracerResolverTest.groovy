package datadog.opentracing.resolver

import datadog.opentracing.DDTracer
import datadog.trace.test.util.DDSpecification
import io.opentracing.contrib.tracerresolver.TracerResolver

import static datadog.trace.api.config.TracerConfig.TRACE_RESOLVER_ENABLED

class DDTracerResolverTest extends DDSpecification {

  def resolver = new DDTracerResolver()

  def "test resolveTracer"() {
    when:
    def tracer = TracerResolver.resolveTracer()

    then:
    tracer instanceof DDTracer

    cleanup:
    tracer.close()
  }

  def "test disable DDTracerResolver"() {
    setup:
    injectSysConfig(TRACE_RESOLVER_ENABLED, "false")

    when:
    def tracer = resolver.resolve()

    then:
    tracer == null
  }
}
