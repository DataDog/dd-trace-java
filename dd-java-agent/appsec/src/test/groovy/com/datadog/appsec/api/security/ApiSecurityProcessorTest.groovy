package com.datadog.appsec.api.security

import com.datadog.appsec.event.EventProducerService
import com.datadog.appsec.event.ExpiredSubscriberInfoException
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.gateway.AppSecRequestContext
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.test.util.DDSpecification

class ApiSecurityProcessorTest extends DDSpecification {

  void 'schema extracted on happy path'() {
    given:
    def sampler = Mock(ApiSecuritySamplerImpl)
    def producer = Mock(EventProducerService)
    def subInfo = Mock(EventProducerService.DataSubscriberInfo)
    def span = Mock(AgentSpan)
    def reqCtx = Mock(RequestContext)
    def traceSegment = Mock(TraceSegment)
    def ctx = Mock(AppSecRequestContext)
    def processor = new ApiSecurityProcessor(sampler, producer)

    when:
    processor.process(span, { false })

    then:
    noExceptionThrown()
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(_) >> ctx
    1 * ctx.isKeepOpenForApiSecurityPostProcessing() >> true
    1 * sampler.sampleRequest(_) >> true
    1 * reqCtx.getTraceSegment() >> traceSegment
    1 * producer.getDataSubscribers(KnownAddresses.WAF_CONTEXT_PROCESSOR) >> subInfo
    1 * subInfo.isEmpty() >> false
    1 * producer.publishDataEvent(_, ctx, _, _)
    1 * ctx.commitDerivatives(traceSegment)
    1 * ctx.setKeepOpenForApiSecurityPostProcessing(false)
    1 * ctx.closeWafContext()
    1 * ctx.close()
    1 * sampler.releaseOne()
    0 * _
  }

  void 'no schema extracted if sampling is false'() {
    given:
    def sampler = Mock(ApiSecuritySamplerImpl)
    def producer = Mock(EventProducerService)
    def span = Mock(AgentSpan)
    def reqCtx = Mock(RequestContext)
    def ctx = Mock(AppSecRequestContext)
    def processor = new ApiSecurityProcessor(sampler, producer)

    when:
    processor.process(span, { false })

    then:
    noExceptionThrown()
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(_) >> ctx
    1 * ctx.isKeepOpenForApiSecurityPostProcessing() >> true
    1 * sampler.sampleRequest(_) >> false
    1 * ctx.setKeepOpenForApiSecurityPostProcessing(false)
    1 * ctx.closeWafContext()
    1 * ctx.close()
    1 * sampler.releaseOne()
    0 * _
  }

  void 'permit is released even if request context close throws'() {
    given:
    def sampler = Mock(ApiSecuritySamplerImpl)
    def producer = Mock(EventProducerService)
    def span = Mock(AgentSpan)
    def reqCtx = Mock(RequestContext)
    def traceSegment = Mock(TraceSegment)
    def ctx = Mock(AppSecRequestContext)
    def processor = new ApiSecurityProcessor(sampler, producer)

    when:
    processor.process(span, { false })

    then:
    noExceptionThrown()
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(_) >> ctx
    1 * ctx.isKeepOpenForApiSecurityPostProcessing() >> true
    1 * sampler.sampleRequest(_) >> true
    1 * reqCtx.getTraceSegment() >> traceSegment
    1 * producer.getDataSubscribers(_) >> null
    1 * ctx.setKeepOpenForApiSecurityPostProcessing(false)
    1 * ctx.closeWafContext()
    1 * ctx.close() >> { throw new RuntimeException() }
    1 * sampler.releaseOne()
    0 * _
  }

  void 'context is cleaned up on timeout'() {
    given:
    def sampler = Mock(ApiSecuritySamplerImpl)
    def producer = Mock(EventProducerService)
    def span = Mock(AgentSpan)
    def reqCtx = Mock(RequestContext)
    def ctx = Mock(AppSecRequestContext)
    def processor = new ApiSecurityProcessor(sampler, producer)

    when:
    processor.process(span, { true })

    then:
    noExceptionThrown()
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(_) >> ctx
    1 * ctx.isKeepOpenForApiSecurityPostProcessing() >> true
    1 * ctx.setKeepOpenForApiSecurityPostProcessing(false)
    1 * ctx.closeWafContext()
    1 * ctx.close()
    1 * sampler.releaseOne()
    0 * _
  }

