package com.datadog.appsec.api.security

import com.datadog.appsec.event.EventProducerService
import com.datadog.appsec.event.ExpiredSubscriberInfoException
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.gateway.AppSecRequestContext
import datadog.trace.api.ProductTraceSource
import datadog.trace.api.config.AppSecConfig
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.test.util.DDSpecification

class ApiSecurityProcessorTest extends DDSpecification {

  void 'schema extracted on happy path'() {
    given:
    def sampler = Mock(ApiSecuritySampler)
    def producer = Mock(EventProducerService)
    def subInfo = Mock(EventProducerService.DataSubscriberInfo)
    def traceSegment = Mock(TraceSegment)
    def ctx = Mock(AppSecRequestContext)
    def processor = new ApiSecurityProcessor(sampler, producer)

    when:
    processor.processTraceSegment(traceSegment, ctx, null)

    then:
    noExceptionThrown()
    1 * sampler.sample(ctx) >> true
    1 * producer.getDataSubscribers(KnownAddresses.WAF_CONTEXT_PROCESSOR) >> subInfo
    1 * subInfo.isEmpty() >> false
    1 * producer.publishDataEvent(_, ctx, _, _)
    1 * traceSegment.setTagTop('asm.keep', true)
    0 * _
  }

  void 'no schema extracted if sampling is false'() {
    given:
    def sampler = Mock(ApiSecuritySampler)
    def producer = Mock(EventProducerService)
    def ctx = Mock(AppSecRequestContext)
    def traceSegment = Mock(TraceSegment)
    def processor = new ApiSecurityProcessor(sampler, producer)

    when:
    processor.processTraceSegment(traceSegment, ctx, null)

    then:
    noExceptionThrown()
    1 * sampler.sample(ctx) >> false
    0 * _
  }

  void 'process null appsec request context does nothing'() {
    given:
    def sampler = Mock(ApiSecuritySampler)
    def producer = Mock(EventProducerService)
    def traceSegment = Mock(TraceSegment)
    def processor = new ApiSecurityProcessor(sampler, producer)

    when:
    processor.processTraceSegment(traceSegment, null, null)

    then:
    noExceptionThrown()
    0 * _
  }

  void 'empty event subscription does not break the process'() {
    given:
    def sampler = Mock(ApiSecuritySampler)
    def producer = Mock(EventProducerService)
    def subInfo = Mock(EventProducerService.DataSubscriberInfo)
    def traceSegment = Mock(TraceSegment)
    def ctx = Mock(AppSecRequestContext)
    def processor = new ApiSecurityProcessor(sampler, producer)

    when:
    processor.processTraceSegment(traceSegment, ctx, null)

    then:
    noExceptionThrown()
    1 * sampler.sample(ctx) >> true
    1 * producer.getDataSubscribers(_) >> subInfo
    1 * subInfo.isEmpty() >> true
    0 * _
  }

  void 'expired event subscription does not break the process'() {
    given:
    def sampler = Mock(ApiSecuritySampler)
    def producer = Mock(EventProducerService)
    def subInfo = Mock(EventProducerService.DataSubscriberInfo)
    def traceSegment = Mock(TraceSegment)
    def ctx = Mock(AppSecRequestContext)
    def processor = new ApiSecurityProcessor(sampler, producer)

    when:
    processor.processTraceSegment(traceSegment, ctx, null)

    then:
    noExceptionThrown()
    1 * sampler.sample(ctx) >> true
    1 * producer.getDataSubscribers(_) >> subInfo
    1 * subInfo.isEmpty() >> false
    1 * producer.publishDataEvent(_, ctx, _, _) >> { throw new ExpiredSubscriberInfoException() }
    0 * _
  }

  void 'test api security sampling with tracing disabled'() {
    given:
    injectSysConfig(GeneralConfig.APM_TRACING_ENABLED, "false")
    injectSysConfig(AppSecConfig.API_SECURITY_ENABLED, "true")
    def sampler = Mock(ApiSecuritySampler)
    def subInfo = Mock(EventProducerService.DataSubscriberInfo)
    def producer = Mock(EventProducerService)
    def traceSegment = Mock(TraceSegment)
    def processor = new ApiSecurityProcessor(sampler, producer)
    def ctx = Mock(AppSecRequestContext)

    when:
    processor.processTraceSegment(traceSegment, ctx, null)

    then:
    1 * sampler.sample(ctx) >> true
    1 * producer.getDataSubscribers(KnownAddresses.WAF_CONTEXT_PROCESSOR) >> subInfo
    1 * subInfo.isEmpty() >> false
    1 * producer.publishDataEvent(_, ctx, _, _)
    1 * traceSegment.setTagTop('asm.keep', true)
    1 * traceSegment.setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM)
    0 * _
  }
}