  void 'process null request context does nothing'() {
    given:
    def sampler = Mock(ApiSecuritySamplerImpl)
    def producer = Mock(EventProducerService)
    def span = Mock(AgentSpan)
    def processor = new ApiSecurityProcessor(sampler, producer)

    when:
    processor.process(span, { false })

    then:
    noExceptionThrown()
    1 * span.getRequestContext() >> null
    0 * _
  }

  void 'process null appsec request context does nothing'() {
    given:
    def sampler = Mock(ApiSecuritySamplerImpl)
    def producer = Mock(EventProducerService)
    def span = Mock(AgentSpan)
    def reqCtx = Mock(RequestContext)
    def processor = new ApiSecurityProcessor(sampler, producer)

    when:
    processor.process(span, { false })

    then:
    noExceptionThrown()
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(_) >> null
    0 * _
  }

  void 'process already closed context does nothing'() {
    given:
    def sampler = Mock(ApiSecuritySamplerImpl)
    def producer = Mock(EventProducerService)
    def span = Mock(AgentSpan)
    def reqCtx = Mock(RequestContext)
    def ctx = Mock(AppSecRequestContext)
    def processor = new ApiSecurityProcessor(sampler, producer)

    when:
    processor.process(span, { false })

    then:
    noExceptionThrown()
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(_) >> ctx
    1 * ctx.isKeepOpenForApiSecurityPostProcessing() >> false
    0 * _
  }

  void 'process throws on null span'() {
    given:
    def sampler = Mock(ApiSecuritySamplerImpl)
    def producer = Mock(EventProducerService)
    def processor = new ApiSecurityProcessor(sampler, producer)

    when:
    processor.process(null, { false })

    then:
    thrown(NullPointerException)
    0 * _
  }

  void 'empty event subscription does not break the process'() {
    given:
    def sampler = Mock(ApiSecuritySamplerImpl)
    def producer = Mock(EventProducerService)
    def subInfo = Mock(EventProducerService.DataSubscriberInfo)
    def span = Mock(AgentSpan)
    def reqCtx = Mock(RequestContext)
    def traceSegment = Mock(TraceSegment)
    def ctx = Mock(AppSecRequestContext)
    def processor = new ApiSecurityProcessor(sampler, producer)

    when:
    processor.process(span, { false })

    then:
    noExceptionThrown()
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(_) >> ctx
    1 * ctx.isKeepOpenForApiSecurityPostProcessing() >> true
    1 * sampler.sampleRequest(_) >> true
    1 * reqCtx.getTraceSegment() >> traceSegment
    1 * producer.getDataSubscribers(_) >> subInfo
    1 * subInfo.isEmpty() >> true
    1 * ctx.setKeepOpenForApiSecurityPostProcessing(false)
    1 * ctx.closeWafContext()
    1 * ctx.close()
    1 * sampler.releaseOne()
    0 * _
  }

  void 'expired event subscription does not break the process'() {
    given:
    def sampler = Mock(ApiSecuritySamplerImpl)
    def producer = Mock(EventProducerService)
    def subInfo = Mock(EventProducerService.DataSubscriberInfo)
    def span = Mock(AgentSpan)
    def reqCtx = Mock(RequestContext)
    def traceSegment = Mock(TraceSegment)
    def ctx = Mock(AppSecRequestContext)
    def processor = new ApiSecurityProcessor(sampler, producer)

    when:
    processor.process(span, { false })

    then:
    noExceptionThrown()
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(_) >> ctx
    1 * ctx.isKeepOpenForApiSecurityPostProcessing() >> true
    1 * sampler.sampleRequest(_) >> true
    1 * reqCtx.getTraceSegment() >> traceSegment
    1 * producer.getDataSubscribers(_) >> subInfo
    1 * subInfo.isEmpty() >> false
    1 * producer.publishDataEvent(_, ctx, _, _) >> { throw new ExpiredSubscriberInfoException() }
    1 * ctx.setKeepOpenForApiSecurityPostProcessing(false)
    1 * ctx.closeWafContext()
    1 * ctx.close()
    1 * sampler.releaseOne()
    0 * _
  }
}
